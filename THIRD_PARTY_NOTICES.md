# Third-party notices

This checkout adapts Ghostlane (itself an olcbox fork) for Apollo.RGA Android.
The upstream copyright notices remain in the repository's MIT `LICENSE`.
Third-party components retain their own licences. The table describes the
multi-platform codebase; the Apollo.RGA Android candidate has a narrower
packaged set, detailed below. This is an engine-level inventory, not a complete
list of transitive Gradle or Go dependencies.

## What ships inside the applications

| Component | Licence | How it is used |
| --- | --- | --- |
| [olcRTC](https://github.com/ghostlane-project/olcrtc) | Apache-2.0 | The Ghostlane tunnel engine; absent from the ordinary Apollo.RGA Android release APK. |
| [sing-box](https://github.com/SagerNet/sing-box) | **GPL-3.0-or-later**, with an additional name/association term in its [pinned licence](https://github.com/SagerNet/sing-box/blob/25a600db24f7680ad9806ce5427bd0ab8afe1114/LICENSE) | Reality, TLS and Hysteria2. Packaged as an executable native core in Apollo.RGA Android. |
| [Xray-core](https://github.com/XTLS/Xray-core) | **[MPL-2.0](https://github.com/XTLS/Xray-core/blob/52a412d9e2f5c2a5142b1b4e2ab3771dacb8b120/LICENSE)** | XHTTP. Packaged as an executable native core in Apollo.RGA Android; other targets also use [libXray](https://github.com/XTLS/libXray) (MIT). |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | [MIT](androidApp/src/main/jni/hev-socks5-tunnel/LICENSE) | TUN-to-SOCKS5 bridge on Android and Apple platforms. |
| [Wintun](https://www.wintun.net/) | GPL-2.0 source; separate [prebuilt DLL terms](androidApp/src/main/jni/hev-socks5-tunnel/third-part/wintun/LICENSE.txt) | The Windows-only TUN driver; not in the Android APK. |
| [Re:filter](https://github.com/1andrevich/Re-filter-lists) | MIT, Copyright (c) 2024 Andrevich | The list of sites blocked in Russia behind "only blocked sites through the tunnel": its sing-box rule-sets, bundled unmodified in `sharedUI/src/commonMain/composeResources/files/rules/` and pinned in `scripts/rule-sets.lock`. |

## Apollo.RGA Android arm64 candidate source

The [candidate workflow](.github/workflows/apollo-rga-android.yml) builds
`githubRelease` for arm64 and uploads an APK plus release evidence. It does not
publish a release or attach this notice to the APK. The core source revisions
for that workflow are:

| Packaged component | Corresponding source |
| --- | --- |
| `libsingboxcore.so` | [sing-box commit `25a600db24f7680ad9806ce5427bd0ab8afe1114`](https://github.com/SagerNet/sing-box/tree/25a600db24f7680ad9806ce5427bd0ab8afe1114), checked by `scripts/apollo-rga-release-pins.json` |
| `libxraycore.so` | [Xray-core commit `52a412d9e2f5c2a5142b1b4e2ab3771dacb8b120`](https://github.com/XTLS/Xray-core/tree/52a412d9e2f5c2a5142b1b4e2ab3771dacb8b120), checked by `scripts/android-xray-pins.json` |
| Android hev-socks5-tunnel | [Vendored source](androidApp/src/main/jni/hev-socks5-tunnel/) in the exact Apollo.RGA application source commit recorded by the APK's `apollo-rga-release-evidence.json` |
| lwIP inside the Android hev build | [Vendored BSD-3-Clause licence and source](androidApp/src/main/jni/hev-socks5-tunnel/third-part/lwip/) |
| hev task system and YAML inside the Android hev build | Vendored MIT [task-system licence](androidApp/src/main/jni/hev-socks5-tunnel/third-part/hev-task-system/LICENSE) and [YAML licence](androidApp/src/main/jni/hev-socks5-tunnel/third-part/yaml/License) |
| Re:filter rule-sets | [Bundled files](sharedUI/src/commonMain/composeResources/files/rules/) and hashes in `scripts/rule-sets.lock`, at that same application source commit |

The Android Xray builder uses the clean upstream commit above. The
[Xray patch](scripts/patches/xray-core-h2-window.patch) is applied by the iOS
framework builder, not by the Apollo.RGA Android candidate workflow. Likewise,
the [hev commit `941c758101385d145c66210ac88991daaf27d4b6`](https://github.com/heiher/hev-socks5-tunnel/tree/941c758101385d145c66210ac88991daaf27d4b6)
in `scripts/hev-pins.sh` pins the Apple framework; it is not a revision claim
for the vendored Android source.

## Before distributing an Apollo.RGA APK

The APK evidence names its exact `source_commit`. Make that application source
revision, this notice, the repository `LICENSE`, and the component licence
texts available with the distributed APK. Verify that the source revision
contains the vendored Android hev code and any patches actually used by that
APK. The candidate workflow alone is not a source publication step.

This inventory identifies components and their stated licences. It is not a
legal determination about the licence of a combined work or app-store terms;
those questions need separate review before distribution.
