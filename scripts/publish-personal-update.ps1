# Stages immutable PUBLIC artifacts privately; does not deploy or contact a server.
# PowerShell 7+, JBR, Android build-tools and production Kotlin verifier are required.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CandidateApk,
    [Parameter(Mandatory)][string]$CompanionApk,
    [Parameter(Mandatory)][string]$BaselineProperties,
    [Parameter(Mandatory)][string]$EncryptedManifestKey,
    [Parameter(Mandatory)][string]$ManifestPasswordFile,
    [Parameter(Mandatory)][long]$Sequence,
    [string]$AndroidBuildTools = 'D:/Progi/AndroidStudio-sdk/build-tools/36.0.0',
    [int]$LifetimeSeconds = 86400,
    [string]$StageDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PersonalUpdatePublisher.psm1') -Force
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$privateRoot = [IO.Path]::GetFullPath((Join-Path $root '.ops-private/personal-ota'))
if (!$StageDirectory) { $StageDirectory = Join-Path $privateRoot 'staged' }
$stage = [IO.Path]::GetFullPath($StageDirectory)
$prefix = $privateRoot.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
if (!$stage.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'OTA_STAGE_NOT_PRIVATE' }
if ($LifetimeSeconds -lt 1 -or $LifetimeSeconds -gt 604800 -or $Sequence -lt 1) { throw 'OTA_PUBLISH_BOUNDS' }
foreach ($path in @($CandidateApk,$CompanionApk,$BaselineProperties,$EncryptedManifestKey,$ManifestPasswordFile)) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf) -or
        ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'OTA_INPUT_NOT_REGULAR' }
}
# Baseline is operator-captured PUBLIC metadata, never subscription/account data.
$baseline = @{}
foreach ($line in [IO.File]::ReadAllLines($BaselineProperties)) {
    if ($line -notmatch '^([A-Za-z][A-Za-z0-9]*)=(.*)$' -or $baseline.ContainsKey($Matches[1])) { throw 'OTA_BASELINE_SHAPE' }
    $baseline[$Matches[1]] = $Matches[2]
}
$baselineCert = '11cfb810016a9f39d75cc517e9f3a8c731e6d0b4e9156e5872c6138cadd3a017'
if ($baseline.packageName -cne 'tech.gatealtas.rga.personal' -or
    $baseline.apkCertSha256 -cne $baselineCert -or $baseline.companionCertSha256 -cne $baselineCert) { throw 'OTA_BASELINE_SIGNER' }
# Validate ancestors and bootstrap baseline before any stage creation.
Assert-PersonalUpdateUnlinkedDirectory $stage
Assert-PersonalUpdateBootstrapBoundary 3 ([long]$baseline.versionCode)
$key = $null
$stageLock = $null
try {
    # Keep the same owned lock throughout prior validation, candidate verification
    # and manifest switch. Competing publishers fail closed without touching state.
    $stageLock = Enter-PersonalUpdateStageLock $stage
    $key = [Security.Cryptography.ECDsa]::Create()
    $password = [IO.File]::ReadAllText($ManifestPasswordFile).TrimEnd("`r","`n")
    $read = 0
    $key.ImportEncryptedPkcs8PrivateKey($password, [IO.File]::ReadAllBytes($EncryptedManifestKey), [ref]$read)
    $password = $null
    if ($read -ne (Get-Item -LiteralPath $EncryptedManifestKey).Length) { throw 'OTA_KEY_TRAILING_DATA' }
    Assert-PersonalUpdateStageAdvance $stage $Sequence $key
    $scratch = Join-Path $stage ('.candidate-' + [Guid]::NewGuid().ToString('N'))
    [void][IO.Directory]::CreateDirectory($scratch)
    # Freeze inputs before metadata/signing; all external tools inspect this exact copy.
    $frozenMain = Join-Path $scratch 'apollo-rga-personal.apk'
    $frozenCompanion = Join-Path $scratch 'companion.apk'
    [IO.File]::Copy([IO.Path]::GetFullPath($CandidateApk), $frozenMain, $false)
    [IO.File]::Copy([IO.Path]::GetFullPath($CompanionApk), $frozenCompanion, $false)
    $meta = Get-PersonalApkMetadata $frozenMain $AndroidBuildTools
    $companion = Get-PersonalApkMetadata $frozenCompanion $AndroidBuildTools -Companion
    Assert-PersonalUpdateBootstrapBoundary $meta.VersionCode ([long]$baseline.versionCode)
    if ($meta.PackageName -cne $baseline.packageName -or $meta.ApkCertSha256 -cne $baseline.apkCertSha256 -or
        $meta.VersionCode -le [long]$baseline.versionCode -or $meta.MinSdk -gt [int]$baseline.sdk -or
        $companion.PackageName -cne $baseline.companionPackage -or
        $companion.VersionCode -ne [long]$baseline.companionVersionCode -or
        $companion.ApkSha256 -cne $baseline.companionSha256 -or
        $companion.ApkCertSha256 -cne $baseline.companionCertSha256) { throw 'OTA_CANDIDATE_BASELINE_MISMATCH' }
    $meta.CompanionPackage = $companion.PackageName
    $meta.CompanionVersionCode = $companion.VersionCode
    $meta.CompanionSha256 = $companion.ApkSha256
    $meta.CompanionCertSha256 = $companion.ApkCertSha256
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $payload = New-PersonalUpdatePayload $meta $Sequence $now ($now + $LifetimeSeconds)
    $envelope = New-SignedPersonalUpdateEnvelope $payload $key
    [IO.File]::WriteAllText((Join-Path $scratch 'manifest.json'), $envelope, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllBytes((Join-Path $scratch 'manifest-public-spki.der'), $key.ExportSubjectPublicKeyInfo())
    $fixtureBaseline = @($baseline.Keys | Sort-Object | ForEach-Object { $_ + '=' + $baseline[$_] })
    $fixtureBaseline += 'nowEpochSeconds=' + $now.ToString([Globalization.CultureInfo]::InvariantCulture)
    [IO.File]::WriteAllLines((Join-Path $scratch 'baseline.properties'), $fixtureBaseline, [Text.UTF8Encoding]::new($false))
    # Exact production verifier, real JCA signature: cannot bypass with a boolean switch.
    $oldFixture = $env:RGA_PERSONAL_OTA_FIXTURE_DIR
    $env:RGA_PERSONAL_OTA_FIXTURE_DIR = $scratch
    try {
        Push-Location $root
        try {
            & ./gradlew.bat :sharedUI:testAndroidHostTest --tests 'org.olcbox.app.update.personal.PersonalUpdatePublisherRoundtripTest.publisherFixturePassesTheProductionVerifier' '-PrgaPersonalTest=true' '-PrgaPersonalBridgeRemoteOrigin=https://rga.apollot.ru' --rerun *> (Join-Path $scratch 'verifier.log')
            if ($LASTEXITCODE -ne 0) { throw 'OTA_PRODUCTION_VERIFIER_FAILED' }
        } finally { Pop-Location }
    } finally { $env:RGA_PERSONAL_OTA_FIXTURE_DIR = $oldFixture }
    $resultPath = Join-Path $root 'sharedUI/build/test-results/testAndroidHostTest/TEST-org.olcbox.app.update.personal.PersonalUpdatePublisherRoundtripTest.xml'
    [xml]$result = [IO.File]::ReadAllText($resultPath)
    if ($result.testsuite.tests -ne '1' -or $result.testsuite.skipped -ne '0' -or
        $result.testsuite.failures -ne '0' -or $result.testsuite.errors -ne '0') { throw 'OTA_PRODUCTION_VERIFIER_NOT_EXECUTED' }
    Publish-PersonalUpdateStage $stage $frozenMain $envelope $key ([long]$baseline.versionCode) $stageLock
    Write-Output ('OTA_PRIVATE_STAGE_READY versionCode=' + $meta.VersionCode)
} finally {
    if ($key) { $key.Dispose() }
    if ($stageLock) { $stageLock.Dispose() }
    # Keep the bounded scratch receipt privately for inspection; no recursive deletion.
}
