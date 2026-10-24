package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"runtime"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/cmd/rga-personal-node/internal/node"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/personal"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/relaycontrol"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/tunnel"
)

func controlScope(cfg node.Config) relaycontrol.Scope {
	return relaycontrol.Scope{Worker: cfg.WorkerID, Epoch: cfg.WorkerEpoch, Room: cfg.RoomID, Route: cfg.RouteID, Provider: cfg.ProviderName()}
}

// TLS terminates here, not at an HTTP forwarder. No forwarded certificate
// header or Home public subscription token can impersonate a node-control peer.
func loadControlTLS(cfg node.Config) (*x509.CertPool, tls.Certificate, [32]byte, error) {
	var peer [32]byte
	keyInfo, err := os.Stat(cfg.ControlKeyFile)
	if err != nil || !bootstrapTLSKeyModeOK(keyInfo.Mode(), runtime.GOOS) {
		return nil, tls.Certificate{}, peer, errNodeStart
	}
	cert, err := tls.LoadX509KeyPair(cfg.ControlCertFile, cfg.ControlKeyFile)
	if err != nil {
		return nil, tls.Certificate{}, peer, errNodeStart
	}
	f, err := os.Open(cfg.ControlCAFile)
	if err != nil {
		return nil, tls.Certificate{}, peer, errNodeStart
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, (256<<10)+1))
	roots := x509.NewCertPool()
	if err != nil || len(b) > 256<<10 || !roots.AppendCertsFromPEM(b) {
		return nil, tls.Certificate{}, peer, errNodeStart
	}
	decoded, err := hex.DecodeString(cfg.ControlPeerSPKI)
	if err != nil || len(decoded) != len(peer) {
		return nil, tls.Certificate{}, peer, errNodeStart
	}
	copy(peer[:], decoded)
	return roots, cert, peer, nil
}

func runHomeControl(ctx context.Context, cfg node.Config, issuer relaycontrol.Issuer, journal relaycontrol.Journal) error {
	if cfg.Role != "home-control-v1" {
		return errNodeStart
	}
	roots, cert, peer, err := loadControlTLS(cfg)
	if err != nil {
		return errNodeStart
	}
	personalConfig := cfg.PersonalTestConfig()
	h, err := relaycontrol.NewHandler(relaycontrol.Binding{Scope: controlScope(cfg),
		Subject: personalConfig.Subject, Installation: personalConfig.Installation, ClientSPKI: peer}, issuer, journal)
	if err != nil {
		return errNodeStart
	}
	tlsConfig, err := relaycontrol.ServerTLS(roots, cert)
	if err != nil {
		return errNodeStart
	}
	listener, err := net.Listen("tcp4", cfg.ControlListenAddress)
	if err != nil {
		return errNodeStart
	}
	srv := &http.Server{Handler: h, ReadHeaderTimeout: 3 * time.Second, ReadTimeout: 5 * time.Second,
		WriteTimeout: 6 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 4 << 10,
		ErrorLog: log.New(io.Discard, "", 0)}
	done := make(chan error, 1)
	go func() { done <- srv.Serve(tls.NewListener(listener, tlsConfig)) }()
	select {
	case <-ctx.Done():
		shutdown, cancel := context.WithTimeout(context.Background(), 6*time.Second)
		err := srv.Shutdown(shutdown)
		cancel()
		if err != nil {
			_ = srv.Close()
		}
		serveErr := <-done
		if err != nil || !errors.Is(serveErr, http.ErrServerClosed) {
			return errNodeStart
		}
		return nil
	case <-done:
		_ = srv.Close()
		return errNodeStart
	}
}

func runRelayCarrier(ctx context.Context, cfg node.Config) error {
	if cfg.Role != "ru-carrier-v1" {
		return errNodeStart
	}
	roots, cert, peer, err := loadControlTLS(cfg)
	if err != nil {
		return errNodeStart
	}
	bound := cfg.PersonalTestConfig()
	remote, err := relaycontrol.NewClient(relaycontrol.ClientConfig{Origin: cfg.ControlOrigin,
		Scope: controlScope(cfg), Subject: bound.Subject, Installation: bound.Installation,
		Roots: roots, Certificate: cert, ServerSPKI: peer})
	if err != nil {
		return errNodeStart
	}
	defer remote.CloseIdleConnections()
	adapter := &nativeAdapter{}
	// ai-generated: bounded diagnostics exist only on the RU carrier, never Home control.
	connect := relayConnectDiagnostics{emit: func(line string) { log.Print(line) }}
	defer connect.summary()
	nativeConfig := newTunnelConfig(cfg, adapter, adapter)
	nativeConfig.OnConnectObservation = connect.observe
	native := tunnel.New(nativeConfig)
	hooks, err := personal.NewNodeHooks(bound.Subject, bound.Installation, remote, remote, native)
	if err != nil {
		return errNodeStart
	}
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	lifecycle := node.NewRelayLifecycle(runCtx, hooks, native)
	owner := node.NewResourceOwner(runCtx, hooks.Admit, lifecycle, native, cancel)
	adapter.bindResources(hooks, owner)
	stopping := make(chan struct{})
	go func() {
		defer close(stopping)
		<-runCtx.Done()
		owner.Fence()
		lifecycle.Shutdown()
	}()
	err = native.Run(runCtx)
	owner.Fence()
	lifecycle.Shutdown()
	cancel()
	<-stopping
	// Run-return alone is not provider ACK/exit or an enclosing process join.
	// Use the original outer context: no new cleanup deadline is minted here.
	ownerJoin := owner.JoinWorkers(ctx)
	lifecycleJoin := lifecycle.JoinWorkers(ctx)
	if err != nil || ownerJoin != nil || lifecycleJoin != nil {
		return errNodeStart
	}
	return nil
}
