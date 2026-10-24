# Managed OlcRTC native component — actual linked-module notices

This packet identifies the 91 Go module dependencies recorded in the actual
original arm64 ELF of this AAR, not all go.mod/MVS entries. APK-packaged inventory
verification is a separate acceptance step; no prior APK proof is inherited. The AAR is 12,160,323 bytes with
SHA-256 `2d061e6270af0a8f7cd844416a7f964ab6369057d9f2c02cd3bbe2faac6db7ad`.

There are 90 selected external module versions and one modified local OlcRTC
source replacement. The old embedded-Xray, Apollo Bridge/API and juju/ratelimit
entries are not claimed as dependencies of this managed AAR. This does not
remove the separate ordinary Xray and sing-box components from the APK or alter
their licensing and corresponding-source obligations.

The local OlcRTC source is based on upstream commit
`8d97e32e0b1af0a727d6e785c4c467f37543aa4d`, with Apollo managed-lease,
fresh-admission, transport-health and bounded lifecycle changes. Preserve its
Apache-2.0 LICENSE, NOTICE and the supplied upstream WTFPL provenance text.
Read the complete original licence texts; no generic SPDX label replaces them.

Selected module texts include applicable nested LICENSE, NOTICE, COPYING and
COPYRIGHT material. Each external text was compared byte-for-byte with its exact
selected module archive, whose .ziphash marker matches the ELF-recorded h1 sum.
Stable module IDs are retained from the historical inventory to avoid needless
renaming; gaps in IDs do not imply missing linked modules. Exact versions,
checksums, text hashes and portable relative links are in the inventory JSON.

The Go 1.26.8 runtime/standard library licence is additional to the 91 dep lines.
This packet supplements ordinary APK/distribution, JNI/NDK, fonts and routing
data notices; it does not enumerate all Kotlin or system dependencies. Do not
present it as a licence of the whole APK, a legal-compliance guarantee, binary
reproducibility proof, signed-app release approval or network-runtime evidence.

This is a new sidecar/source-notices packet. Existing signed APK32/APK33 and their
sealed source archives retain their historical 117-module assets unchanged.
Those APK assets are not retroactively corrected by this packet. Integration
into a future candidate/source export and verification of actual delivery remain
separate release gates. No operational endpoints, keys or user credentials are
included.

## Exact linked Go module inventory

| Module | Actual version | Full licence / notice texts |
| --- | --- | --- |
| `buf.build/gen/go/bufbuild/protovalidate/protocolbuffers/go` | `v1.36.11-20260415201107-50325440f8f2.1` | [LICENSE](licenses/m002/c693279643b8-LICENSE) |
| `buf.build/go/protovalidate` | `v1.2.0` | [LICENSE](licenses/m003/c693279643b8-LICENSE) |
| `buf.build/go/protoyaml` | `v0.7.0` | [LICENSE](licenses/m004/c693279643b8-LICENSE) |
| `cel.dev/expr` | `v0.25.2` | [LICENSE](licenses/m005/c693279643b8-LICENSE) |
| `codeberg.org/rape4me/kc` | `v0.0.0-20260527074346-4cb2a45790c2` | [LICENSE](licenses/m006/c693279643b8-LICENSE) |
| `github.com/antlr4-go/antlr/v4` | `v4.13.1` | [LICENSE](licenses/m009/c693279643b8-LICENSE) |
| `github.com/benbjohnson/clock` | `v1.3.5` | [LICENSE](licenses/m011/c693279643b8-LICENSE) |
| `github.com/beorn7/perks` | `v1.0.1` | [LICENSE](licenses/m012/c693279643b8-LICENSE) |
| `github.com/bep/debounce` | `v1.2.1` | [LICENSE](licenses/m013/c693279643b8-LICENSE) |
| `github.com/cespare/xxhash/v2` | `v2.3.0` | [LICENSE.txt](licenses/m014/d0ed4cc3fb70-LICENSE.txt) |
| `github.com/coder/websocket` | `v1.8.14` | [LICENSE.txt](licenses/m016/d0ed4cc3fb70-LICENSE.txt) |
| `github.com/dennwc/iters` | `v1.2.2` | [LICENSE](licenses/m017/c693279643b8-LICENSE) |
| `github.com/frostbyte73/core` | `v0.1.1` | [LICENSE](licenses/m018/c693279643b8-LICENSE) |
| `github.com/fsnotify/fsnotify` | `v1.10.1` | [LICENSE](licenses/m019/c693279643b8-LICENSE) |
| `github.com/gammazero/deque` | `v1.2.1` | [LICENSE](licenses/m020/c693279643b8-LICENSE) |
| `github.com/go-logr/logr` | `v1.4.3` | [LICENSE](licenses/m022/c693279643b8-LICENSE) |
| `github.com/go-logr/stdr` | `v1.2.2` | [LICENSE](licenses/m023/c693279643b8-LICENSE) |
| `github.com/golang-jwt/jwt/v5` | `v5.3.1` | [LICENSE](licenses/m024/c693279643b8-LICENSE) |
| `github.com/google/cel-go` | `v0.28.1` | [LICENSE](licenses/m026/c693279643b8-LICENSE) |
| `github.com/google/uuid` | `v1.6.0` | [LICENSE](licenses/m027/c693279643b8-LICENSE) |
| `github.com/gorilla/websocket` | `v1.5.4-0.20250319132907-e064f32e3674` | [LICENSE](licenses/m028/c693279643b8-LICENSE) |
| `github.com/jxskiss/base62` | `v1.1.0` | [LICENSE](licenses/m030/c693279643b8-LICENSE) |
| `github.com/klauspost/compress` | `v1.18.6` | [gzhttp/LICENSE](licenses/m031/e83775ac87dd-LICENSE), [internal/lz4ref/LICENSE](licenses/m031/09b69489504c-LICENSE), [internal/snapref/LICENSE](licenses/m031/e386007ab893-LICENSE), [LICENSE](licenses/m031/c693279643b8-LICENSE), [s2/cmd/internal/filepathx/LICENSE](licenses/m031/fb4d1f11987c-LICENSE), [s2/cmd/internal/readahead/LICENSE](licenses/m031/a4f1bcb908dc-LICENSE), [s2/LICENSE](licenses/m031/c71dd03289c3-LICENSE), [snappy/LICENSE](licenses/m031/f6cbbf1f0d3d-LICENSE), [snappy/xerial/LICENSE](licenses/m031/3eb709d2f161-LICENSE), [zstd/internal/xxhash/LICENSE.txt](licenses/m031/07d1e1161858-LICENSE.txt) |
| `github.com/klauspost/cpuid/v2` | `v2.3.0` | [LICENSE](licenses/m032/c693279643b8-LICENSE) |
| `github.com/klauspost/reedsolomon` | `v1.14.0` | [LICENSE](licenses/m033/c693279643b8-LICENSE) |
| `github.com/lithammer/shortuuid/v4` | `v4.2.0` | [LICENSE](licenses/m034/c693279643b8-LICENSE) |
| `github.com/livekit/mediatransportutil` | `v0.0.0-20260605212259-862d4a7bcb1e` | [LICENSE](licenses/m035/c693279643b8-LICENSE), [NOTICE](licenses/m035/dfb14fbb9e7d-NOTICE) |
| `github.com/livekit/protocol` | `v1.50.1` | [LICENSE](licenses/m036/c693279643b8-LICENSE), [NOTICE](licenses/m036/dfb14fbb9e7d-NOTICE) |
| `github.com/livekit/psrpc` | `v0.7.2` | [LICENSE](licenses/m037/c693279643b8-LICENSE), [NOTICE](licenses/m037/dfb14fbb9e7d-NOTICE) |
| `github.com/makiuchi-d/gozxing` | `v0.1.1` | [LICENSE](licenses/m038/c693279643b8-LICENSE) |
| `github.com/munnerz/goautoneg` | `v0.0.0-20191010083416-a7dc8b61c822` | [LICENSE](licenses/m040/c693279643b8-LICENSE) |
| `github.com/nats-io/nats.go` | `v1.52.0` | [LICENSE](licenses/m041/c693279643b8-LICENSE) |
| `github.com/nats-io/nkeys` | `v0.4.16` | [LICENSE](licenses/m042/c693279643b8-LICENSE) |
| `github.com/nats-io/nuid` | `v1.0.1` | [LICENSE](licenses/m043/c693279643b8-LICENSE) |
| `github.com/openlibrecommunity/olcrtc` | `v0.0.0-00010101000000-000000000000` → `source/olc` | [LICENSE](licenses/m044/c693279643b8-LICENSE), [NOTICE](licenses/m044/dfb14fbb9e7d-NOTICE), [docs/upstream-license-wtfpl.txt](licenses/m044/e8b8f82059e4-upstream-license-wtfpl.txt) |
| `github.com/owenewans/owenlivekit/v2` | `v2.18.2` | [LICENSE](licenses/m045/c693279643b8-LICENSE), [NOTICE](licenses/m045/dfb14fbb9e7d-NOTICE), [pkg/samplebuilder/NOTICE](licenses/m045/cd17448a9cea-NOTICE) |
| `github.com/pion/datachannel` | `v1.6.0` | [LICENSE](licenses/m047/c693279643b8-LICENSE) |
| `github.com/pion/dtls/v3` | `v3.1.4` | [LICENSE](licenses/m048/c693279643b8-LICENSE) |
| `github.com/pion/ice/v4` | `v4.2.7` | [LICENSE](licenses/m049/c693279643b8-LICENSE) |
| `github.com/pion/interceptor` | `v0.1.45` | [LICENSE](licenses/m050/c693279643b8-LICENSE) |
| `github.com/pion/logging` | `v0.2.4` | [LICENSE](licenses/m051/c693279643b8-LICENSE) |
| `github.com/pion/mdns/v2` | `v2.1.0` | [LICENSE](licenses/m052/c693279643b8-LICENSE) |
| `github.com/pion/randutil` | `v0.1.0` | [LICENSE](licenses/m053/c693279643b8-LICENSE) |
| `github.com/pion/rtcp` | `v1.2.16` | [LICENSE](licenses/m054/c693279643b8-LICENSE) |
| `github.com/pion/rtp` | `v1.10.2` | [LICENSE](licenses/m055/c693279643b8-LICENSE) |
| `github.com/pion/sctp` | `v1.10.0` | [LICENSE](licenses/m056/c693279643b8-LICENSE) |
| `github.com/pion/sdp/v3` | `v3.0.19` | [LICENSE](licenses/m057/c693279643b8-LICENSE) |
| `github.com/pion/srtp/v3` | `v3.0.11` | [LICENSE](licenses/m058/c693279643b8-LICENSE) |
| `github.com/pion/stun/v3` | `v3.1.5` | [LICENSE](licenses/m059/c693279643b8-LICENSE) |
| `github.com/pion/transport/v4` | `v4.0.2` | [LICENSE](licenses/m060/c693279643b8-LICENSE) |
| `github.com/pion/turn/v5` | `v5.0.9` | [LICENSE](licenses/m061/c693279643b8-LICENSE) |
| `github.com/pion/webrtc/v4` | `v4.2.15` | [LICENSE](licenses/m062/c693279643b8-LICENSE), [test-wasm/LICENSE](licenses/m062/2abbe28a045f-LICENSE) |
| `github.com/pkg/errors` | `v0.9.1` | [LICENSE](licenses/m064/c693279643b8-LICENSE) |
| `github.com/prometheus/client_golang` | `v1.23.2` | [internal/github.com/golang/gddo/LICENSE](licenses/m065/c9b6f8dc575d-LICENSE), [LICENSE](licenses/m065/c693279643b8-LICENSE), [NOTICE](licenses/m065/dfb14fbb9e7d-NOTICE) |
| `github.com/prometheus/client_model` | `v0.6.2` | [LICENSE](licenses/m066/c693279643b8-LICENSE), [NOTICE](licenses/m066/dfb14fbb9e7d-NOTICE) |
| `github.com/prometheus/common` | `v0.68.1` | [LICENSE](licenses/m067/c693279643b8-LICENSE), [NOTICE](licenses/m067/dfb14fbb9e7d-NOTICE) |
| `github.com/prometheus/procfs` | `v0.20.1` | [LICENSE](licenses/m068/c693279643b8-LICENSE), [NOTICE](licenses/m068/dfb14fbb9e7d-NOTICE) |
| `github.com/puzpuzpuz/xsync/v4` | `v4.5.0` | [LICENSE](licenses/m069/c693279643b8-LICENSE) |
| `github.com/redis/go-redis/v9` | `v9.20.0` | [LICENSE](licenses/m071/c693279643b8-LICENSE) |
| `github.com/tjfoc/gmsm` | `v1.4.1` | [LICENSE](licenses/m076/c693279643b8-LICENSE) |
| `github.com/twitchtv/twirp` | `v8.1.3+incompatible` | [_tools/src/github.com/kisielk/errcheck/LICENSE](licenses/m077/09754063dd13-LICENSE), [_tools/src/github.com/kisielk/gotool/LICENSE](licenses/m077/f3c6319ffcc6-LICENSE), [_tools/src/github.com/twitchtv/retool/LICENSE](licenses/m077/0850e9e05933-LICENSE), [LICENSE](licenses/m077/c693279643b8-LICENSE), [license_test.go](licenses/m077/8ac8021266ec-license_test.go), [NOTICE](licenses/m077/dfb14fbb9e7d-NOTICE) |
| `github.com/wlynxg/anet` | `v0.0.5` | [LICENSE](licenses/m080/c693279643b8-LICENSE) |
| `github.com/xtaci/kcp-go/v5` | `v5.6.72` | [LICENSE](licenses/m081/c693279643b8-LICENSE) |
| `github.com/xtaci/smux` | `v1.5.57` | [LICENSE](licenses/m082/c693279643b8-LICENSE) |
| `github.com/zarazaex69/gr` | `v0.0.1` | [LICENSE](licenses/m085/c693279643b8-LICENSE) |
| `github.com/zarazaex69/j` | `v0.0.1` | [LICENSE](licenses/m086/c693279643b8-LICENSE) |
| `github.com/zeebo/xxh3` | `v1.1.0` | [LICENSE](licenses/m087/c693279643b8-LICENSE) |
| `go.opentelemetry.io/otel` | `v1.44.0` | [LICENSE](licenses/m089/c693279643b8-LICENSE) |
| `go.uber.org/atomic` | `v1.11.0` | [LICENSE.txt](licenses/m090/d0ed4cc3fb70-LICENSE.txt) |
| `go.uber.org/multierr` | `v1.11.0` | [LICENSE.txt](licenses/m091/d0ed4cc3fb70-LICENSE.txt) |
| `go.uber.org/zap` | `v1.28.0` | [LICENSE](licenses/m092/c693279643b8-LICENSE) |
| `go.uber.org/zap/exp` | `v0.3.0` | [LICENSE](licenses/m093/c693279643b8-LICENSE) |
| `go.yaml.in/yaml/v3` | `v3.0.4` | [LICENSE](licenses/m094/c693279643b8-LICENSE), [NOTICE](licenses/m094/dfb14fbb9e7d-NOTICE) |
| `golang.org/x/crypto` | `v0.53.0` | [LICENSE](licenses/m096/c693279643b8-LICENSE) |
| `golang.org/x/exp` | `v0.0.0-20260603202125-055de637280b` | [LICENSE](licenses/m097/c693279643b8-LICENSE) |
| `golang.org/x/mobile` | `v0.0.0-20260520154334-0e4426e1883d` | [LICENSE](licenses/m098/c693279643b8-LICENSE) |
| `golang.org/x/mod` | `v0.36.0` | [LICENSE](licenses/m099/c693279643b8-LICENSE) |
| `golang.org/x/net` | `v0.55.0` | [LICENSE](licenses/m100/c693279643b8-LICENSE) |
| `golang.org/x/sync` | `v0.21.0` | [LICENSE](licenses/m101/c693279643b8-LICENSE) |
| `golang.org/x/sys` | `v0.46.0` | [LICENSE](licenses/m102/c693279643b8-LICENSE) |
| `golang.org/x/text` | `v0.38.0` | [LICENSE](licenses/m103/c693279643b8-LICENSE) |
| `golang.org/x/time` | `v0.15.0` | [LICENSE](licenses/m104/c693279643b8-LICENSE) |
| `golang.org/x/tools` | `v0.45.0` | [copyright/copyright.go](licenses/m105/a1c6d6b85955-copyright.go), [copyright/copyright_test.go](licenses/m105/af62cd3f3ee9-copyright_test.go), [LICENSE](licenses/m105/c693279643b8-LICENSE) |
| `golang.org/x/xerrors` | `v0.0.0-20240903120638-7835f813f4da` | [LICENSE](licenses/m106/c693279643b8-LICENSE) |
| `google.golang.org/genproto` | `v0.0.0-20260523011958-0a33c5d7ca68` | [LICENSE](licenses/m108/c693279643b8-LICENSE) |
| `google.golang.org/genproto/googleapis/api` | `v0.0.0-20260526163538-3dc84a4a5aaa` | [LICENSE](licenses/m109/c693279643b8-LICENSE) |
| `google.golang.org/genproto/googleapis/rpc` | `v0.0.0-20260526163538-3dc84a4a5aaa` | [LICENSE](licenses/m110/c693279643b8-LICENSE) |
| `google.golang.org/grpc` | `v1.82.1` | [LICENSE](licenses/m111/c693279643b8-LICENSE), [NOTICE.txt](licenses/m111/b810ebe4ffda-NOTICE.txt) |
| `google.golang.org/protobuf` | `v1.36.11` | [LICENSE](licenses/m112/c693279643b8-LICENSE) |
| `gopkg.in/yaml.v3` | `v3.0.1` | [LICENSE](licenses/m114/c693279643b8-LICENSE), [NOTICE](licenses/m114/dfb14fbb9e7d-NOTICE) |
| `rsc.io/qr` | `v0.2.0` | [LICENSE](licenses/m117/c693279643b8-LICENSE) |

Go1.26.8 runtime/standard library: [full licence](licenses/go1.26.8/LICENSE).

Exact actual h1 checksums and per-file SHA/ordinary-byte reuse are in NATIVE-MODULE-LICENSES.json.

Apollo.RGA VK provider backport: upstream 7ac128ac8bc59bd008f116f072c0a2b14c70395f,
with local managed cancellation, resource ownership and privacy corrections.
The exact 90 external module versions/h1 sums and supplied licence texts are
unchanged from the accepted managed91 packet. This is private candidate evidence,
not provider authorisation or production acceptance.

Apollo.RGA MTS Link backport: upstream b6cb4c417d17dfb7b9b780d7c37280df02e5dc6e,
VP9 carrier/explicit ICE-TCP, with local pending-start cancellation/join and
secret-free refusal corrections. Existing Apache-2.0/NOTICE and original WTFPL
provenance are preserved. Exact external versions/h1 sums and licence texts
match the previously verified packet. This is NOT provider or runtime approval.
