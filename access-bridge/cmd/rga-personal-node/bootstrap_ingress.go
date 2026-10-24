package main

import (
	"mime"
	"net"
	"net/http"
	"net/netip"
)

func bootstrapRequestBoundaryWithIngress(r *http.Request, publicHost, forwarder string) bool {
	if r.TLS == nil || r.Method != http.MethodPost || r.URL.RawQuery != "" ||
		r.URL.ForceQuery || r.URL.EscapedPath() != r.URL.Path {
		return false
	}
	contentType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || contentType != "application/json" {
		return false
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	peer, parseErr := netip.ParseAddr(host)
	if err != nil || parseErr != nil {
		return false
	}
	if r.Host == bootstrapListenAddress && peer.IsLoopback() {
		return true
	}
	trusted, err := netip.ParseAddr(forwarder)
	return publicHost == "rga.apollot.ru" && r.Host == publicHost && err == nil &&
		trusted.Is4() && trusted.IsPrivate() && trusted == peer
}
