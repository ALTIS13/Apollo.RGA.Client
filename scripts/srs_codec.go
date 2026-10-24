// Reuses the exact shipped sing-box SRS codec; no network or daemon.
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"go4.org/netipx"
	"net/netip"
	"os"
	"reflect"
)

func set(values []string) (*netipx.IPSet, error) {
	var b netipx.IPSetBuilder
	for _, v := range values {
		p, e := netip.ParsePrefix(v)
		if e != nil {
			return nil, e
		}
		b.AddPrefix(p)
	}
	return b.IPSet()
}
func run() error {
	if len(os.Args) != 3 {
		return fmt.Errorf("EXACT_INPUT_OUTPUT_REQUIRED")
	}
	raw, e := os.ReadFile(os.Args[1])
	if e != nil {
		return e
	}
	var input struct {
		Version int `json:"version"`
		Rules   []struct {
			IPCIDR []string `json:"ip_cidr"`
		} `json:"rules"`
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	d.DisallowUnknownFields()
	if e = d.Decode(&input); e != nil {
		return e
	}
	if input.Version != 1 || len(input.Rules) != 1 || len(input.Rules[0].IPCIDR) == 0 {
		return fmt.Errorf("EXACT_COUNTRY_RULE_REQUIRED")
	}
	values := input.Rules[0].IPCIDR
	want, e := set(values)
	if e != nil {
		return e
	}
	rule := option.PlainRuleSet{Rules: []option.HeadlessRule{{Type: C.RuleTypeDefault, DefaultOptions: option.DefaultHeadlessRule{IPCIDR: values}}}}
	var binary bytes.Buffer
	if e = srs.Write(&binary, rule, 1); e != nil {
		return e
	}
	back, e := srs.Read(bytes.NewReader(binary.Bytes()), true)
	if e != nil {
		return e
	}
	if back.Version != 1 || len(back.Options.Rules) != 1 {
		return fmt.Errorf("ROUNDTRIP_SCOPE")
	}
	got, e := set(back.Options.Rules[0].DefaultOptions.IPCIDR)
	if e != nil {
		return e
	}
	if !reflect.DeepEqual(want.Prefixes(), got.Prefixes()) {
		return fmt.Errorf("ROUNDTRIP_COVERAGE_CHANGED")
	}
	f, e := os.OpenFile(os.Args[2], os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if e != nil {
		return e
	}
	if _, e = f.Write(binary.Bytes()); e != nil {
		f.Close()
		return e
	}
	if e = f.Close(); e != nil {
		return e
	}
	fmt.Printf("{\"srsVersion\":1,\"inputPrefixes\":%d,\"decodedPrefixes\":%d,\"binaryBytes\":%d,\"exactIPSetRoundtrip\":true}\n", len(values), len(got.Prefixes()), binary.Len())
	return nil
}
func main() {
	if e := run(); e != nil {
		fmt.Fprintln(os.Stderr, "SRS_CODEC_DENIED", e)
		os.Exit(1)
	}
}
