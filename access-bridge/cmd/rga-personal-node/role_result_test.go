package main

import "testing"

func TestHomeBootstrapFailureCannotBecomeCleanExit(t *testing.T) {
	failure := make(chan struct{}, 1)
	failure <- struct{}{}
	if nodeRunResult(nil, failure) == nil {
		t.Fatal("bootstrap crash hidden by normal control shutdown")
	}
	if nodeRunResult(nil, make(chan struct{}, 1)) != nil {
		t.Fatal("normal shutdown misreported")
	}
}
