package main

import (
	"crypto/tls"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestBootstrapExplicitPrivateForwarderBoundary(t *testing.T) {
	cases := []struct {
		name, host, peer string
		tls              bool
		want             bool
	}{
		{"default-loopback", bootstrapListenAddress, "127.0.0.1:17", true, true},
		{"exact-forwarder", "rga.apollot.ru", "10.0.27.1:17", true, true},
		{"wrong-private-peer", "rga.apollot.ru", "10.0.27.2:17", true, false},
		{"public-direct", "rga.apollot.ru", "198.51.100.9:17", true, false},
		{"wrong-host", "attacker.example", "10.0.27.1:17", true, false},
		{"no-tls", "rga.apollot.ru", "10.0.27.1:17", false, false},
		{"forwarded-spoof", "rga.apollot.ru", "198.51.100.9:17", true, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest(http.MethodPost, "https://"+tc.host+"/v1/personal/bootstrap", nil)
			r.Host = tc.host
			r.RemoteAddr = tc.peer
			r.Header.Set("Content-Type", "application/json")
			r.Header.Set("X-Forwarded-For", "10.0.27.1")
			if tc.tls {
				r.TLS = &tls.ConnectionState{}
			} else {
				r.TLS = nil
			}
			if got := bootstrapRequestBoundaryWithIngress(r, "rga.apollot.ru", "10.0.27.1"); got != tc.want {
				t.Fatalf("allowed=%v want=%v", got, tc.want)
			}
		})
	}
}

func TestBootstrapPublicIngressNeverEnabledImplicitly(t *testing.T) {
	r := httptest.NewRequest(http.MethodPost, "https://rga.apollot.ru/v1/personal/bootstrap", nil)
	r.Host = "rga.apollot.ru"
	r.RemoteAddr = "10.0.27.1:17"
	r.TLS = &tls.ConnectionState{}
	r.Header.Set("Content-Type", "application/json")
	for _, pair := range [][2]string{{"", ""}, {"rga.apollot.ru", ""}, {"", "10.0.27.1"}, {"rga.apollot.ru", "198.51.100.9"}} {
		if bootstrapRequestBoundaryWithIngress(r, pair[0], pair[1]) {
			t.Fatal("unconfigured/untrusted ingress accepted")
		}
	}
	r.URL.RawQuery = "shortUuid=not-a-real-secret"
	if bootstrapRequestBoundaryWithIngress(r, "rga.apollot.ru", "10.0.27.1") {
		t.Fatal("query-bearing bootstrap allowed")
	}
}
