package node

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/netip"
	"net/url"
	"os"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

var ErrConfig = errors.New("personal node: invalid configuration")

const (
	maxConfigBytes          = 16 << 10
	technicalSessionByteCap = 1 << 40
)

var roomIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{8,128}$`)

// Config is a server-owned, single-installation node contract. It deliberately
// has no arbitrary provider, mode or auth override.
type Config struct {
	Role                        string `json:"role"`
	WorkerID                    string `json:"worker_id"`
	WorkerEpoch                 string `json:"worker_epoch"`
	RouteID                     string `json:"route_id"`
	ControlOrigin               string `json:"control_origin"`
	ControlListenAddress        string `json:"control_listen_address"`
	ControlCAFile               string `json:"control_ca_file"`
	ControlCertFile             string `json:"control_cert_file"`
	ControlKeyFile              string `json:"control_key_file"`
	ControlPeerSPKI             string `json:"control_peer_spki_sha256"`
	PostgresDSN                 string `json:"postgres_dsn"`
	PanelOrigin                 string `json:"panel_origin"`
	PanelAdminToken             string `json:"panel_admin_token"`
	PanelAuthKind               string `json:"panel_auth_kind"`
	PanelInstance               string `json:"panel_instance"`
	UserID                      int64  `json:"user_id"`
	InstallationID              string `json:"installation_id"`
	InstallationPublicKeySHA256 string `json:"installation_public_key_sha256"`
	RoomID                      string `json:"room_id"`
	Provider                    string `json:"provider"`
	ProviderRoomURL             string `json:"provider_room_url"`
	Country                     string `json:"country"`
	KeyHex                      string `json:"key_hex"`
	LeaseTTLSeconds             int64  `json:"lease_ttl_seconds"`
	MaxSnapshotAgeSeconds       int64  `json:"max_snapshot_age_seconds"`
	BootstrapTLSCertFile        string `json:"bootstrap_tls_cert_file"`
	BootstrapTLSKeyFile         string `json:"bootstrap_tls_key_file"`
	BootstrapListenAddress      string `json:"bootstrap_listen_address"`
	BootstrapPublicHost         string `json:"bootstrap_public_host"`
	BootstrapTrustedForwarder   string `json:"bootstrap_trusted_forwarder"`
	EgressSOCKSAddress          string `json:"egress_socks_address"`
	EgressSOCKSPort             int    `json:"egress_socks_port"`
	EgressSOCKSUser             string `json:"egress_socks_user"`
	EgressSOCKSPassword         string `json:"egress_socks_password"`
	RequireEgressProxy          bool   `json:"require_egress_proxy"`
	ManagedUDPVersion           uint8  `json:"managed_udp_version"`
	EgressUDPSOCKSAddress       string `json:"egress_udp_socks_address"`
	EgressUDPSOCKSPort          int    `json:"egress_udp_socks_port"`
	EgressUDPSOCKSUser          string `json:"egress_udp_socks_user"`
	EgressUDPSOCKSPassword      string `json:"egress_udp_socks_password"`
}

// LoadConfig reads a bounded JSON object and never includes file content,
// path, DSN, room, or credentials in returned errors.
func LoadConfig(path string) (Config, error) {
	if path == "" {
		return Config{}, ErrConfig
	}
	f, err := os.Open(path)
	if err != nil {
		return Config{}, ErrConfig
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || !acceptableConfigMode(info.Mode(), runtime.GOOS) {
		return Config{}, ErrConfig
	}
	data, err := io.ReadAll(io.LimitReader(f, maxConfigBytes+1))
	if err != nil || len(data) > maxConfigBytes {
		return Config{}, ErrConfig
	}
	// Reject duplicate names instead of allowing the last JSON value to
	// silently change the server's immutable room or installation binding.
	if !uniqueObjectKeys(data) {
		return Config{}, ErrConfig
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	var cfg Config
	if decoder.Decode(&cfg) != nil || decoder.Decode(new(any)) != io.EOF || cfg.validate() != nil {
		return Config{}, ErrConfig
	}
	return cfg, nil
}

func acceptableConfigMode(mode os.FileMode, goos string) bool {
	if !mode.IsRegular() {
		return false
	}
	if goos == "linux" {
		perms := mode.Perm()
		return perms&0400 != 0 && perms&0077 == 0
	}
	return true
}

func uniqueObjectKeys(data []byte) bool {
	d := json.NewDecoder(bytes.NewReader(data))
	first, err := d.Token()
	if err != nil || first != json.Delim('{') {
		return false
	}
	seen := make(map[string]bool)
	for d.More() {
		key, err := d.Token()
		name, ok := key.(string)
		if err != nil || !ok || seen[name] {
			return false
		}
		seen[name] = true
		var value json.RawMessage
		if d.Decode(&value) != nil {
			return false
		}
	}
	last, err := d.Token()
	return err == nil && last == json.Delim('}')
}

func (c Config) validate() error {
	if !c.validRoleConfiguration() || !c.validNetworkConfiguration() || !c.validManagedUDPConfiguration() {
		return ErrConfig
	}
	if c.PanelAuthKind != "" && c.PanelAuthKind != "api_token" && c.PanelAuthKind != "browser_session" {
		return ErrConfig
	}
	if (c.BootstrapTLSCertFile == "") != (c.BootstrapTLSKeyFile == "") ||
		c.BootstrapTLSCertFile != "" && strings.TrimSpace(c.BootstrapTLSCertFile) == "" ||
		c.BootstrapTLSKeyFile != "" && strings.TrimSpace(c.BootstrapTLSKeyFile) == "" {
		return ErrConfig
	}
	if c.Role != "ru-carrier-v1" {
		uri, err := url.Parse(c.PostgresDSN)
		if err != nil || (uri.Scheme != "postgres" && uri.Scheme != "postgresql") || uri.Host == "" || strings.Trim(uri.Path, "/") == "" {
			return ErrConfig
		}
		if _, err := remnawave.NewClient(c.PanelOrigin, c.PanelAdminToken, nil); err != nil {
			return ErrConfig
		}
	}
	if !roomIDPattern.MatchString(c.RoomID) || len(c.Country) != 2 ||
		c.Country[0] < 'A' || c.Country[0] > 'Z' || c.Country[1] < 'A' || c.Country[1] > 'Z' ||
		c.LeaseTTLSeconds < 1 || c.LeaseTTLSeconds > 300 ||
		c.MaxSnapshotAgeSeconds < 1 || c.MaxSnapshotAgeSeconds > 30 ||
		len(c.KeyHex) != 64 || strings.Trim(c.KeyHex, "0123456789abcdef") != "" {
		return ErrConfig
	}
	if _, err := hex.DecodeString(c.KeyHex); err != nil || c.KeyHex == strings.Repeat("0", 64) {
		return ErrConfig
	}
	if len(c.InstallationPublicKeySHA256) != 64 ||
		strings.Trim(c.InstallationPublicKeySHA256, "0123456789abcdef") != "" {
		return ErrConfig
	}
	personal := c.PersonalTestConfig()
	if personal.Subject.Validate() != nil || personal.Installation.Validate() != nil {
		return ErrConfig
	}
	return nil
}

// A container may listen on its unpublished private bridge. A public literal,
// hostname, malformed address or non-TLS custom listener is never accepted.
func (c Config) BootstrapAddress() string {
	if c.BootstrapListenAddress == "" {
		return "127.0.0.1:18443"
	}
	return c.BootstrapListenAddress
}

func (c Config) validNetworkConfiguration() bool {
	if c.BootstrapPublicHost != "" || c.BootstrapTrustedForwarder != "" {
		ip, err := netip.ParseAddr(c.BootstrapTrustedForwarder)
		if c.BootstrapPublicHost != "rga.apollot.ru" || err != nil || !ip.Is4() || !ip.IsPrivate() ||
			c.BootstrapListenAddress == "" || c.Role != "home-control-v1" && !c.RequireEgressProxy || c.BootstrapTLSCertFile == "" {
			return false
		}
	}
	if c.BootstrapListenAddress != "" {
		host, port, err := net.SplitHostPort(c.BootstrapListenAddress)
		ip, ipErr := netip.ParseAddr(host)
		p, portErr := strconv.Atoi(port)
		if err != nil || ipErr != nil || !ip.Is4() || portErr != nil || p < 1 || p > 65535 ||
			!(ip.IsLoopback() || ip.IsPrivate() || ip.IsUnspecified()) ||
			c.BootstrapTLSCertFile == "" || c.BootstrapTLSKeyFile == "" {
			return false
		}
	}
	if c.EgressSOCKSAddress == "" {
		return !c.RequireEgressProxy && c.EgressSOCKSPort == 0 && c.EgressSOCKSUser == "" && c.EgressSOCKSPassword == ""
	}
	if c.EgressSOCKSPort < 1 || c.EgressSOCKSPort > 65535 ||
		len(c.EgressSOCKSAddress) > 253 || len(c.EgressSOCKSUser) > 255 || len(c.EgressSOCKSPassword) > 255 ||
		(c.EgressSOCKSUser == "") != (c.EgressSOCKSPassword == "") ||
		strings.TrimSpace(c.EgressSOCKSAddress) != c.EgressSOCKSAddress {
		return false
	}
	if _, err := netip.ParseAddr(c.EgressSOCKSAddress); err == nil {
		return true
	}
	for _, label := range strings.Split(c.EgressSOCKSAddress, ".") {
		if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, ch := range label {
			if !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '-') {
				return false
			}
		}
	}
	return true
}

func (c Config) validManagedUDPConfiguration() bool {
	if c.ManagedUDPVersion == 0 {
		return c.EgressUDPSOCKSAddress == "" && c.EgressUDPSOCKSPort == 0 &&
			c.EgressUDPSOCKSUser == "" && c.EgressUDPSOCKSPassword == ""
	}
	if c.ManagedUDPVersion != 2 || c.EgressUDPSOCKSAddress == "" ||
		c.EgressUDPSOCKSPort < 1 || c.EgressUDPSOCKSPort > 65535 ||
		len(c.EgressUDPSOCKSAddress) > 253 || len(c.EgressUDPSOCKSUser) > 255 || len(c.EgressUDPSOCKSPassword) > 255 ||
		(c.EgressUDPSOCKSUser == "") != (c.EgressUDPSOCKSPassword == "") ||
		strings.TrimSpace(c.EgressUDPSOCKSAddress) != c.EgressUDPSOCKSAddress {
		return false
	}
	if _, err := netip.ParseAddr(c.EgressUDPSOCKSAddress); err == nil {
		return true
	}
	for _, label := range strings.Split(c.EgressUDPSOCKSAddress, ".") {
		if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, ch := range label {
			if !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '-') {
				return false
			}
		}
	}
	return true
}

func (c Config) PersonalTestConfig() access.PersonalTestConfig {
	var fingerprint [32]byte
	decoded, _ := hex.DecodeString(c.InstallationPublicKeySHA256)
	copy(fingerprint[:], decoded)
	return access.PersonalTestConfig{
		Subject:      access.Subject{PanelInstance: c.PanelInstance, UserID: c.UserID},
		Installation: access.InstallationBinding{ID: c.InstallationID, PublicKeySHA256: fingerprint},
		RoomID:       c.RoomID, Country: c.Country,
		LeaseTTL:       time.Duration(c.LeaseTTLSeconds) * time.Second,
		MaxSnapshotAge: time.Duration(c.MaxSnapshotAgeSeconds) * time.Second,
		SessionByteCap: technicalSessionByteCap,
	}
}
