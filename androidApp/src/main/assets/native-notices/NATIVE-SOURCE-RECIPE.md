# Current managed AAR source and build recipe

Target: Android API 23, arm64-v8a, 16 KiB ELF page alignment.
The actual AAR SHA-256 is
`2d061e6270af0a8f7cd844416a7f964ab6369057d9f2c02cd3bbe2faac6db7ad`.

The corresponding source is the 567-file qualified OlcRTC tree under
`source/olc` in the native corresponding-source archive. It is based on upstream
commit `8d97e32e0b1af0a727d6e785c4c467f37543aa4d` with applied managed admission,
lease, lifecycle and health changes. Keep LICENSE, NOTICE and
`docs/upstream-license-wtfpl.txt`, existing attribution and applicable modified
source notices. The actual original ELF dependency inventory contains
90 external modules and this one local source replacement. An external h1 sum
is not invented for the local replacement.

Toolchain: Go 1.26.8, x/mobile gomobile/gobind
`v0.0.0-20260520154334-0e4426e1883d`, Android NDK `28.2.13676358`.

```sh
cd source/olc
GOWORK=off gomobile bind -target=android/arm64 -androidapi=23 -trimpath \
  '-ldflags=-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384' \
  -o olcrtc-managed-v2.aar github.com/openlibrecommunity/olcrtc/mobile
```

Rebuild generated Java/JNI bindings using gomobile; do not copy a historical
binding or embed the former Apollo Bridge/API/Xray multi-module tree. Ordinary
APK Xray and sing-box corresponding-source archives remain separate and intact.
The portable `source/olc` path replaces a private local build path in metadata;
it is not a claim of byte-identical binary reproducibility.

This packet contains notice texts and metadata, not an AAR/APK/native executable,
signing key, subscription material, server configuration or deployment action.

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
