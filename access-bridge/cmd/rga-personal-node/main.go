// rga-personal-node is an opt-in, single-binding managed OlcRTC node. It is
// intentionally separate from the upstream CLI and its default auth path.
package main

import (
	"context"
	"database/sql"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/cmd/rga-personal-node/internal/node"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/personal"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/rtcprovider"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/store"
	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/tunnel"
)

var errNodeStart = errors.New("personal node: startup failed")

const renewInterval = 2 * time.Minute

type managedHooks interface {
	Admit(context.Context, string, map[string]any) (personal.NodeLease, error)
	Report(context.Context, personal.NodeCumulativeReport) (personal.NodeCumulativeReport, error)
	ObserveFinal(context.Context, personal.NodeFinalTotals) error
}

type lifecycleCallbacks interface {
	OnOpen(string)
	OnClose(string)
}

// nativeAdapter is unbound until the exact tunnel.Server, service and hooks
// have been constructed. Admission and metering fail closed before binding.
type nativeAdapter struct {
	mu        sync.RWMutex
	hooks     managedHooks
	lifecycle lifecycleCallbacks
	resources *node.ResourceOwner
}

func (a *nativeAdapter) bind(hooks managedHooks, lifecycle lifecycleCallbacks) {
	a.mu.Lock()
	a.hooks, a.lifecycle = hooks, lifecycle
	a.mu.Unlock()
}

// Only the explicit fixed-QA RU role owns this resource-retirement boundary.
// Meter/final-report forwarding continues through the original hooks.
func (a *nativeAdapter) bindResources(hooks managedHooks, owner *node.ResourceOwner) {
	a.mu.Lock()
	a.hooks, a.lifecycle, a.resources = hooks, owner, owner
	a.mu.Unlock()
}

func (a *nativeAdapter) Admit(ctx context.Context, deviceID string, claims map[string]any) (personal.NodeLease, error) {
	a.mu.RLock()
	hooks := a.hooks
	resources := a.resources
	a.mu.RUnlock()
	if resources != nil {
		return resources.Admit(ctx, deviceID, claims)
	}
	if hooks == nil {
		return personal.NodeLease{}, personal.ErrNodeUnavailable
	}
	return hooks.Admit(ctx, deviceID, claims)
}

func (a *nativeAdapter) Report(ctx context.Context, report personal.NodeCumulativeReport) (personal.NodeCumulativeReport, error) {
	a.mu.RLock()
	hooks := a.hooks
	a.mu.RUnlock()
	if hooks == nil {
		return personal.NodeCumulativeReport{}, personal.ErrNodeUnavailable
	}
	return hooks.Report(ctx, report)
}

func (a *nativeAdapter) ObserveFinal(ctx context.Context, final personal.NodeFinalTotals) error {
	a.mu.RLock()
	hooks := a.hooks
	a.mu.RUnlock()
	if hooks == nil {
		return personal.ErrNodeUnavailable
	}
	return hooks.ObserveFinal(ctx, final)
}

func (a *nativeAdapter) OnOpen(id string) {
	a.mu.RLock()
	lifecycle := a.lifecycle
	a.mu.RUnlock()
	if lifecycle != nil {
		lifecycle.OnOpen(id)
	}
}

func (a *nativeAdapter) OnClose(id string) {
	a.mu.RLock()
	lifecycle := a.lifecycle
	a.mu.RUnlock()
	if lifecycle != nil {
		lifecycle.OnClose(id)
	}
}

func newTunnelConfig(cfg node.Config, hooks managedHooks, lifecycle lifecycleCallbacks) tunnel.Config {
	nativeConfig := tunnel.Config{
		Provider: cfg.ProviderName(), Transport: rtcprovider.Transport(cfg.ProviderName()), RoomURL: cfg.RoomID,
		KeyHex:         cfg.KeyHex,
		SOCKSProxyAddr: cfg.EgressSOCKSAddress, SOCKSProxyPort: cfg.EgressSOCKSPort,
		SOCKSProxyUser: cfg.EgressSOCKSUser, SOCKSProxyPass: cfg.EgressSOCKSPassword,
		Liveness:        tunnel.LivenessConfig{Interval: 10 * time.Second, Timeout: 15 * time.Second, Failures: 4},
		RequireAuthHook: true, RequireLease: true, RequireMeter: true,
		ManagedLeaseAuthHook: func(ctx context.Context, deviceID string, claims map[string]any) (tunnel.ManagedLease, error) {
			lease, err := hooks.Admit(ctx, deviceID, claims)
			if err != nil {
				return tunnel.ManagedLease{}, err
			}
			return tunnel.ManagedLease{SessionID: lease.SessionID, NotAfter: lease.NotAfter, ByteLimit: lease.ByteLimit}, nil
		},
		ManagedMeterReporter: func(ctx context.Context, report tunnel.CumulativeReport) (tunnel.CumulativeReport, error) {
			ack, err := hooks.Report(ctx, personal.NodeCumulativeReport{
				SessionID: report.SessionID, Sequence: report.Sequence,
				ClientToExitBytes: report.ClientToExitBytes, ExitToClientBytes: report.ExitToClientBytes,
				Terminal: report.Terminal,
			})
			if err != nil {
				return tunnel.CumulativeReport{}, err
			}
			return tunnel.CumulativeReport{
				SessionID: ack.SessionID, Sequence: ack.Sequence,
				ClientToExitBytes: ack.ClientToExitBytes, ExitToClientBytes: ack.ExitToClientBytes,
				Terminal: ack.Terminal,
			}, nil
		},
		OnSessionOpen:  func(id, _ string, _ map[string]any) { lifecycle.OnOpen(id) },
		OnSessionClose: func(id, _ string) { lifecycle.OnClose(id) },
	}
	if nativeConfig.Transport == "vp8channel" {
		nativeConfig.TransportOptions = tunnel.VP8Options{FPS: 30, BatchSize: 64}
	}
	if cfg.Role == "home-control-v1" || cfg.Role == "ru-carrier-v1" {
		// Provider rendezvous is distinct from the immutable authority channel.
		nativeConfig.RoomURL, nativeConfig.ChannelID = cfg.ProviderRoomURL, cfg.RoomID
	}
	if cfg.ManagedUDPVersion == 2 {
		nativeConfig.ManagedUDP = &tunnel.ManagedUDPConfig{
			Version: cfg.ManagedUDPVersion,
			Upstream: tunnel.SOCKSProxyConfig{
				Address: cfg.EgressUDPSOCKSAddress, Port: cfg.EgressUDPSOCKSPort,
				User: cfg.EgressUDPSOCKSUser, Pass: cfg.EgressUDPSOCKSPassword,
			},
			FinalObserver: func(ctx context.Context, final tunnel.FinalTotals) error {
				return hooks.ObserveFinal(ctx, personal.NodeFinalTotals{
					SessionID: final.SessionID, ClientToExitBytes: final.ClientToExitBytes,
					ExitToClientBytes: final.ExitToClientBytes,
				})
			},
		}
	}
	return nativeConfig
}

func run(ctx context.Context, configPath string) error {
	runCtx, cancelRun := context.WithCancel(ctx)
	defer cancelRun()
	cfg, err := node.LoadConfig(configPath)
	if err != nil {
		return errNodeStart
	}
	if cfg.Role == "ru-carrier-v1" {
		// RU never constructs a Panel client, database or public bootstrap.
		return runRelayCarrier(runCtx, cfg)
	}
	db, err := sql.Open("pgx", cfg.PostgresDSN)
	if err != nil {
		return errNodeStart
	}
	defer db.Close()
	db.SetMaxOpenConns(4)
	check, cancel := context.WithTimeout(runCtx, 5*time.Second)
	defer cancel()
	if db.PingContext(check) != nil {
		return errNodeStart
	}
	journal, err := store.New(db)
	if err != nil {
		return errNodeStart
	}
	personalConfig := cfg.PersonalTestConfig()
	if _, err := journal.Load(check, personalConfig.Subject); err != nil {
		// The schema and A063 subject must be provisioned separately. This
		// executable never migrates or creates production control state.
		return errNodeStart
	}
	panel, err := newPanelClient(cfg, nil)
	if err != nil {
		return errNodeStart
	}
	service, err := personal.NewService(personalConfig, panel, journal)
	if err != nil {
		return errNodeStart
	}
	var bootstrapServer *http.Server
	bootstrapFailure := make(chan struct{}, 1)
	if cfg.BootstrapTLSCertFile != "" {
		eligibility, err := access.NewExactPersonalInstallationEligibility(personalConfig.Subject, personalConfig.Installation)
		if err != nil {
			return errNodeStart
		}
		linker, err := access.NewLinkService(personalConfig.Subject.PanelInstance, panel, journal, eligibility, nil)
		if err != nil {
			return errNodeStart
		}
		bootstrap, err := personal.NewBootstrapService(personal.BootstrapConfig{
			Subject: personalConfig.Subject, Installation: personalConfig.Installation,
			RoomID: cfg.RoomID, Country: cfg.Country, RoomKey: cfg.KeyHex,
		}, linker, journal, panel)
		if err != nil {
			return errNodeStart
		}
		rateGuard := newBootstrapRateGuard(time.Now, bootstrapGlobalPerMinute, bootstrapDevicePerMinute)
		handler := newBootstrapHandlerWithBoundary(personalConfig.Installation, linker, bootstrap,
			rateGuard,
			func(r *http.Request) bool {
				return bootstrapRequestBoundaryWithIngress(r, cfg.BootstrapPublicHost, cfg.BootstrapTrustedForwarder)
			})
		if cfg.Role == "home-control-v1" {
			handler = newManagedProviderBootstrapHandlerWithBoundary(personalConfig.Subject, personalConfig.Installation, cfg.ProviderName(), cfg.ProviderRoomURL, linker, bootstrap,
				rateGuard,
				func(r *http.Request) bool {
					return bootstrapRequestBoundaryWithIngress(r, cfg.BootstrapPublicHost, cfg.BootstrapTrustedForwarder)
				}, time.Now)
		}
		var done <-chan error
		bootstrapServer, done, err = startBootstrapServerOnAddress(cfg.BootstrapAddress(), cfg.BootstrapTLSCertFile, cfg.BootstrapTLSKeyFile, handler)
		if err != nil {
			return errNodeStart
		}
		defer func() {
			shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			_ = bootstrapServer.Shutdown(shutdown)
			log.Print(rateGuard.bootstrapSummary())
		}()
		go func() {
			if serveErr := <-done; serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
				bootstrapFailure <- struct{}{}
				cancelRun()
			}
		}()
	}
	if cfg.Role == "home-control-v1" {
		// Home exits this branch before any RTC/native carrier is constructed.
		return nodeRunResult(runHomeControl(runCtx, cfg, service, journal), bootstrapFailure)
	}
	adapter := &nativeAdapter{}
	native := tunnel.New(newTunnelConfig(cfg, adapter, adapter))
	hooks, err := personal.NewNodeHooks(personalConfig.Subject, personalConfig.Installation, service, journal, native)
	if err != nil {
		return errNodeStart
	}
	lifecycle := node.NewLifecycle(runCtx, hooks, native, renewInterval)
	adapter.bind(hooks, lifecycle)
	shutdownDone := make(chan struct{})
	go func() {
		defer close(shutdownDone)
		<-runCtx.Done()
		lifecycle.Shutdown()
	}()
	err = native.Run(runCtx)
	lifecycle.Shutdown()
	cancelRun()
	<-shutdownDone
	return nodeRunResult(err, bootstrapFailure)
}

func nodeRunResult(err error, bootstrapFailure <-chan struct{}) error {
	select {
	case <-bootstrapFailure:
		return errNodeStart
	default:
	}
	if err != nil {
		return errNodeStart
	}
	return nil
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if run(ctx, os.Getenv("RGA_PERSONAL_NODE_CONFIG")) != nil {
		// No source error is printed: provider, driver and file errors can
		// contain a secret DSN, room or credential.
		log.Print("personal node stopped with an error")
		os.Exit(1)
	}
}
