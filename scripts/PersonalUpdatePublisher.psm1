Set-StrictMode -Version Latest
function New-PersonalUpdatePayload {
    param([hashtable]$Metadata, [long]$Sequence, [long]$IssuedAt, [long]$ExpiresAt)
    $required = @('PackageName','VersionCode','VersionName','MinSdk','Abi','ApkBytes',
        'ApkSha256','ApkCertSha256','CompanionPackage','CompanionVersionCode',
        'CompanionSha256','CompanionCertSha256')
    if (!$Metadata -or $Metadata.Count -ne $required.Count -or
        @($required | Where-Object { !$Metadata.ContainsKey($_) }).Count -ne 0) {
        throw 'OTA_METADATA_SHAPE'
    }
    if ($Sequence -le 0 -or $IssuedAt -lt 0 -or $ExpiresAt -le $IssuedAt -or
        $ExpiresAt - $IssuedAt -gt 604800) { throw 'OTA_TIME_BOUNDS' }
    if ($Metadata.PackageName -cne 'tech.gatealtas.rga.personal' -or
        $Metadata.CompanionPackage -cne 'tech.gatealtas.rga.personal.test' -or
        $Metadata.Abi -cne 'arm64-v8a' -or
        [string]$Metadata.VersionName -cnotmatch '\A[A-Za-z0-9.+_-]{1,64}\z') { throw 'OTA_METADATA_CONTRACT' }
    if ([long]$Metadata.VersionCode -lt 3) { throw 'OTA_BOOTSTRAP_BOUNDARY' }
    if ([long]$Metadata.VersionCode -gt 2147483647 -or
        [long]$Metadata.CompanionVersionCode -lt 0 -or [long]$Metadata.CompanionVersionCode -gt 2147483647) { throw 'OTA_VERSION_BOUNDS' }
    if ([long]$Metadata.MinSdk -lt 23 -or [long]$Metadata.MinSdk -gt 2147483647 -or
        [long]$Metadata.ApkBytes -lt 1 -or [long]$Metadata.ApkBytes -gt 268435456) { throw 'OTA_APK_BOUNDS' }
    foreach ($field in @('ApkSha256','ApkCertSha256','CompanionSha256','CompanionCertSha256')) {
        if ([string]$Metadata[$field] -cnotmatch '\A[0-9a-f]{64}\z') { throw 'OTA_HASH_SHAPE' }
    }
    $ci = [Globalization.CultureInfo]::InvariantCulture
    $lines = @('APOLLO-RGA-PERSONAL-OTA/1',
        ('sequence=' + $Sequence.ToString($ci)), ('issuedAt=' + $IssuedAt.ToString($ci)),
        ('expiresAt=' + $ExpiresAt.ToString($ci)), 'package=tech.gatealtas.rga.personal',
        ('versionCode=' + ([long]$Metadata.VersionCode).ToString($ci)), ('versionName=' + $Metadata.VersionName),
        ('minSdk=' + ([long]$Metadata.MinSdk).ToString($ci)), 'abi=arm64-v8a',
        ('apkPath=/updates/personal/v1/' + ([long]$Metadata.VersionCode).ToString($ci) + '/apollo-rga-personal.apk'),
        ('apkBytes=' + ([long]$Metadata.ApkBytes).ToString($ci)), ('apkSha256=' + $Metadata.ApkSha256),
        ('apkCertSha256=' + $Metadata.ApkCertSha256), 'companionPackage=tech.gatealtas.rga.personal.test',
        ('companionVersionCode=' + ([long]$Metadata.CompanionVersionCode).ToString($ci)),
        ('companionSha256=' + $Metadata.CompanionSha256), ('companionCertSha256=' + $Metadata.CompanionCertSha256))
    return ($lines -join "`n") + "`n"
}
function Assert-PersonalUpdateBootstrapBoundary {
    param([long]$CandidateVersionCode, [long]$BaselineVersionCode)
    if ($CandidateVersionCode -lt 3 -or $BaselineVersionCode -lt 2) { throw 'OTA_BOOTSTRAP_BOUNDARY' }
}
function Assert-PersonalUpdateUnlinkedDirectory {
    param([string]$Path)
    $ancestor = [IO.DirectoryInfo]::new([IO.Path]::GetFullPath($Path))
    while ($ancestor) {
        if ([IO.File]::Exists($ancestor.FullName)) { throw 'OTA_STAGE_NOT_DIRECTORY' }
        if ($ancestor.Exists -and ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'OTA_STAGE_LINKED' }
        $ancestor = $ancestor.Parent
    }
}
function Enter-PersonalUpdateStageLock {
    param([string]$StageDirectory)
    Assert-PersonalUpdateUnlinkedDirectory $StageDirectory
    [void][IO.Directory]::CreateDirectory($StageDirectory)
    $path = Join-Path $StageDirectory '.publish.lock'
    if ((Test-Path -LiteralPath $path) -and
        ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'OTA_STAGE_LINKED' }
    # Leave this zero-content lock file in place: deleting it would let another
    # process lock a replacement file while the original handle is still owned.
    try { return [IO.File]::Open($path,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
    catch { throw 'OTA_STAGE_BUSY' }
}
function Get-VerifiedPersonalUpdateState {
    param([byte[]]$EnvelopeBytes, [Security.Cryptography.ECDsa]$Key)
    if (!$Key -or $Key.ExportParameters($false).Curve.Oid.Value -ne '1.2.840.10045.3.1.7') { throw 'OTA_SIGNING_CURVE' }
    if (!$EnvelopeBytes -or $EnvelopeBytes.Length -gt 8192 -or @($EnvelopeBytes | Where-Object { $_ -gt 127 }).Count) { throw 'OTA_ENVELOPE_SHAPE' }
    $envelope = [Text.Encoding]::ASCII.GetString($EnvelopeBytes)
    if ($envelope -cnotmatch '\A\{"payload":"([A-Za-z0-9_-]+)","signature":"([A-Za-z0-9_-]+)"\}\z') { throw 'OTA_ENVELOPE_SHAPE' }
    $encodedPayload = $Matches[1]; $encodedSignature = $Matches[2]
    $decoded = @()
    foreach ($encoded in @($encodedPayload,$encodedSignature)) {
        $base64 = $encoded.Replace('-','+').Replace('_','/')
        $bytes = [Convert]::FromBase64String($base64.PadRight($base64.Length + ((4 - $base64.Length % 4) % 4),'='))
        if ([Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_') -cne $encoded) { throw 'OTA_BASE64_SHAPE' }
        $decoded += ,$bytes
    }
    [byte[]]$payloadBytes = $decoded[0]; [byte[]]$signature = $decoded[1]
    if ($payloadBytes.Length -gt 4096 -or !$Key.VerifyData($payloadBytes,$signature,
        [Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)) { throw 'OTA_SIGNATURE_INVALID' }
    if (@($payloadBytes | Where-Object { $_ -gt 127 }).Count) { throw 'OTA_PAYLOAD_SHAPE' }
    $payload = [Text.Encoding]::ASCII.GetString($payloadBytes)
    $lines = $payload.Split("`n")
    if ($lines.Count -ne 18 -or $lines[0] -cne 'APOLLO-RGA-PERSONAL-OTA/1' -or $lines[17] -cne '') { throw 'OTA_PAYLOAD_SHAPE' }
    $fields = @('sequence','issuedAt','expiresAt','package','versionCode','versionName','minSdk','abi',
        'apkPath','apkBytes','apkSha256','apkCertSha256','companionPackage','companionVersionCode','companionSha256','companionCertSha256')
    $values = @{}
    for ($i = 0; $i -lt $fields.Count; $i++) {
        $prefix = $fields[$i] + '='
        if (!$lines[$i+1].StartsWith($prefix,[StringComparison]::Ordinal)) { throw 'OTA_PAYLOAD_SHAPE' }
        $values[$fields[$i]] = $lines[$i+1].Substring($prefix.Length)
    }
    foreach ($field in @('sequence','issuedAt','expiresAt','versionCode','minSdk','apkBytes','companionVersionCode')) {
        [long]$number = 0
        if ($values[$field] -cnotmatch '\A(?:0|[1-9][0-9]*)\z' -or
            ![long]::TryParse($values[$field],[Globalization.NumberStyles]::None,[Globalization.CultureInfo]::InvariantCulture,[ref]$number)) { throw 'OTA_DECIMAL_SHAPE' }
        $values[$field] = $number
    }
    $metadata = @{
        PackageName=$values.package; VersionCode=$values.versionCode; VersionName=$values.versionName
        MinSdk=$values.minSdk; Abi=$values.abi; ApkBytes=$values.apkBytes
        ApkSha256=$values.apkSha256; ApkCertSha256=$values.apkCertSha256
        CompanionPackage=$values.companionPackage; CompanionVersionCode=$values.companionVersionCode
        CompanionSha256=$values.companionSha256; CompanionCertSha256=$values.companionCertSha256
    }
    $canonical = New-PersonalUpdatePayload $metadata $values.sequence $values.issuedAt $values.expiresAt
    if ($payload -cne $canonical) { throw 'OTA_PAYLOAD_SHAPE' }
    return @{
        Sequence=$values.sequence; Metadata=$metadata
        PayloadSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($payloadBytes)).ToLowerInvariant()
    }
}
function Assert-PersonalUpdateStageAdvance {
    param([string]$StageDirectory, [long]$Sequence, [Security.Cryptography.ECDsa]$Key)
    $manifest = Join-Path $StageDirectory 'manifest.json'
    if (!(Test-Path -LiteralPath $manifest)) { return }
    try {
        $file = Get-Item -LiteralPath $manifest
        if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $file.Length -gt 8192) { throw 'invalid' }
        $prior = Get-VerifiedPersonalUpdateState ([IO.File]::ReadAllBytes($manifest)) $Key
    } catch { throw 'OTA_PRIOR_STATE_INVALID' }
    if ($Sequence -le $prior.Sequence) { throw 'OTA_SEQUENCE_NOT_ADVANCED' }
}
function Publish-PersonalUpdateStage {
    param([string]$StageDirectory, [string]$FrozenApk, [string]$Envelope,
        [Security.Cryptography.ECDsa]$Key, [Parameter(Mandatory)][long]$BaselineVersionCode,
        [IO.FileStream]$StageLock)
    Assert-PersonalUpdateBootstrapBoundary 3 $BaselineVersionCode
    $owned = !$StageLock
    if ($owned) { $StageLock = Enter-PersonalUpdateStageLock $StageDirectory }
    try {
        $expectedLock = [IO.Path]::GetFullPath((Join-Path $StageDirectory '.publish.lock'))
        if (!$StageLock.CanRead -or !$StageLock.CanWrite -or $StageLock.Name -cne $expectedLock) { throw 'OTA_STAGE_LOCK_INVALID' }
        $state = Get-VerifiedPersonalUpdateState ([Text.Encoding]::UTF8.GetBytes($Envelope)) $Key
        Assert-PersonalUpdateStageAdvance $StageDirectory $state.Sequence $Key
        $meta = $state.Metadata
        if ((Get-Item -LiteralPath $FrozenApk).Length -ne $meta.ApkBytes -or
            (Get-FileHash -LiteralPath $FrozenApk -Algorithm SHA256).Hash.ToLowerInvariant() -cne $meta.ApkSha256) { throw 'OTA_FROZEN_APK_CHANGED' }
        $versionDir = Join-Path $StageDirectory ([string]$meta.VersionCode)
        Assert-PersonalUpdateUnlinkedDirectory $versionDir
        $destination = Join-Path $versionDir 'apollo-rga-personal.apk'
        if (Test-Path -LiteralPath $destination) {
            $existing = Get-Item -LiteralPath $destination
            if ($existing.PSIsContainer -or ($existing.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'OTA_STAGE_LINKED' }
            if ($existing.Length -ne $meta.ApkBytes -or
                (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -cne $meta.ApkSha256) { throw 'OTA_IMMUTABLE_VERSION_CONFLICT' }
        }
        [void][IO.Directory]::CreateDirectory($versionDir)
        $temporary = Join-Path $StageDirectory ('.manifest-' + [Guid]::NewGuid().ToString('N') + '.tmp')
        [IO.File]::WriteAllText($temporary,$Envelope,[Text.UTF8Encoding]::new($false))
        if (!(Test-Path -LiteralPath $destination)) { [IO.File]::Copy($FrozenApk,$destination,$false) }
        if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -cne $meta.ApkSha256) { throw 'OTA_FROZEN_APK_CHANGED' }
        # The versioned APK is present and verified before the atomic final switch.
        [IO.File]::Move($temporary,(Join-Path $StageDirectory 'manifest.json'),$true)
    } finally { if ($owned) { $StageLock.Dispose() } }
}
function New-SignedPersonalUpdateEnvelope {
    param([string]$Payload, [Security.Cryptography.ECDsa]$Key)
    if (!$Key -or $Key.ExportParameters($false).Curve.Oid.Value -ne '1.2.840.10045.3.1.7') { throw 'OTA_SIGNING_CURVE' }
    if (!$Payload -or $Payload.Length -gt 4096 -or $Payload -cmatch '[^\x00-\x7f]' -or
        !$Payload.StartsWith("APOLLO-RGA-PERSONAL-OTA/1`n", [StringComparison]::Ordinal) -or
        !$Payload.EndsWith("`n", [StringComparison]::Ordinal) -or $Payload.Contains("`r")) { throw 'OTA_PAYLOAD_SHAPE' }
    $bytes = [Text.Encoding]::ASCII.GetBytes($Payload)
    $signature = $Key.SignData($bytes, [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)
    $p = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    $s = [Convert]::ToBase64String($signature).TrimEnd('=').Replace('+','-').Replace('/','_')
    return '{"payload":"' + $p + '","signature":"' + $s + '"}'
}
function Get-PersonalApkMetadata {
    param([string]$ApkPath, [string]$AndroidBuildTools, [switch]$Companion)
    if (!(Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw 'OTA_APK_MISSING' }
    $badging = @(& (Join-Path $AndroidBuildTools 'aapt2.exe') dump badging $ApkPath 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'OTA_APK_BADGING_FAILED' }
    $packagePattern = if ($Companion) { "^package: name='([^']+)' versionCode='([0-9]*)' versionName='([^']*)'" } else { "^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'" }
    $packageLines = @($badging | Where-Object { [string]$_ -cmatch $packagePattern })
    $sdkLines = @($badging | Where-Object { [string]$_ -cmatch "^minSdkVersion:'([0-9]+)'$" })
    $abiLines = @($badging | Where-Object { [string]$_ -cmatch "^native-code: 'arm64-v8a'$" })
    if ($packageLines.Count -ne 1 -or $sdkLines.Count -ne 1 -or (!$Companion -and $abiLines.Count -ne 1)) { throw 'OTA_APK_METADATA_FAILED' }
    [void]([string]$packageLines[0] -cmatch $packagePattern)
    $package = $Matches[1]; $version = [long]$Matches[2]; $name = $Matches[3]
    [void]([string]$sdkLines[0] -cmatch "^minSdkVersion:'([0-9]+)'$")
    $sdk = [int]$Matches[1]
    $signing = @(& (Join-Path $AndroidBuildTools 'apksigner.bat') verify --print-certs $ApkPath 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'OTA_APK_SIGNATURE_FAILED' }
    $certs = @($signing | ForEach-Object {
        if ([string]$_ -cmatch '^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$') { $Matches[1].ToLowerInvariant() }
    })
    if ($certs.Count -ne 1) { throw 'OTA_APK_SIGNER_COUNT' }
    return @{
        PackageName = $package; VersionCode = $version; VersionName = $name; MinSdk = $sdk
        Abi = 'arm64-v8a'; ApkBytes = (Get-Item -LiteralPath $ApkPath).Length
        ApkSha256 = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        ApkCertSha256 = $certs[0]
    }
}
Export-ModuleMember -Function New-PersonalUpdatePayload,New-SignedPersonalUpdateEnvelope,Get-PersonalApkMetadata,Assert-PersonalUpdateBootstrapBoundary,Assert-PersonalUpdateUnlinkedDirectory,Enter-PersonalUpdateStageLock,Get-VerifiedPersonalUpdateState,Assert-PersonalUpdateStageAdvance,Publish-PersonalUpdateStage
