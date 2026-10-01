# Apollo.RGA: собственные обновления личной Android-сборки

Дата: 2026-10-01. Следующий этап подтверждённого личного теста; решения подготовлены
по поручению владельца продолжать самостоятельно. Это проект следующего этапа,
не сообщение о работающем OTA и не разрешение общего выпуска.

## Цель и неизменные границы

Обновлять `tech.gatealtas.rga.personal` с собственного сервера без удаления данных,
повторного импорта подписки и новой учётной записи. Android остаётся ответственным
за окончательное подтверждение установки: бесшумную установку не обещаем.
Ни подпись манифеста, ни скачанная APK не предоставляют OlcRTC-допуск. Bridge
по-прежнему допускает только привязанную установку, со сроком и отзывом подписки.
Общие подписчики, обычный release-пакет, Remnawave, DNS, RU/зарубежные ноды и
исключённые проекты не меняются.

Принятый физический baseline: personal APK versionCode=1, versionName=1.0.0,
сертификат SHA-256
`11cfb810016a9f39d75cc517e9f3a8c731e6d0b4e9156e5872c6138cadd3a017`.
Рабочая проверка OlcRTC и обновления подписки принята на A063; рабочая APK пока
обновляется через ADB. Фактические SHA артефактов выпускаются из immutable
снимка кандидата, а не копируются из текста этого документа.

## Выбранный подход

Новый personal-only updater вместо включения `store_self_update` у legacy GitHub
загрузчика. Первый вариант: одна новая main APK, прежний проверенный companion
сервис. Альтернатива с двумя устанавливаемыми APK требует согласованного
package lifecycle и не нужна для обновлений, не меняющих companion IPC.
Play/store updater и новый обычный аккаунт не вводятся.

Манифест подписывается отдельным постоянным EC P-256 ключом (`SHA256withECDSA`),
публичный SPKI закреплён в personal-сборке. Закрытый ключ не находится в APK,
исходниках, served-каталоге или Home-контейнере. До активации: зашифрованная
независимая резервная копия ключа и проверенное восстановление; доступ к паролю
восстановления хранится отдельно от носителя и не выводится в чат. При отсутствии
копии можно продолжать source-тесты, но не объявлять OTA готовым.
Та же готовность обязательна для существующего APK signing private key:
независимая зашифрованная копия и проверка восстановления с точным baseline
сертификатом `11cfb810...a017`. Проверка полного fingerprint обязательна;
сокращение здесь только для чтения. Новый manifest key не заменяет APK signer.
Не менять сертификат и не удалять приложение ради обновления.

## Точный формат v1

Фиксированный origin `https://rga.apollot.ru`, manifest path
`/updates/personal/v1/manifest.json`. Только HTTPS GET, без redirect, Cookie или
subscription bearer. Ответ не более 8192 байт. Канонический envelope без пробелов:
`{"payload":"<base64url-no-padding>","signature":"<base64url-no-padding>"}`.
Ровно два поля в данном порядке, неизвестные/дублированные поля запрещены.
Подпись проверяется над decoded payload, до интерпретации полей. Payload — ASCII,
не более 4096 байт, следующие 17 строк в данном порядке, с LF после каждой:

```text
APOLLO-RGA-PERSONAL-OTA/1
sequence=<positive canonical decimal>
issuedAt=<Unix seconds>
expiresAt=<Unix seconds>
package=tech.gatealtas.rga.personal
versionCode=<canonical decimal>
versionName=<1..64 ASCII characters from A-Za-z0-9.+_->
minSdk=<canonical decimal>
abi=arm64-v8a
apkPath=/updates/personal/v1/<versionCode>/apollo-rga-personal.apk
apkBytes=<canonical decimal>
apkSha256=<64 lowercase hex>
apkCertSha256=<64 lowercase hex>
companionPackage=tech.gatealtas.rga.personal.test
companionVersionCode=<canonical decimal>
companionSha256=<64 lowercase hex>
companionCertSha256=<64 lowercase hex>
```

`versionCode` строго выше установленного. Рабочая v1 не содержит нового updater
или SPKI pin: сначала один раз устанавливаем bootstrap APK v2 через ADB тем же
сертификатом без удаления данных; первый настоящий OTA-кандидат — v3.
`minSdk` не ниже
23 и не выше SDK устройства; ABI должен присутствовать на устройстве. APK size
1..268435456 байт. `expiresAt > now`, `issuedAt <= now + 300`, срок манифеста не
более 604800 секунд: `0 < expiresAt - issuedAt <= 604800`.
Сертификаты main/companion должны совпадать с сохранённым
installed baseline, а не только между собой. Companion package/version/SHA
проверяются локально; если companion изменился или его IPC несовместим, OTA v1
отказывается, применяется проверенное ручное обновление пары через ADB.

Durable high-water: `(sequence, payloadSha256)`. Меньшая sequence запрещена;
равная допустима только с тем же payload hash. Новый high-water сохраняется
атомарно после полной проверки подписи/контракта. Непроверенный manifest никогда
не замещает последний проверенный. Истёкший кэш не разрешает установку.

## Получение и установка

При обычной доступной сети используется обычный HTTPS. При активном personal
OlcRTC — выбранный SOCKS с повторными runtime/session guard; ни Checking, ни
смена сессии не дают nullable/default fallback. Это отдельный update-purpose
ticket с exact manifest/APK URL, не расширение разрешённых URL subscription fence.
Домен RGA уже принудительно tunnelled. Пользователь видит отмену из-за смены
подключения отдельно от DNS/HTTP timeout. Автоматическая проверка: не чаще раза
в 6 часов при foreground; ручная проверка отдельная, без бесконечных повторов.

APK скачивается в собственный temporary cache с exact size и SHA-256; затем
Android PackageManager проверяет package, versionCode, signer и SDK/ABI против
verified manifest и installed baseline. Только после этого immutable файл
передаётся системному PackageInstaller через FileProvider. Нельзя повторно
использовать файл, изменившийся после проверки. Запрос install permission и
диалог установки показываются только по действию владельца. Перед установкой
сообщаем, что Android перезапустит VPN; не устанавливаем незаметно в фоне.
Повреждённый APK/manifest, лишний origin, rollback, mismatch или потеря сессии
оставляют нынешнюю рабочую сборку и подписку нетронутыми.

Разрешение `REQUEST_INSTALL_PACKAGES` добавляется только personal debug manifest;
обычные/Play варианты его не получают. Проверенный кандидат и source snapshot
сохраняются приватно для восстановления. Возврат на прежний код выполняется
новой подписанной APK с более высоким versionCode, не обходом anti-downgrade.

## Публикация и доказательства

Сначала verifier и real-crypto negative tests, затем Android installer gate и
selected-path download, потом изолированный served path только RGA на HomeNode.
Кандидат публикуется immutable, manifest переключается атомарно последним.
APK содержит no subscription/grant/room/keys; перед публикацией отдельный scan.
Не размещать personal APK на общей странице подписки и не рекламировать её
обычным пользователям. Публичная загрузка артефакта не заменяет server admission.

Финальное доказательство: после ручного bootstrap v2 A063 получает версию 3
системным установщиком, сохраняет
подписку/выбор/Keystore и после перезапуска подключается; rejected candidate не
запускает installer. Source-only проверки не заменяют это доказательство.

Источники: [подпись APK](https://developer.android.com/studio/publish/app-signing),
[versionCode и защита от downgrade](https://developer.android.com/studio/publish/versioning),
[PackageManager и install permission](https://developer.android.com/reference/android/content/pm/PackageManager),
[PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller).
