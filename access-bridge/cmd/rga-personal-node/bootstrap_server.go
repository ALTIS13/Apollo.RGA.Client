package main

import (
	"crypto/tls"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"runtime"
	"time"
)

const bootstrapListenAddress = "127.0.0.1:18443"

var errBootstrapServer = errors.New("personal node: bootstrap server failed")

// The certificate is public; only the private key needs owner-only file
// permissions on Linux. Neither path nor a TLS/parser error reaches the log.
func bootstrapTLSKeyModeOK(mode os.FileMode, goos string) bool {
	return mode.IsRegular() && (goos != "linux" || mode.Perm()&0400 != 0 && mode.Perm()&0077 == 0)
}

func startBootstrapServer(certPath, keyPath string, handler http.Handler) (*http.Server, <-chan error, error) {
	return startBootstrapServerOnAddress(bootstrapListenAddress, certPath, keyPath, handler)
}

func startBootstrapServerOnAddress(address, certPath, keyPath string, handler http.Handler) (*http.Server, <-chan error, error) {
	keyInfo, err := os.Stat(keyPath)
	if err != nil || !bootstrapTLSKeyModeOK(keyInfo.Mode(), runtime.GOOS) {
		return nil, nil, errBootstrapServer
	}
	cert, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, nil, errBootstrapServer
	}
	// Address comes only from validated server-owned configuration, never Host.
	// The default remains USB loopback; a container listener must be unpublished.
	listener, err := net.Listen("tcp4", address)
	if err != nil {
		return nil, nil, errBootstrapServer
	}
	server := &http.Server{
		Addr:    listener.Addr().String(),
		Handler: handler, ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout: 10 * time.Second, WriteTimeout: 15 * time.Second,
		IdleTimeout: 30 * time.Second, MaxHeaderBytes: 4 << 10,
		ErrorLog: log.New(io.Discard, "", 0),
	}
	secured := tls.NewListener(listener, &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12})
	done := make(chan error, 1)
	go func() { done <- server.Serve(secured) }()
	return server, done, nil
}
