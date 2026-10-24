// rga-personal-local-setup provisions only the dedicated loopback A063 test DB.
// It is not a general account or subscription management command.
package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/url"
	"os"
	"regexp"
	"strings"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/cmd/rga-personal-node/internal/node"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/store"
	_ "github.com/jackc/pgx/v5/stdlib"
)

const (
	panelInstance = "rga-main"
	panelOrigin   = "https://dashb.gate-altas.tech"
	subOrigin     = "https://pan.gate-altas.tech"
	localDBName   = "apollo_rga_bridge_test"
)

var roomIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{8,128}$`)

type panelSession struct {
	PanelOrigin     string `json:"panel_origin"`
	PanelBrowserJWT string `json:"panel_browser_jwt"`
	UserID          int64  `json:"user_id"`
	SubscriptionURL string `json:"subscription_url"`
}

type binding struct {
	InstallationID     string `json:"installationId"`
	PublicKeySHA256Hex string `json:"publicKeySha256"`
}

func loadStrict(path string, limit int64, target any) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, limit+1))
	if err != nil {
		return err
	}
	if int64(len(data)) > limit {
		return errors.New("oversized input")
	}
	if !uniqueTopLevelObjectKeys(data) {
		return errors.New("invalid private input")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if decoder.Decode(target) != nil || decoder.Decode(new(any)) != io.EOF {
		return errors.New("invalid private input")
	}
	return nil
}

func uniqueTopLevelObjectKeys(data []byte) bool {
	decoder := json.NewDecoder(bytes.NewReader(data))
	opening, err := decoder.Token()
	if err != nil || opening != json.Delim('{') {
		return false
	}
	seen := make(map[string]bool)
	for decoder.More() {
		key, err := decoder.Token()
		name, ok := key.(string)
		if err != nil || !ok || seen[name] {
			return false
		}
		seen[name] = true
		var value json.RawMessage
		if decoder.Decode(&value) != nil {
			return false
		}
	}
	closing, err := decoder.Token()
	if err != nil || closing != json.Delim('}') {
		return false
	}
	return decoder.Decode(new(any)) == io.EOF
}

func validLocalDB(dsn string) bool {
	u, err := url.Parse(dsn)
	if err != nil || u.User == nil {
		return false
	}
	_, hasPassword := u.User.Password()
	query := u.Query()
	return (u.Scheme == "postgres" || u.Scheme == "postgresql") &&
		u.Host == "127.0.0.1:55433" && u.Path == "/"+localDBName &&
		u.User.Username() == "bridge_test" && !hasPassword &&
		u.RawPath == "" && u.Fragment == "" && u.Opaque == "" &&
		len(query) == 1 && len(query["sslmode"]) == 1 && query.Get("sslmode") == "disable"
}

func validSession(s panelSession) bool {
	if s.PanelOrigin != panelOrigin || s.UserID < 1 || s.PanelBrowserJWT == "" ||
		len(s.PanelBrowserJWT) > 8192 || !strings.HasPrefix(s.SubscriptionURL, subOrigin+"/") {
		return false
	}
	u, err := url.Parse(s.SubscriptionURL)
	return err == nil && u.Scheme == "https" && u.Host == "pan.gate-altas.tech" &&
		u.User == nil && u.RawQuery == "" && u.Fragment == "" &&
		regexp.MustCompile(`^/[A-Za-z0-9_-]{8,128}$`).MatchString(u.EscapedPath())
}

func loadKey(path string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil || len(data) > 128 {
		return "", errors.New("invalid room key")
	}
	key := strings.TrimSpace(string(data))
	if !regexp.MustCompile(`^[0-9a-f]{64}$`).MatchString(key) || key == strings.Repeat("0", 64) {
		return "", errors.New("invalid room key")
	}
	return key, nil
}

func prepareConfig(session panelSession, link binding, dsn, roomID, keyHex, cert, privateKey string) (node.Config, access.Subject, access.InstallationBinding, error) {
	if !validLocalDB(dsn) || !validSession(session) || !roomIDPattern.MatchString(roomID) {
		return node.Config{}, access.Subject{}, access.InstallationBinding{}, errors.New("invalid local setup")
	}
	fingerprint, err := hex.DecodeString(link.PublicKeySHA256Hex)
	if err != nil || len(fingerprint) != 32 || link.PublicKeySHA256Hex != strings.ToLower(link.PublicKeySHA256Hex) {
		return node.Config{}, access.Subject{}, access.InstallationBinding{}, errors.New("invalid binding")
	}
	var digest [32]byte
	copy(digest[:], fingerprint)
	subject := access.Subject{PanelInstance: panelInstance, UserID: session.UserID}
	installation := access.InstallationBinding{ID: link.InstallationID, PublicKeySHA256: digest}
	if subject.Validate() != nil || installation.Validate() != nil {
		return node.Config{}, access.Subject{}, access.InstallationBinding{}, errors.New("invalid binding")
	}
	if _, err := tls.LoadX509KeyPair(cert, privateKey); err != nil {
		return node.Config{}, access.Subject{}, access.InstallationBinding{}, errors.New("invalid local TLS")
	}
	return node.Config{
		PostgresDSN: dsn, PanelOrigin: panelOrigin, PanelAdminToken: session.PanelBrowserJWT,
		PanelAuthKind: "browser_session", PanelInstance: panelInstance, UserID: session.UserID,
		InstallationID: installation.ID, InstallationPublicKeySHA256: link.PublicKeySHA256Hex,
		RoomID: roomID, Country: "NL", KeyHex: keyHex,
		LeaseTTLSeconds: 300, MaxSnapshotAgeSeconds: 30,
		BootstrapTLSCertFile: cert, BootstrapTLSKeyFile: privateKey,
	}, subject, installation, nil
}

func setup(ctx context.Context, cfg node.Config, subject access.Subject, installation access.InstallationBinding, output string) error {
	if _, err := os.Stat(output); !errors.Is(err, os.ErrNotExist) {
		return errors.New("private config output already exists")
	}
	db, err := sql.Open("pgx", cfg.PostgresDSN)
	if err != nil {
		return err
	}
	defer db.Close()
	db.SetMaxOpenConns(2)
	if err := db.PingContext(ctx); err != nil {
		return err
	}
	journal, err := store.New(db)
	if err != nil {
		return err
	}
	if err := journal.Migrate(ctx); err != nil {
		return err
	}
	if _, err := journal.EnsureSubject(ctx, subject); err != nil {
		return err
	}
	if err := journal.BindInstallation(ctx, subject, installation); err != nil {
		return err
	}
	if _, err := journal.SetProtocolEnabled(ctx, subject, true); err != nil {
		return err
	}
	if err := journal.SetQAJournalingEnabled(ctx, subject, installation.ID, true); err != nil {
		return err
	}
	encoded, err := json.Marshal(cfg)
	if err != nil {
		return err
	}
	file, err := os.OpenFile(output, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	defer file.Close()
	if _, err := file.Write(append(encoded, '\n')); err != nil {
		return err
	}
	return file.Sync()
}

func main() {
	var sessionPath, bindingPath, roomKeyPath, roomID, certPath, keyPath, outPath string
	flag.StringVar(&sessionPath, "session", "", "private panel browser session JSON")
	flag.StringVar(&bindingPath, "binding", "", "private A063 binding JSON")
	flag.StringVar(&roomKeyPath, "room-key", "", "private room key file")
	flag.StringVar(&roomID, "room-id", "", "existing bare Telemost room ID")
	flag.StringVar(&certPath, "tls-cert", "", "private test TLS certificate file")
	flag.StringVar(&keyPath, "tls-key", "", "private test TLS key file")
	flag.StringVar(&outPath, "out", "", "new private node config JSON")
	flag.Parse()
	var session panelSession
	var link binding
	keyHex, keyErr := loadKey(roomKeyPath)
	if sessionPath == "" || bindingPath == "" || certPath == "" || keyPath == "" || outPath == "" ||
		loadStrict(sessionPath, 16<<10, &session) != nil ||
		loadStrict(bindingPath, 1<<10, &link) != nil || keyErr != nil {
		fmt.Fprintln(os.Stderr, "personal local setup unavailable")
		os.Exit(1)
	}
	dsn := os.Getenv("APOLLO_RGA_TEST_PG_DSN")
	cfg, subject, installation, err := prepareConfig(session, link, dsn, roomID, keyHex, certPath, keyPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "personal local setup unavailable")
		os.Exit(1)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if setup(ctx, cfg, subject, installation, outPath) != nil {
		fmt.Fprintln(os.Stderr, "personal local setup unavailable")
		os.Exit(1)
	}
	fmt.Println("personal local test state and private node config prepared")
}
