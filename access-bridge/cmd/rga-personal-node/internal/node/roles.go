package node

import (
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"strings"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/rtcprovider"
)

// ValidProviderRoomURL checks a provider locator, not admission authority.
// Its exact grammar excludes credentials, ports, redirects and URL suffixes.
func ValidProviderRoomURL(value string) bool {
	return rtcprovider.Valid("telemost", rtcprovider.Transport("telemost"), value)
}

// Empty is the exact historical Telemost configuration, not an unknown-provider
// fallback. Nonempty unknown names are always rejected by role validation.
func (c Config) ProviderName() string {
	if c.Provider == "" {
		return "telemost"
	}
	return c.Provider
}

// Explicit roles prevent a failed new composition from silently becoming a
// combined personal node. The legacy opt-in executable remains compatible.
func (c Config) validRoleConfiguration() bool {
	if c.Role == "" {
		return c.Provider == "" && c.ProviderRoomURL == "" && c.WorkerID == "" && c.WorkerEpoch == "" && c.RouteID == "" &&
			c.ControlOrigin == "" && c.ControlListenAddress == "" && c.ControlCAFile == "" &&
			c.ControlCertFile == "" && c.ControlKeyFile == "" && c.ControlPeerSPKI == ""
	}
	if c.Role != "home-control-v1" && c.Role != "ru-carrier-v1" {
		return false
	}
	if !rtcprovider.Valid(c.ProviderName(), rtcprovider.Transport(c.ProviderName()), c.ProviderRoomURL) || !roomIDPattern.MatchString(c.WorkerID) || !roomIDPattern.MatchString(c.WorkerEpoch) ||
		!roomIDPattern.MatchString(c.RouteID) || c.LeaseTTLSeconds != 30 ||
		c.ControlCAFile == "" || c.ControlCertFile == "" || c.ControlKeyFile == "" ||
		strings.TrimSpace(c.ControlCAFile) != c.ControlCAFile || strings.TrimSpace(c.ControlCertFile) != c.ControlCertFile ||
		strings.TrimSpace(c.ControlKeyFile) != c.ControlKeyFile || len(c.ControlPeerSPKI) != 64 ||
		strings.Trim(c.ControlPeerSPKI, "0123456789abcdef") != "" || strings.Trim(c.ControlPeerSPKI, "0") == "" {
		return false
	}
	if c.Role == "ru-carrier-v1" {
		if c.PostgresDSN != "" || c.PanelOrigin != "" || c.PanelAdminToken != "" || c.PanelAuthKind != "" ||
			c.ControlListenAddress != "" || c.BootstrapTLSCertFile != "" || c.BootstrapTLSKeyFile != "" ||
			c.BootstrapListenAddress != "" || c.BootstrapPublicHost != "" || c.BootstrapTrustedForwarder != "" ||
			!c.RequireEgressProxy {
			return false
		}
		// A per-worker local subscriber-bound Xray selector owns foreign routing.
		// The carrier cannot choose an arbitrary remote proxy or direct fallback.
		ip, err := netip.ParseAddr(c.EgressSOCKSAddress)
		if err != nil || !ip.IsLoopback() {
			return false
		}
		if c.ManagedUDPVersion != 0 {
			udpIP, err := netip.ParseAddr(c.EgressUDPSOCKSAddress)
			if err != nil || !udpIP.IsLoopback() {
				return false
			}
		}
		u, err := url.Parse(c.ControlOrigin)
		return err == nil && u.Scheme == "https" && u.Host != "" && u.Path == "" && u.User == nil &&
			u.RawQuery == "" && !u.ForceQuery && u.Fragment == "" && u.Opaque == "" && net.ParseIP(u.Hostname()) == nil
	}
	if c.ControlOrigin != "" || c.RequireEgressProxy || c.EgressSOCKSAddress != "" || c.EgressSOCKSPort != 0 ||
		c.EgressSOCKSUser != "" || c.EgressSOCKSPassword != "" || c.ManagedUDPVersion != 0 ||
		c.EgressUDPSOCKSAddress != "" || c.EgressUDPSOCKSPort != 0 || c.EgressUDPSOCKSUser != "" || c.EgressUDPSOCKSPassword != "" {
		return false
	}
	host, port, err := net.SplitHostPort(c.ControlListenAddress)
	ip, ipErr := netip.ParseAddr(host)
	p, pErr := strconv.Atoi(port)
	return err == nil && ipErr == nil && pErr == nil && p > 0 && p <= 65535 && ip.Is4() &&
		(ip.IsLoopback() || ip.IsPrivate() || ip.IsUnspecified()) && c.ControlListenAddress != c.BootstrapListenAddress
}
