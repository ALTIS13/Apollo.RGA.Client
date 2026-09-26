# Apollo.RGA: существующая подписка Remnawave — план реализации

> Исполнение по этапам с тестом RED→GREEN. Production Remnawave и страницу подписки не менять до отдельной проверки и решения о выпуске.

**Goal:** Android-клиент принимает ту же HTTPS-ссылку Remnawave без новой учётной записи, сохраняет полную семантику XRAY_JSON и представляет обычные хосты в интерфейсе Apollo.RGA. OlcRTC закрыт в обычном выпуске до доказанного общего учёта.

**Architecture:** Строгий декодер XRAY_JSON создаёт атомарный набор хостов с исходными эффективными Xray DNS/routing/outbound-полями. Android VPN запускает Xray с этими данными, если закреплённое ядро принимает также Hy2 с проверкой сертификата; иначе выпуск целой XRAY_JSON-подписки блокируется до доказанного совместимого пути. Policy не подменяется локальным Bypass Russia. UI использует отдельные presenter-модели, а release policy запрещает OlcRTC на уровне импорта и старта VPN. Существующие URI/Base64 остаются совместимыми, но не являются fallback при ошибке XRAY_JSON.

**Tech Stack:** Kotlin Multiplatform, Compose Material 3, Xray/sing-box Android cores, Gradle, kotlin.test, Android instrumented tests.

**Spec:** `docs/superpowers/specs/2026-09-26-apollo-rga-existing-subscription-design.md`

## Global Constraints

- Работать только в `D:/CodexProjects/Apollo.RGA.Client` на `codex/apollo-rga-client-mvp`. Не менять tracked `Apollo.GAP`/GA Panel, LetsCube, Apollo.TF, production Remnawave, DNS, ноды и текущий Happ.
- Не выводить subscription URL, UUID, ключи и конфиги с секретами в test output, logcat, артефакты или git. Использовать редактированные структурно-эквивалентные фикстуры.
- Один буквальный HTTPS URL; никаких новых Apollo.GAP managed endpoints. Не включать OlcRTC в release до серверного доказательства общего срока и трафика. Debug/test путь сохраняется только как явно изолированный.
- XRAY_JSON либо принят целиком с DNS/routing/Reality/XHTTP extra/Hy2 без потери обязательных полей, либо явно отклонён. URI/Base64 не использовать как неявную деградацию. Обновление атомарно; последний валидный профиль не даёт бессрочного доступа после известного expiry/limit.
- Сохранять старые поддерживаемые URI/Base64 и existing Android behavior вне перечисленных границ. UI не обещает неподтверждённый режим, UDP или поддержку iOS. INCY/Happ остаются в прежнем потоке.
- До production-выдачи нужны точный текущий URL response/HWID read-only, подписанная release-сборка, устройство, профиль и реальная проверка маршрутов/сна/переключения сети. Source/эмулятор не равен этому допуску.

## Review Focus

- Не интерпретируется ли неожиданное поле Xray как «успешно импортировано» с другим маршрутом; сохраняются ли DNS и порядок routing rules; не утекают ли секреты в сообщения об ошибках.
- Не запускается ли OlcRTC в release через старый storage, deep link, refresh либо прямой вызов сервиса.
- Не меняет ли refresh выбор хоста и не публикует ли частично разобранный набор; верно ли expiry/limit закрывают stale last-good.
- Не регрессируют ли привычные ссылки, QR, Android VPN permission, системная навигация и крупный шрифт.

### Task 1: Строгий XRAY_JSON codec

**Files:** новый `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/datasource/XrayJsonSubscriptionCodec.kt`, `sharedUI/src/commonTest/kotlin/org/olcbox/app/data/datasource/XrayJsonSubscriptionCodecTest.kt`, редактированные фикстуры рядом с тестом.

**Interfaces:** `decode(body)` returns either all validated Xray-format config objects in original order with display name/protocol and their unchanged effective config, or a structured rejection. Never logs a payload. Integration/storage comes in Task 2.

- [ ] Написать редактированные фикстуры и тесты для Xray-format JSON array: VLESS/XHTTP/Reality + `protocol:hysteria` v2/TLS, разный DNS/routing, сохранение `extra` и `pinnedPeerCertSha256`, порядок правил, неверный transport/обязательное поле, одна плохая запись среди хороших, ошибочный JSON. Тест на неизвестный transport должен реально падать на текущем коде.
- [ ] Запустить `:sharedUI:jvmTest` с фильтром теста и зафиксировать ожидаемый RED, затем реализовать минимальный codec, повторить GREEN.
- [ ] Проверить приватный snapshot только структурно и вне вывода; любые новые поля покрытия занести как redacted fixture. Не копировать live payload в git.
- [ ] Прогнать связанные codec tests, `git diff --check`, проверить отсутствие секретов в diff. Commit: `feat(rga): decode strict Xray subscription profiles`.

### Task 2: Импорт, хранение и атомарное обновление

**Files:** `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/datasource/LocationsDatasource.kt`, `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/LocationConfig.kt`, `sharedUI/src/androidMain/kotlin/org/olcbox/app/data/datasource/LocationsDataSourceImpl.kt`, `sharedUI/build.gradle.kts` (`GeneratedAppInfo.NAME`), `sharedUI/src/commonTest/kotlin/org/olcbox/app/data/datasource/LocationsRepositoryImplTest.kt` and targeted Android storage tests.

**Interfaces:** Task 1 codec is consumed by `LocationsRepositoryImpl` as an atomic subscription unit. Persisted location carries its validated original config and stable identity. Own User-Agent is `ApolloRGA/<version>` (exact Response Rule later); a rejected Identity request never retries as browser UA to bypass HWID/format, and the literal URL is retained for XRAY_JSON. URI/Base64 behavior remains where independently valid.

- [ ] RED tests: bad entry/format drift/Identity rejection cannot publish a subset or invoke Compatibility UA; valid refresh keeps selected host; known expired/limited profile does not become unlimited after failed refresh.
- [ ] Integrate codec, serialize exact profile, make Android write crash-safe, preserve last valid version only while entitlement state permits. GREEN and related repository/storage tests.
- [ ] Check focused diff for URL/key logging, run `git diff --check`. Commit: `feat(rga): store Xray subscriptions atomically`.

### Task 3: Обновлённое Android-ядро для Hy2 и XHTTP

**Files:** `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/XrayConfig.kt` (pin), `.github/workflows/pr-checks.yml`, `.github/workflows/release.yml`, `.github/workflows/singbox-verify.yml`, core packaging script/version declarations, tests and ignored build artifacts. No bundled binary in git unless that is the existing repo policy.

**Interfaces:** Replace Android Xray 25.3.6, which cannot parse Hysteria v2/TLS, with a pinned, reproducible and patched Xray version at least 26.7.11. Preserve XHTTP functionality. Keep iOS/desktop pins unchanged unless their own compatibility requires a separate explicit task.

- [ ] Select the exact upstream tag after reading its official release/security notes; record tag, source commit and artifact hash. 26.7.11 is the minimum for `pinnedPeerCertSha256` security fix ([advisory](https://github.com/XTLS/Xray-core/security/advisories/GHSA-5wf9-h793-w73c)); newer prereleases require focused testing, not an assumption of stability.
- [ ] Treat config acceptance separately from network usability: [upstream report](https://github.com/XTLS/Xray-core/issues/6717) describes Xray Hy2 on a Russian network authenticating yet carrying no usable traffic while sing-box worked. Only an actual device/network test may close that risk; do not infer success from `xray test`.
- [ ] First RED: pinned Android 25.3.6 rejects sanitized Hy2/TLS-pin configuration. Build/stage the replacement arm64 binary with existing project pipeline; run `xray test` on that exact build in isolated Android execution and verify XHTTP/Reality too. A Windows x64 binary passing is insufficient.
- [ ] Update matching CI/release pins, 16 KB ELF alignment check and app packaging tests; run focused build/verification. Do not install on the user's Nothing Phone yet or alter an active VPN. Commit: `build(rga): pin Android Xray with Hy2 support`.

### Task 4: Запуск точного Xray-профиля

**Files:** новый `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/XrayJsonRuntimeConfig.kt`, `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt`, `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/XrayJsonRuntimeConfigTest.kt`, targeted Android tests.

**Interfaces:** Preserve input DNS/routing/outbounds byte/semantic content and adapt only loopback SOCKS inbound/tag/auth for Android TUN→hev/mapdns→SOCKS. Start through actual `OlcboxVpnService.startCore`, not unused `EngineDispatcher`. No local Bypass Russia substitution.

- [ ] RED tests: exact DNS/routing/outbound preservation, tag graph validation, a malformed inbound and a changed XHTTP/Hy2 field cause failure.
- [ ] Reuse Task 3 evidence that the packaged pinned Android core accepts sanitized XHTTP and Hy2/TLS-pin fixtures. If real Hy2 traffic remains unusable, release stays blocked; do not route via lossy sing-box converter.
- [ ] Implement runtime adapter/Android branch, GREEN, focused Xray validation, and no secret logging. Physical RU DIRECT/foreign egress/DNS/IPv4/IPv6 remains a release gate. Commit: `feat(rga): run validated Xray subscription profiles`.

### Task 5: Release-hide OlcRTC и понятная модель подписки/хоста

**Files:** variant policy в `androidApp`/`sharedUI`, `LocationsDatasource.kt`, `OlcboxVpnService.kt`, новые presenter-модели в `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/`, tests under `sharedUI/src/commonTest`.

**Interfaces:** Release denies OlcRTC on import, restore, refresh and VPN start, while isolated debug/test path stays explicit. Host model puts destination flag/country first and relay/protocol second. Subscription model distinguishes active, expiring, expired, limited, unknown and refresh-failed without treating UI headers as server admission proof.

- [ ] RED tests for composite flag (`🇳🇱`), long host name + comment, storage-injected OlcRTC in release, expired/limited status, missing usage headers and failed refresh.
- [ ] Implement policy/presenters; GREEN + related tests; check no URL/UUID in UI output or logs. Commit: `feat(rga): gate experimental mode and present subscription status`.

### Task 6: Apollo Orbit Android UI

**Files:** `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/features/onboarding/OnboardingScreen.kt`, `.../features/home/HomeScreen.kt`, `HomeScreenContent.kt`, `.../components/LocationSelection.kt`, theme/colors/typography, Android activity resources; tests for presenter/composables as applicable.

**Interfaces:** Home exposes one connect/disconnect action and subscription status; ordinary host cards show destination flag/country, `через RU Relay`, protocol and purpose. Release has no OlcRTC copy/entry. QR/paste uses the existing literal URL.

- [ ] Перед UI-правкой прочитать `impeccable/reference/craft-floor.md` и выполнить требуемый `concept-seed` для новой визуальной среды; записать direction contract в кратком UI brief, не использовать экран пользователя без нужды.
- [ ] Написать/обновить RED presenter/UI tests для host/status/onboarding/hidden mode. Реализовать Material 3 Apollo Orbit screen, нативные back/insets/touch targets; GREEN.
- [ ] Android build + screenshot/visual review на контролируемой среде; проверить крупный шрифт и длинные названия. Commit: `feat(rga): present Apollo subscription home`.

### Task 7: Импорт из страницы и выпускной барьер

**Files:** `androidApp/src/main/AndroidManifest.xml`, Android import handler/test, release notes, `docs/` handoff; production subpage/Response Rules не менять.

**Interfaces:** QR/paste work first. Narrow owned HTTPS App Link is implemented only when signed release + verified `assetlinks.json` can be tested; subscription token never appears in HTTP path/query logs. If verification is not possible, one-tap remains unreleased and documented as such.

- [ ] RED tests for URL validation, secret-free diagnostics and malformed/untrusted import intent. Implement minimal safe handler; GREEN.
- [ ] Verify Android build/tests, run targeted physical-device acceptance if phone and network are available without interrupting user connectivity. Explicitly record unverified items (exact live Response Rule/HWID, DIRECT/foreign egress, sleep/network switch, verified App Link/signature).
- [ ] Fresh whole-branch review, focused diff and secret scan. Do not deploy, change production page or remove INCY/Happ. Commit handoff docs only after verification.
