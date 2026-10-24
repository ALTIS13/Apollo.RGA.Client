package main

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/cmd/rga-personal-node/internal/node"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/personal"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/tunnel"
)

type fakeManagedHooks struct {
	gotDeviceID string
	gotClaims   map[string]any
	gotReport   personal.NodeCumulativeReport
	gotFinal    personal.NodeFinalTotals
	observe     func(context.Context, personal.NodeFinalTotals) error
}

func (h *fakeManagedHooks) Admit(_ context.Context, deviceID string, claims map[string]any) (personal.NodeLease, error) {
	h.gotDeviceID, h.gotClaims = deviceID, claims
	return personal.NodeLease{SessionID: "test-session-0001", NotAfter: time.Now().Add(time.Minute), ByteLimit: 1 << 40}, nil
}

func (h *fakeManagedHooks) Report(_ context.Context, report personal.NodeCumulativeReport) (personal.NodeCumulativeReport, error) {
	h.gotReport = report
	return report, nil
}

func (h *fakeManagedHooks) ObserveFinal(ctx context.Context, final personal.NodeFinalTotals) error {
	h.gotFinal = final
	if h.observe != nil {
		return h.observe(ctx, final)
	}
	return nil
}

type fakeLifecycle struct{ opened, closed string }

func (l *fakeLifecycle) OnOpen(id string)  { l.opened = id }
func (l *fakeLifecycle) OnClose(id string) { l.closed = id }

func TestPersonalNativeConfigUsesOnlyManagedLeaseAndDurableMeter(t *testing.T) {
	cfg := node.Config{RoomID: "room_A063_01", KeyHex: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
	hooks, lifecycle := &fakeManagedHooks{}, &fakeLifecycle{}
	native := newTunnelConfig(cfg, hooks, lifecycle)
	if native.Provider != "telemost" || native.Transport != "vp8channel" || native.RoomURL != cfg.RoomID ||
		!native.RequireLease || !native.RequireMeter || !native.RequireAuthHook || native.AuthHook != nil ||
		native.ManagedLeaseAuthHook == nil || native.ManagedMeterReporter == nil || native.KeyHex != cfg.KeyHex {
		t.Fatal("native config is not exclusively managed and bound to proof room")
	}
	claims := map[string]any{"nonce": "signed-proof"}
	lease, err := native.ManagedLeaseAuthHook(context.Background(), "untrusted-device", claims)
	if err != nil || lease.SessionID != "test-session-0001" || lease.ByteLimit != 1<<40 ||
		hooks.gotDeviceID != "untrusted-device" || hooks.gotClaims["nonce"] != "signed-proof" {
		t.Fatalf("managed lease adapter result = %#v, err=%v", lease, err)
	}
	report, err := native.ManagedMeterReporter(context.Background(), tunnel.CumulativeReport{
		SessionID: "test-session-0001", Sequence: 7, ClientToExitBytes: 111, ExitToClientBytes: 222,
	})
	if err != nil || report.Sequence != 7 || report.ClientToExitBytes != 111 || report.ExitToClientBytes != 222 ||
		hooks.gotReport.Sequence != 7 || hooks.gotReport.ExitToClientBytes != 222 {
		t.Fatalf("durable report adapter result = %#v, err=%v", report, err)
	}
	native.OnSessionOpen("test-session-0001", "device", claims)
	native.OnSessionClose("test-session-0001", "closed")
	if lifecycle.opened != "test-session-0001" || lifecycle.closed != "test-session-0001" {
		t.Fatal("native callbacks did not drive the exact session lifecycle")
	}
}

func TestUnboundNativeAdapterFailsClosed(t *testing.T) {
	adapter := &nativeAdapter{}
	if _, err := adapter.Admit(context.Background(), "ignored", nil); !errors.Is(err, personal.ErrNodeUnavailable) {
		t.Fatalf("unbound admission error = %v", err)
	}
	if _, err := adapter.Report(context.Background(), personal.NodeCumulativeReport{SessionID: "test-session-0001"}); !errors.Is(err, personal.ErrNodeUnavailable) {
		t.Fatalf("unbound reporter error = %v", err)
	}
}

func TestPersonalNativeEgressUsesConfiguredRelayProxy(t *testing.T) {
	cfg := node.Config{EgressSOCKSAddress: "rga-egress", EgressSOCKSPort: 1080, EgressSOCKSUser: "test-user", EgressSOCKSPassword: "test-pass", RequireEgressProxy: true}
	native := newTunnelConfig(cfg, &fakeManagedHooks{}, &fakeLifecycle{})
	if native.SOCKSProxyAddr != "rga-egress" || native.SOCKSProxyPort != 1080 || native.SOCKSProxyUser != "test-user" || native.SOCKSProxyPass != "test-pass" {
		t.Fatal("user egress lost required relay proxy")
	}
	if native.Provider != "telemost" || native.Transport != "vp8channel" {
		t.Fatal("user egress changed carrier provider")
	}
}

func TestPersonalNativeTerminalReportPreservesExactFinality(t *testing.T) {
	hooks := &fakeManagedHooks{}
	native := newTunnelConfig(node.Config{RoomID: "room_A063_01"}, hooks, &fakeLifecycle{})
	final := tunnel.CumulativeReport{
		SessionID: "test-session-0001", Sequence: 8,
		ClientToExitBytes: 123, ExitToClientBytes: 456, Terminal: true,
	}
	ack, err := native.ManagedMeterReporter(context.Background(), final)
	if err != nil || ack != final || hooks.gotReport.SessionID != final.SessionID ||
		!hooks.gotReport.Terminal || hooks.gotReport.ClientToExitBytes != 123 ||
		hooks.gotReport.ExitToClientBytes != 456 {
		t.Fatalf("terminal adapter lost exact finality: ack=%+v err=%v hook=%+v", ack, err, hooks.gotReport)
	}
}

func TestNewTunnelConfigManagedUDPActualProjection(t *testing.T) {
	// Break caught: projecting UDP onto the TCP selector, dropping observer/mirror fields, or weakening Require guards.
	cfg := node.Config{ManagedUDPVersion: 2, EgressUDPSOCKSAddress: "udp-gateway", EgressUDPSOCKSPort: 5080,
		EgressUDPSOCKSUser: "udp-user", EgressUDPSOCKSPassword: "udp-password",
		EgressSOCKSAddress: "tcp-gateway", EgressSOCKSPort: 1080, EgressSOCKSUser: "tcp-user", EgressSOCKSPassword: "tcp-password"}
	hooks := &fakeManagedHooks{}
	native := newTunnelConfig(cfg, hooks, &fakeLifecycle{})
	if native.ManagedUDP == nil || native.ManagedUDP.Version != 2 || native.ManagedUDP.Upstream.Address != "udp-gateway" ||
		native.ManagedUDP.Upstream.Port != 5080 || native.ManagedUDP.Upstream.User != "udp-user" || native.ManagedUDP.Upstream.Pass != "udp-password" ||
		native.ManagedUDP.FinalObserver == nil || !native.RequireAuthHook || !native.RequireLease || !native.RequireMeter ||
		native.SOCKSProxyAddr != "tcp-gateway" || native.SOCKSProxyPort != 1080 || native.SOCKSProxyUser != "tcp-user" || native.SOCKSProxyPass != "tcp-password" {
		t.Fatal("actual native config did not preserve distinct managed UDP/TCP selectors and mandatory guards")
	}
	if err := native.ManagedUDP.FinalObserver(context.Background(), tunnel.FinalTotals{SessionID: "test-session-0001", ClientToExitBytes: 123, ExitToClientBytes: 456}); err != nil ||
		hooks.gotFinal != (personal.NodeFinalTotals{SessionID: "test-session-0001", ClientToExitBytes: 123, ExitToClientBytes: 456}) || hooks.gotReport != (personal.NodeCumulativeReport{}) {
		t.Fatal("native final callback lost typed observation or manufactured cumulative report")
	}
	if legacy := newTunnelConfig(node.Config{}, hooks, &fakeLifecycle{}); legacy.ManagedUDP != nil {
		t.Fatal("default node configured a managed UDP handler")
	}
}

func TestNativeAdapterObserveFinalOutsideBindingLock(t *testing.T) {
	// Break caught: synchronous journal callback under the adapter mutex blocks rebinding/cleanup.
	adapter := &nativeAdapter{}
	hooks := &fakeManagedHooks{}
	hooks.observe = func(context.Context, personal.NodeFinalTotals) error {
		if !adapter.mu.TryLock() {
			return errors.New("observer held binding lock")
		}
		adapter.mu.Unlock()
		return nil
	}
	adapter.bind(hooks, &fakeLifecycle{})
	final := personal.NodeFinalTotals{SessionID: "test-session-0001", ClientToExitBytes: 1, ExitToClientBytes: 2}
	if err := adapter.ObserveFinal(context.Background(), final); err != nil || hooks.gotFinal != final {
		t.Fatal("bound final observation did not run outside adapter lock")
	}
}

func TestUnboundNativeAdapterObserveFinalFailsClosed(t *testing.T) {
	if err := (&nativeAdapter{}).ObserveFinal(context.Background(), personal.NodeFinalTotals{}); !errors.Is(err, personal.ErrNodeUnavailable) {
		t.Fatalf("unbound final observer accepted: %v", err)
	}
}
