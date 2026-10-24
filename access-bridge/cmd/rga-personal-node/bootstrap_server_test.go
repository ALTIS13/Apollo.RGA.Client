package main

import (
	"context"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestBootstrapTLSKeyRequiresOwnerOnlyRegularFileOnLinux(t *testing.T) {
	tests := []struct {
		mode os.FileMode
		goos string
		want bool
	}{
		{0400, "linux", true}, {0600, "linux", true},
		{0640, "linux", false}, {0604, "linux", false},
		{0100, "linux", false}, {os.ModeDir | 0700, "linux", false},
		{0644, "windows", true},
	}
	for _, tc := range tests {
		if got := bootstrapTLSKeyModeOK(tc.mode, tc.goos); got != tc.want {
			t.Fatalf("mode %v on %s: got %v, want %v", tc.mode, tc.goos, got, tc.want)
		}
	}
}

func TestBootstrapTLSPreflightErrorDoesNotEchoPaths(t *testing.T) {
	secretPath := filepath.Join(t.TempDir(), "secret-private-key.pem")
	_, _, err := startBootstrapServer("private-cert-file.pem", secretPath, nil)
	if !errors.Is(err, errBootstrapServer) || strings.Contains(err.Error(), secretPath) || strings.Contains(err.Error(), "private-cert") {
		t.Fatal("TLS preflight did not return generic failure")
	}
}

func TestBootstrapPrivateListenerServesOnlyTLSOnConfiguredAddress(t *testing.T) {
	fixture := httptest.NewTLSServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer fixture.Close()
	dir := t.TempDir()
	certPath, keyPath := filepath.Join(dir, "cert.pem"), filepath.Join(dir, "key.pem")
	cert := fixture.TLS.Certificates[0]
	// The fixture key is in-memory test material, never deployment input.
	keyDER, err := x509.MarshalPKCS8PrivateKey(cert.PrivateKey)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert.Certificate[0]}), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER}), 0600); err != nil {
		t.Fatal(err)
	}
	server, done, err := startBootstrapServerOnAddress("127.0.0.1:0", certPath, keyPath, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.TLS == nil {
			t.Error("bootstrap handler reached without TLS")
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	if err != nil {
		t.Fatal(err)
	}
	response, err := fixture.Client().Get("https://" + server.Addr + "/")
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusNoContent {
		t.Fatal("configured TLS listener did not serve handler")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := server.Shutdown(ctx); err != nil {
		t.Fatal(err)
	}
	if err := <-done; !errors.Is(err, http.ErrServerClosed) {
		t.Fatalf("private listener did not shut down cleanly: %v", err)
	}
}
