# Source-only .NET publisher -> production Kotlin/JCA verifier interoperability.
# Requires source-only main >=3 and synthetic prior >=2, NOT live A063; never deploys.
[CmdletBinding()]
param([Parameter(Mandatory)][string]$MainApk, [Parameter(Mandatory)][string]$CompanionApk)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PersonalUpdatePublisher.psm1') -Force
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixture = Join-Path $root ('.ops-private/personal-ota/tests/' + [Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($fixture)
$buildTools = 'D:/Progi/AndroidStudio-sdk/build-tools/36.0.0'
$main = Get-PersonalApkMetadata $MainApk $buildTools
$companion = Get-PersonalApkMetadata $CompanionApk $buildTools -Companion
if ($main.PackageName -cne 'tech.gatealtas.rga.personal' -or
    $companion.PackageName -cne 'tech.gatealtas.rga.personal.test') { throw 'Fixture package extraction failed' }
Assert-PersonalUpdateBootstrapBoundary $main.VersionCode 2
$baseline = @(
    'packageName=tech.gatealtas.rga.personal', ('versionCode=' + $main.VersionCode),
    ('apkCertSha256=' + $main.ApkCertSha256), 'sdk=35', 'supportedAbis=arm64-v8a',
    'companionPackage=tech.gatealtas.rga.personal.test', ('companionVersionCode=' + $companion.VersionCode),
    ('companionSha256=' + $companion.ApkSha256), ('companionCertSha256=' + $companion.ApkCertSha256),
    'highWaterSequence=0', 'highWaterPayloadSha256='
)
$baselinePath = Join-Path $fixture 'baseline.properties'
[IO.File]::WriteAllLines($baselinePath,$baseline,[Text.UTF8Encoding]::new($false))
$password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$passwordPath = Join-Path $fixture 'test-password.txt'
[IO.File]::WriteAllText($passwordPath,$password,[Text.UTF8Encoding]::new($false))
$keyPath = Join-Path $fixture 'test-key.encrypted-pkcs8'
$key = [Security.Cryptography.ECDsa]::Create([Security.Cryptography.ECCurve+NamedCurves]::nistP256)
try {
    $pbe = [Security.Cryptography.PbeParameters]::new([Security.Cryptography.PbeEncryptionAlgorithm]::Aes256Cbc,
        [Security.Cryptography.HashAlgorithmName]::SHA256, 200000)
    [IO.File]::WriteAllBytes($keyPath,$key.ExportEncryptedPkcs8PrivateKey($password,$pbe))
} finally { $key.Dispose(); $password = $null }
$args = @{
    CandidateApk=$MainApk; CompanionApk=$CompanionApk; BaselineProperties=$baselinePath
    EncryptedManifestKey=$keyPath; ManifestPasswordFile=$passwordPath; Sequence=7L
    StageDirectory=(Join-Path $fixture 'staged'); AndroidBuildTools=$buildTools
}
$target = Join-Path $fixture 'junction-target'
[void][IO.Directory]::CreateDirectory($target)
$link = Join-Path $fixture 'junction'
New-Item -ItemType Junction -Path $link -Target $target | Out-Null
$linkedArgs = $args.Clone(); $linkedArgs.StageDirectory = Join-Path $link 'must-not-create'
$linkedRejected = $false
try { & (Join-Path $PSScriptRoot 'publish-personal-update.ps1') @linkedArgs | Out-Null }
catch { if ($_.Exception.Message -cne 'OTA_STAGE_LINKED') { throw }; $linkedRejected = $true }
if (!$linkedRejected -or (Test-Path -LiteralPath (Join-Path $target 'must-not-create'))) { throw 'Linked stage created side effects before refusal' }
$rejected=$false
try { & (Join-Path $PSScriptRoot 'publish-personal-update.ps1') @args | Out-Null }
catch { if ($_.Exception.Message -cne 'OTA_CANDIDATE_BASELINE_MISMATCH') { throw }; $rejected=$true }
if (!$rejected -or (Test-Path -LiteralPath (Join-Path $args.StageDirectory 'manifest.json'))) { throw 'Downgrade published unexpectedly' }
[IO.File]::WriteAllLines($baselinePath,($baseline | ForEach-Object {
    if ($_ -match '^versionCode=') { 'versionCode=1' } else { $_ }
}),[Text.UTF8Encoding]::new($false))
$prebootstrapRejected = $false
try { & (Join-Path $PSScriptRoot 'publish-personal-update.ps1') @args | Out-Null }
catch { if ($_.Exception.Message -cne 'OTA_BOOTSTRAP_BOUNDARY') { throw }; $prebootstrapRejected = $true }
if (!$prebootstrapRejected -or (Test-Path -LiteralPath (Join-Path $args.StageDirectory 'manifest.json'))) { throw 'Prebootstrap baseline published unexpectedly' }
[IO.File]::WriteAllLines($baselinePath,($baseline | ForEach-Object {
    if ($_ -match '^versionCode=') { 'versionCode=' + ($main.VersionCode - 1) } else { $_ }
}),[Text.UTF8Encoding]::new($false))
& (Join-Path $PSScriptRoot 'publish-personal-update.ps1') @args | Out-Null
$published = Join-Path $args.StageDirectory ([string]$main.VersionCode + '/apollo-rga-personal.apk')
if ((Get-FileHash -LiteralPath $published -Algorithm SHA256).Hash.ToLowerInvariant() -cne $main.ApkSha256 -or
    !(Test-Path -LiteralPath (Join-Path $args.StageDirectory 'manifest.json'))) { throw 'Verified staging failed' }
$manifest = Join-Path $args.StageDirectory 'manifest.json'
$before = [Convert]::ToBase64String([IO.File]::ReadAllBytes($manifest))
foreach ($sequence in @(6L,7L)) {
    $retryArgs = $args.Clone(); $retryArgs.Sequence = $sequence
    $refused = $false
    try { & (Join-Path $PSScriptRoot 'publish-personal-update.ps1') @retryArgs | Out-Null }
    catch { if ($_.Exception.Message -cne 'OTA_SEQUENCE_NOT_ADVANCED') { throw }; $refused = $true }
    if (!$refused -or [Convert]::ToBase64String([IO.File]::ReadAllBytes($manifest)) -cne $before) { throw 'Monotonic publication refusal did not preserve manifest' }
}
Write-Output 'Publisher stage-link + downgrade + prebootstrap + .NET/JCA roundtrip + lower/equal sequence: 6 passed; source-only main >=3 and synthetic prior >=2'
