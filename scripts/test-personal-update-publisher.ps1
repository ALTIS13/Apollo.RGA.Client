# Real crypto regression tests for the publisher boundary. No permanent keys.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PersonalUpdatePublisher.psm1') -Force
$cases = 0
$failures = 0
function Check([bool]$ok, [string]$label) {
    if (!$ok) { $script:failures++; Write-Output "FAIL: $label" }
    $script:cases++
}
function Reject([scriptblock]$action, [string]$label) {
    $rejected = $false
    try { & $action | Out-Null } catch { $rejected = $true }
    Check $rejected $label
}
function RejectCode([scriptblock]$action, [string]$code, [string]$label) {
    $actual = ''
    try { & $action | Out-Null } catch { $actual = $_.Exception.Message }
    Check ($actual -ceq $code) $label
}
$metadata = @{
    PackageName = 'tech.gatealtas.rga.personal'; VersionCode = 3L; VersionName = '1.0.3'
    MinSdk = 23; Abi = 'arm64-v8a'; ApkBytes = 1024L; ApkSha256 = ('1' * 64)
    ApkCertSha256 = ('2' * 64); CompanionPackage = 'tech.gatealtas.rga.personal.test'
    CompanionVersionCode = 1L; CompanionSha256 = ('3' * 64); CompanionCertSha256 = ('2' * 64)
}
$payload = New-PersonalUpdatePayload -Metadata $metadata -Sequence 7 -IssuedAt 1900000000 -ExpiresAt 1900003600
$expected = @(
    'APOLLO-RGA-PERSONAL-OTA/1', 'sequence=7', 'issuedAt=1900000000', 'expiresAt=1900003600',
    'package=tech.gatealtas.rga.personal', 'versionCode=3', 'versionName=1.0.3', 'minSdk=23',
    'abi=arm64-v8a', 'apkPath=/updates/personal/v1/3/apollo-rga-personal.apk', 'apkBytes=1024',
    ('apkSha256=' + ('1' * 64)), ('apkCertSha256=' + ('2' * 64)),
    'companionPackage=tech.gatealtas.rga.personal.test', 'companionVersionCode=1',
    ('companionSha256=' + ('3' * 64)), ('companionCertSha256=' + ('2' * 64))
) -join "`n"
Check ($payload -ceq ($expected + "`n")) 'canonical interoperable payload'
$zeroCompanion = $metadata.Clone(); $zeroCompanion.CompanionVersionCode = 0L
$zeroAccepted = $false
try { $zeroPayload = New-PersonalUpdatePayload $zeroCompanion 7 1900000000 1900003600; $zeroAccepted = $zeroPayload.Contains("companionVersionCode=0`n") } catch { }
Check $zeroAccepted 'actual frozen instrumentation companion version zero accepted'
$key = [Security.Cryptography.ECDsa]::Create([Security.Cryptography.ECCurve+NamedCurves]::nistP256)
try {
    $json = New-SignedPersonalUpdateEnvelope -Payload $payload -Key $key
    Check ($json -cmatch '^\{"payload":"[A-Za-z0-9_-]+","signature":"[A-Za-z0-9_-]+"\}$') 'exact envelope'
    $obj = $json | ConvertFrom-Json
    function Decode([string]$s) {
        $s = $s.Replace('-', '+').Replace('_', '/')
        return [Convert]::FromBase64String($s.PadRight($s.Length + ((4 - $s.Length % 4) % 4), '='))
    }
    $decoded = Decode $obj.payload
    Check ([Text.Encoding]::ASCII.GetString($decoded) -ceq $expected + "`n") 'signed bytes are canonical payload'
    $sig = Decode $obj.signature
    Check ($key.VerifyData($decoded, $sig, [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)) 'DER ECDSA signature validates'
    $decoded[0] = 0
    Check (!$key.VerifyData($decoded, $sig, [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)) 'tampered payload denied'
    $wrongKey = [Security.Cryptography.ECDsa]::Create([Security.Cryptography.ECCurve+NamedCurves]::nistP256)
    try { Check (!$wrongKey.VerifyData([Text.Encoding]::ASCII.GetBytes($payload), $sig,
        [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)) 'wrong key denied' } finally { $wrongKey.Dispose() }

    # Real filesystem + transient real crypto. Removing advance/signature/schema/lock
    # enforcement must fail these behavioral checks; no private fixtures are printed.
    $root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    $fixture = Join-Path $root ('.ops-private/personal-ota/tests/' + [Guid]::NewGuid().ToString('N'))
    [void][IO.Directory]::CreateDirectory($fixture)
    $apk = Join-Path $fixture 'candidate.apk'
    [IO.File]::WriteAllBytes($apk, [byte[]](1,2,3,4))
    $stage = Join-Path $fixture 'staged'
    $stageMeta = $metadata.Clone()
    $stageMeta.ApkBytes = 4L
    $stageMeta.ApkSha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    $stagePayload = New-PersonalUpdatePayload $stageMeta 7 1900000000 1900003600
    $stageEnvelope = New-SignedPersonalUpdateEnvelope $stagePayload $key
    $fresh = $false
    try { Publish-PersonalUpdateStage $stage $apk $stageEnvelope $key 2 | Out-Null; $fresh = $true } catch { }
    Check $fresh 'fresh signed state staged'
    if ($fresh) {
        $manifest = Join-Path $stage 'manifest.json'
        $before = [Convert]::ToBase64String([IO.File]::ReadAllBytes($manifest))
        foreach ($sequence in @(6L,7L)) {
            $retry = New-SignedPersonalUpdateEnvelope (New-PersonalUpdatePayload $stageMeta $sequence 1900000001 1900003601) $key
            RejectCode { Publish-PersonalUpdateStage $stage $apk $retry $key 2 } 'OTA_SEQUENCE_NOT_ADVANCED' 'lower/equal sequence refused'
            Check ([Convert]::ToBase64String([IO.File]::ReadAllBytes($manifest)) -ceq $before) 'refusal preserves exact manifest bytes'
        }
        $next = New-SignedPersonalUpdateEnvelope (New-PersonalUpdatePayload $stageMeta 8 1900000001 1900003601) $key
        $badStates = @('invalid', (' ' + $stageEnvelope), ($stageEnvelope + "`n"))
        $badStates += New-SignedPersonalUpdateEnvelope ($stagePayload.Replace('sequence=7','sequence=07')) $key
        $badStates += New-SignedPersonalUpdateEnvelope ($stagePayload.Replace('apkPath=/updates/personal/v1/3/','apkPath=/other/3/')) $key
        $other = [Security.Cryptography.ECDsa]::Create([Security.Cryptography.ECCurve+NamedCurves]::nistP256)
        try { $badStates += New-SignedPersonalUpdateEnvelope $stagePayload $other } finally { $other.Dispose() }
        foreach ($badState in $badStates) {
            [IO.File]::WriteAllText($manifest,$badState,[Text.UTF8Encoding]::new($false))
            RejectCode { Publish-PersonalUpdateStage $stage $apk $next $key 2 } 'OTA_PRIOR_STATE_INVALID' 'corrupt/noncanonical/wrong-key prior state refused'
            Check ([IO.File]::ReadAllText($manifest) -ceq $badState) 'invalid prior state preserved exactly'
        }
        [IO.File]::WriteAllText($manifest,$stageEnvelope,[Text.UTF8Encoding]::new($false))
        $lock = Enter-PersonalUpdateStageLock $stage
        try {
            # A different PowerShell process competes for the same production lock.
            $job = Start-Job -ScriptBlock {
                param($module,$directory)
                Import-Module $module -Force
                try { $handle = Enter-PersonalUpdateStageLock $directory; $handle.Dispose(); 'ACQUIRED' }
                catch { $_.Exception.Message }
            } -ArgumentList (Join-Path $PSScriptRoot 'PersonalUpdatePublisher.psm1'),$stage
            $lockResult = $job | Wait-Job | Receive-Job
            Remove-Job $job
            Check ($lockResult -ceq 'OTA_STAGE_BUSY') 'concurrent process refused owned stage lock'
            RejectCode { Publish-PersonalUpdateStage $stage $apk $next $key 2 } 'OTA_STAGE_BUSY' 'concurrent publication refused before mutation'
            Check ([IO.File]::ReadAllText($manifest) -ceq $stageEnvelope) 'busy stage preserves manifest'
        } finally { $lock.Dispose() }
        RejectCode { Publish-PersonalUpdateStage $stage $apk $next $key 1 } 'OTA_BOOTSTRAP_BOUNDARY' 'stage API cannot bypass manual bootstrap baseline'
        Publish-PersonalUpdateStage $stage $apk $next $key 2 | Out-Null
        Check ([IO.File]::ReadAllText($manifest) -ceq $next) 'strict advance succeeds after lock released'
        Check ((Get-FileHash -LiteralPath (Join-Path $stage '3/apollo-rga-personal.apk')).Hash.ToLowerInvariant() -ceq $stageMeta.ApkSha256) 'immutable APK survives manifest advance'

        # Invoke the operator entry point too. Dummy APKs deliberately cannot pass
        # aapt/JCA; these refusals must happen before tools, scratch or APK writes.
        $passwordPath = Join-Path $fixture 'test-password.txt'
        $keyPath = Join-Path $fixture 'test-key.encrypted-pkcs8'
        $password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        [IO.File]::WriteAllText($passwordPath,$password,[Text.UTF8Encoding]::new($false))
        $pbe = [Security.Cryptography.PbeParameters]::new([Security.Cryptography.PbeEncryptionAlgorithm]::Aes256Cbc,
            [Security.Cryptography.HashAlgorithmName]::SHA256,10000)
        [IO.File]::WriteAllBytes($keyPath,$key.ExportEncryptedPkcs8PrivateKey($password,$pbe))
        $password = $null
        $cert = '11cfb810016a9f39d75cc517e9f3a8c731e6d0b4e9156e5872c6138cadd3a017'
        $baselinePath = Join-Path $fixture 'baseline.properties'
        $baseline = @('packageName=tech.gatealtas.rga.personal','versionCode=2',('apkCertSha256=' + $cert),
            ('companionCertSha256=' + $cert))
        [IO.File]::WriteAllLines($baselinePath,$baseline,[Text.UTF8Encoding]::new($false))
        $cliArgs = @{
            CandidateApk=$apk; CompanionApk=$apk; BaselineProperties=$baselinePath
            EncryptedManifestKey=$keyPath; ManifestPasswordFile=$passwordPath
            Sequence=8L; StageDirectory=$stage
        }
        $filesBefore = @(Get-ChildItem -LiteralPath $stage -Recurse -File | ForEach-Object { $_.FullName + ':' + (Get-FileHash -LiteralPath $_.FullName).Hash }) -join "`n"
        foreach ($sequence in @(7L,8L)) {
            $cliArgs.Sequence = $sequence
            RejectCode { & (Join-Path $PSScriptRoot 'publish-personal-update.ps1') @cliArgs } 'OTA_SEQUENCE_NOT_ADVANCED' 'operator CLI refuses lower/equal before tools or scratch'
            $filesAfter = @(Get-ChildItem -LiteralPath $stage -Recurse -File | ForEach-Object { $_.FullName + ':' + (Get-FileHash -LiteralPath $_.FullName).Hash }) -join "`n"
            Check ($filesAfter -ceq $filesBefore) 'operator refusal preserves all existing stage files'
        }
        $cliArgs.Sequence = 9L
        [IO.File]::WriteAllText($manifest,'invalid',[Text.UTF8Encoding]::new($false))
        RejectCode { & (Join-Path $PSScriptRoot 'publish-personal-update.ps1') @cliArgs } 'OTA_PRIOR_STATE_INVALID' 'operator CLI refuses corrupt prior before tools'
        Check ([IO.File]::ReadAllText($manifest) -ceq 'invalid') 'operator CLI preserves corrupt prior'
        [IO.File]::WriteAllText($manifest,$next,[Text.UTF8Encoding]::new($false))
        $lock = Enter-PersonalUpdateStageLock $stage
        try {
            RejectCode { & (Join-Path $PSScriptRoot 'publish-personal-update.ps1') @cliArgs } 'OTA_STAGE_BUSY' 'operator CLI respects held stage lock'
        } finally { $lock.Dispose() }
        [IO.File]::WriteAllLines($baselinePath,($baseline | ForEach-Object { if ($_ -ceq 'versionCode=2') { 'versionCode=1' } else { $_ } }),[Text.UTF8Encoding]::new($false))
        RejectCode { & (Join-Path $PSScriptRoot 'publish-personal-update.ps1') @cliArgs } 'OTA_BOOTSTRAP_BOUNDARY' 'operator CLI refuses prebootstrap baseline before tools'
    }
} finally { $key.Dispose() }
foreach ($field in @('PackageName','VersionName','Abi','ApkSha256','CompanionPackage','CompanionCertSha256')) {
    $bad = $metadata.Clone(); $bad[$field] = if ($field -eq 'VersionName') { "invalid`n" } else { 'invalid' }
    Reject { New-PersonalUpdatePayload $bad 7 1900000000 1900003600 } "invalid $field"
}
foreach ($bounds in @(@(0L,1900000000L,1900003600L), @(7L,1900000000L,1900000000L),
    @(7L,1900000000L,1900604801L), @(7L,-1L,1900003600L))) {
    Reject { New-PersonalUpdatePayload $metadata $bounds[0] $bounds[1] $bounds[2] } 'invalid time or sequence'
}
foreach ($size in @(0L, 268435457L)) {
    $bad = $metadata.Clone(); $bad.ApkBytes = $size
    Reject { New-PersonalUpdatePayload $bad 7 1900000000 1900003600 } 'invalid APK size'
}
foreach ($version in @(0L, 2147483648L)) {
    $bad = $metadata.Clone(); $bad.VersionCode = $version
    Reject { New-PersonalUpdatePayload $bad 7 1900000000 1900003600 } 'invalid version'
}
$bootstrapCandidate = $metadata.Clone(); $bootstrapCandidate.VersionCode = 2L
RejectCode { New-PersonalUpdatePayload $bootstrapCandidate 7 1900000000 1900003600 } 'OTA_BOOTSTRAP_BOUNDARY' 'manual v2 is not an OTA candidate'
foreach ($baselineVersion in @(0L,1L)) {
    RejectCode { Assert-PersonalUpdateBootstrapBoundary 3 $baselineVersion } 'OTA_BOOTSTRAP_BOUNDARY' 'prebootstrap baseline refused'
}
try { Assert-PersonalUpdateBootstrapBoundary 3 2; Check $true 'manual v2 to OTA v3 accepted' }
catch { Check $false 'manual v2 to OTA v3 accepted' }
$curve384 = [Security.Cryptography.ECDsa]::Create([Security.Cryptography.ECCurve+NamedCurves]::nistP384)
try { Reject { New-SignedPersonalUpdateEnvelope $payload $curve384 } 'wrong signing curve' } finally { $curve384.Dispose() }
Write-Output "Publisher boundary: $($cases - $failures) passed; $failures failed; $cases checks"
if ($failures -ne 0) { throw 'OTA_PUBLISHER_TEST_FAILURE' }
