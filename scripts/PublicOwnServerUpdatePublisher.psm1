Set-StrictMode -Version Latest
# Reuse only the accepted local APK tooling; no signing key is loaded or invented here.
Import-Module (Join-Path $PSScriptRoot 'PersonalUpdatePublisher.psm1') -Force
function Assert-PublicOwnServerUpdateBindings {
    param([Security.Cryptography.ECDsa]$Key, [string]$PinnedSpkiBase64, [string]$ReleaseSignerSha256)
    if (!$Key -or $Key.ExportParameters($false).Curve.Oid.Value -ne '1.2.840.10045.3.1.7' -or
        !$PinnedSpkiBase64 -or $PinnedSpkiBase64.Length -gt 512 -or
        $ReleaseSignerSha256 -cnotmatch '\A[0-9a-f]{64}\z' -or
        $ReleaseSignerSha256 -ceq '11cfb810016a9f39d75cc517e9f3a8c731e6d0b4e9156e5872c6138cadd3a017') {
        throw 'OTA_PUBLIC_BINDING_REQUIRED'
    }
    try { $bytes = [Convert]::FromBase64String($PinnedSpkiBase64) } catch { throw 'OTA_PUBLIC_KEY_SHAPE' }
    if ([Convert]::ToBase64String($bytes) -cne $PinnedSpkiBase64 -or
        [Convert]::ToBase64String($Key.ExportSubjectPublicKeyInfo()) -cne $PinnedSpkiBase64) { throw 'OTA_PUBLIC_KEY_MISMATCH' }
}
function New-PublicOwnServerUpdatePayload {
    param([hashtable]$Metadata, [long]$Sequence, [long]$IssuedAt, [long]$ExpiresAt,
        [Parameter(Mandatory)][string]$ReleaseSignerSha256)
    $required = @('PackageName','VersionCode','VersionName','MinSdk','Abi','ApkBytes',
        'ApkSha256','ApkCertSha256','Channel')
    if (!$Metadata -or $Metadata.Count -ne $required.Count -or
        @($required | Where-Object { !$Metadata.ContainsKey($_) }).Count -ne 0) {
        throw 'OTA_METADATA_SHAPE'
    }
    if ($Sequence -le 0 -or $IssuedAt -lt 0 -or $ExpiresAt -le $IssuedAt -or
        $ExpiresAt - $IssuedAt -gt 604800) { throw 'OTA_TIME_BOUNDS' }
    if ($Metadata.PackageName -cne 'tech.gatealtas.rga' -or
        $Metadata.Channel -cne 'own-server-stable' -or $Metadata.ApkCertSha256 -cne $ReleaseSignerSha256 -or
        $ReleaseSignerSha256 -cnotmatch '\A[0-9a-f]{64}\z' -or
        $ReleaseSignerSha256 -ceq '11cfb810016a9f39d75cc517e9f3a8c731e6d0b4e9156e5872c6138cadd3a017' -or
        $Metadata.Abi -cne 'arm64-v8a' -or
        [string]$Metadata.VersionName -cnotmatch '\A[A-Za-z0-9.+_-]{1,64}\z') { throw 'OTA_METADATA_CONTRACT' }
    if ([long]$Metadata.VersionCode -lt 2) { throw 'OTA_BOOTSTRAP_BOUNDARY' }
    if ([long]$Metadata.VersionCode -gt 2147483647) { throw 'OTA_VERSION_BOUNDS' }
    if ([long]$Metadata.MinSdk -lt 23 -or [long]$Metadata.MinSdk -gt 2147483647 -or
        [long]$Metadata.ApkBytes -lt 1 -or [long]$Metadata.ApkBytes -gt 268435456) { throw 'OTA_APK_BOUNDS' }
    foreach ($field in @('ApkSha256','ApkCertSha256')) {
        if ([string]$Metadata[$field] -cnotmatch '\A[0-9a-f]{64}\z') { throw 'OTA_HASH_SHAPE' }
    }
    $ci = [Globalization.CultureInfo]::InvariantCulture
    $lines = @('APOLLO-RGA-PUBLIC-OTA/1',
        ('sequence=' + $Sequence.ToString($ci)), ('issuedAt=' + $IssuedAt.ToString($ci)),
        ('expiresAt=' + $ExpiresAt.ToString($ci)), 'package=tech.gatealtas.rga',
        ('versionCode=' + ([long]$Metadata.VersionCode).ToString($ci)), ('versionName=' + $Metadata.VersionName),
        ('minSdk=' + ([long]$Metadata.MinSdk).ToString($ci)), 'abi=arm64-v8a',
        ('apkPath=/updates/public/v1/' + ([long]$Metadata.VersionCode).ToString($ci) + '/apollo-rga.apk'),
        ('apkBytes=' + ([long]$Metadata.ApkBytes).ToString($ci)), ('apkSha256=' + $Metadata.ApkSha256),
        ('apkCertSha256=' + $Metadata.ApkCertSha256), 'channel=own-server-stable')
    return ($lines -join "`n") + "`n"
}
function Assert-PublicOwnServerUpdateBootstrapBoundary {
    param([long]$CandidateVersionCode, [long]$BaselineVersionCode)
    if ($BaselineVersionCode -lt 1 -or $CandidateVersionCode -le $BaselineVersionCode -or
        $CandidateVersionCode -gt 2147483647) { throw 'OTA_BOOTSTRAP_BOUNDARY' }
}
function Assert-PublicOwnServerUpdateUnlinkedDirectory {
    param([string]$Path)
    $ancestor = [IO.DirectoryInfo]::new([IO.Path]::GetFullPath($Path))
    while ($ancestor) {
        if ([IO.File]::Exists($ancestor.FullName)) { throw 'OTA_STAGE_NOT_DIRECTORY' }
        if ($ancestor.Exists -and ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'OTA_STAGE_LINKED' }
        $ancestor = $ancestor.Parent
    }
}
function Enter-PublicOwnServerUpdateStageLock {
    param([string]$StageDirectory)
    Assert-PublicOwnServerUpdateUnlinkedDirectory $StageDirectory
    [void][IO.Directory]::CreateDirectory($StageDirectory)
    $path = Join-Path $StageDirectory '.publish.lock'
    if ((Test-Path -LiteralPath $path) -and
        ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'OTA_STAGE_LINKED' }
    # Leave this zero-content lock file in place: deleting it would let another
    # process lock a replacement file while the original handle is still owned.
    try { return [IO.File]::Open($path,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
    catch { throw 'OTA_STAGE_BUSY' }
}
function Get-VerifiedPublicOwnServerUpdateState {
    param([byte[]]$EnvelopeBytes, [Security.Cryptography.ECDsa]$Key,
        [Parameter(Mandatory)][string]$PinnedSpkiBase64, [Parameter(Mandatory)][string]$ReleaseSignerSha256)
    Assert-PublicOwnServerUpdateBindings $Key $PinnedSpkiBase64 $ReleaseSignerSha256
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
    if ($lines.Count -ne 15 -or $lines[0] -cne 'APOLLO-RGA-PUBLIC-OTA/1' -or $lines[14] -cne '') { throw 'OTA_PAYLOAD_SHAPE' }
    $fields = @('sequence','issuedAt','expiresAt','package','versionCode','versionName','minSdk','abi',
        'apkPath','apkBytes','apkSha256','apkCertSha256','channel')
    $values = @{}
    for ($i = 0; $i -lt $fields.Count; $i++) {
        $prefix = $fields[$i] + '='
        if (!$lines[$i+1].StartsWith($prefix,[StringComparison]::Ordinal)) { throw 'OTA_PAYLOAD_SHAPE' }
        $values[$fields[$i]] = $lines[$i+1].Substring($prefix.Length)
    }
    foreach ($field in @('sequence','issuedAt','expiresAt','versionCode','minSdk','apkBytes')) {
        [long]$number = 0
        if ($values[$field] -cnotmatch '\A(?:0|[1-9][0-9]*)\z' -or
            ![long]::TryParse($values[$field],[Globalization.NumberStyles]::None,[Globalization.CultureInfo]::InvariantCulture,[ref]$number)) { throw 'OTA_DECIMAL_SHAPE' }
        $values[$field] = $number
    }
    $metadata = @{
        PackageName=$values.package; VersionCode=$values.versionCode; VersionName=$values.versionName
        MinSdk=$values.minSdk; Abi=$values.abi; ApkBytes=$values.apkBytes
        ApkSha256=$values.apkSha256; ApkCertSha256=$values.apkCertSha256
        Channel=$values.channel
    }
    $canonical = New-PublicOwnServerUpdatePayload $metadata $values.sequence $values.issuedAt $values.expiresAt $ReleaseSignerSha256
    if ($payload -cne $canonical) { throw 'OTA_PAYLOAD_SHAPE' }
    return @{
        Sequence=$values.sequence; Metadata=$metadata
        PayloadSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($payloadBytes)).ToLowerInvariant()
    }
}
function Assert-PublicOwnServerUpdateStageAdvance {
    param([string]$StageDirectory, [long]$Sequence, [Security.Cryptography.ECDsa]$Key,
        [Parameter(Mandatory)][string]$PinnedSpkiBase64, [Parameter(Mandatory)][string]$ReleaseSignerSha256)
    $manifest = Join-Path $StageDirectory 'manifest.json'
    if (!(Test-Path -LiteralPath $manifest)) { return }
    try {
        $file = Get-Item -LiteralPath $manifest
        if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $file.Length -gt 8192) { throw 'invalid' }
        $prior = Get-VerifiedPublicOwnServerUpdateState ([IO.File]::ReadAllBytes($manifest)) $Key $PinnedSpkiBase64 $ReleaseSignerSha256
    } catch { throw 'OTA_PRIOR_STATE_INVALID' }
    if ($Sequence -le $prior.Sequence) { throw 'OTA_SEQUENCE_NOT_ADVANCED' }
}
function Publish-PublicOwnServerUpdateStage {
    param([string]$StageDirectory, [string]$FrozenApk, [string]$Envelope,
        [Security.Cryptography.ECDsa]$Key, [Parameter(Mandatory)][long]$BaselineVersionCode,
        [Parameter(Mandatory)][string]$PinnedSpkiBase64, [Parameter(Mandatory)][string]$ReleaseSignerSha256,
        [Parameter(Mandatory)][string]$AndroidBuildTools, [IO.FileStream]$StageLock)
    Assert-PublicOwnServerUpdateBindings $Key $PinnedSpkiBase64 $ReleaseSignerSha256
    $owned = !$StageLock
    if ($owned) { $StageLock = Enter-PublicOwnServerUpdateStageLock $StageDirectory }
    try {
        $expectedLock = [IO.Path]::GetFullPath((Join-Path $StageDirectory '.publish.lock'))
        if (!$StageLock.CanRead -or !$StageLock.CanWrite -or $StageLock.Name -cne $expectedLock) { throw 'OTA_STAGE_LOCK_INVALID' }
        $state = Get-VerifiedPublicOwnServerUpdateState ([Text.Encoding]::UTF8.GetBytes($Envelope)) $Key $PinnedSpkiBase64 $ReleaseSignerSha256
        Assert-PublicOwnServerUpdateBootstrapBoundary $state.Metadata.VersionCode $BaselineVersionCode
        Assert-PublicOwnServerUpdateStageAdvance $StageDirectory $state.Sequence $Key $PinnedSpkiBase64 $ReleaseSignerSha256
        $meta = $state.Metadata
        $actual = Get-PublicOwnServerApkMetadata $FrozenApk $AndroidBuildTools
        foreach ($field in $meta.Keys) {
            if ([string]$actual[$field] -cne [string]$meta[$field]) { throw 'OTA_FROZEN_APK_METADATA_CHANGED' }
        }
        if ((Get-Item -LiteralPath $FrozenApk).Length -ne $meta.ApkBytes -or
            (Get-FileHash -LiteralPath $FrozenApk -Algorithm SHA256).Hash.ToLowerInvariant() -cne $meta.ApkSha256) { throw 'OTA_FROZEN_APK_CHANGED' }
        $versionDir = Join-Path $StageDirectory ([string]$meta.VersionCode)
        Assert-PublicOwnServerUpdateUnlinkedDirectory $versionDir
        $destination = Join-Path $versionDir 'apollo-rga.apk'
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
function New-SignedPublicOwnServerUpdateEnvelope {
    param([string]$Payload, [Security.Cryptography.ECDsa]$Key,
        [Parameter(Mandatory)][string]$PinnedSpkiBase64, [Parameter(Mandatory)][string]$ReleaseSignerSha256)
    Assert-PublicOwnServerUpdateBindings $Key $PinnedSpkiBase64 $ReleaseSignerSha256
    if (!$Payload -or $Payload.Length -gt 4096 -or $Payload -cmatch '[^\x00-\x7f]' -or
        !$Payload.StartsWith("APOLLO-RGA-PUBLIC-OTA/1`n", [StringComparison]::Ordinal) -or
        !$Payload.EndsWith("`n", [StringComparison]::Ordinal) -or $Payload.Contains("`r")) { throw 'OTA_PAYLOAD_SHAPE' }
    $bytes = [Text.Encoding]::ASCII.GetBytes($Payload)
    $signature = $Key.SignData($bytes, [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)
    $p = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    $s = [Convert]::ToBase64String($signature).TrimEnd('=').Replace('+','-').Replace('/','_')
    $envelope = '{"payload":"' + $p + '","signature":"' + $s + '"}'
    $null = Get-VerifiedPublicOwnServerUpdateState ([Text.Encoding]::UTF8.GetBytes($envelope)) $Key $PinnedSpkiBase64 $ReleaseSignerSha256
    return $envelope
}
function Get-PublicOwnServerApkMetadata {
    param([string]$ApkPath, [string]$AndroidBuildTools)
    $metadata = Get-PersonalApkMetadata $ApkPath $AndroidBuildTools
    if ($metadata.PackageName -cne 'tech.gatealtas.rga') { throw 'OTA_PUBLIC_PACKAGE_REQUIRED' }
    # AAPT2 binary XML facts, not a caller Boolean or a manifest channel guessed from filename.
    $tree = @(& (Join-Path $AndroidBuildTools 'aapt2.exe') dump xmltree $ApkPath --file AndroidManifest.xml 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'OTA_APK_CHANNEL_FAILED' }
    $applicationIndent = -1; $metadataIndent = -1; $name = $null; $value = $null; $channels = @()
    foreach ($raw in $tree) {
        $line = [string]$raw
        if ($line -cmatch '^(\s*)E: ([A-Za-z0-9_-]+)(?: \(|$)') {
            $indent = $Matches[1].Length; $element = $Matches[2]
            if ($metadataIndent -ge 0 -and $indent -le $metadataIndent) {
                if ($name -ceq 'tech.gatealtas.rga.OWN_SERVER_UPDATE_CHANNEL') { $channels += $value }
                $metadataIndent = -1; $name = $null; $value = $null
            }
            if ($applicationIndent -ge 0 -and $indent -le $applicationIndent) { $applicationIndent = -1 }
            if ($element -ceq 'application') { $applicationIndent = $indent }
            if ($element -ceq 'meta-data' -and $applicationIndent -ge 0 -and $indent -gt $applicationIndent) {
                $metadataIndent = $indent
            }
        } elseif ($metadataIndent -ge 0 -and $line -cmatch '^\s+A: android:(name|value)\(0x[0-9a-fA-F]+\)="([^"]*)" \(Raw: "[^"]*"\)$') {
            if ($Matches[1] -ceq 'name') { if ($null -ne $name) { throw 'OTA_APK_CHANNEL_SHAPE' }; $name = $Matches[2] }
            else { if ($null -ne $value) { throw 'OTA_APK_CHANNEL_SHAPE' }; $value = $Matches[2] }
        }
        if ($line -cmatch '^\s+A: split(?:\(0x[0-9a-fA-F]+\))?=') { throw 'OTA_APK_SPLIT_REFUSED' }
    }
    if ($metadataIndent -ge 0 -and $name -ceq 'tech.gatealtas.rga.OWN_SERVER_UPDATE_CHANNEL') { $channels += $value }
    if ($channels.Count -ne 1 -or $channels[0] -cne 'own-server-stable') { throw 'OTA_APK_CHANNEL_REQUIRED' }
    $metadata.Channel = 'own-server-stable'
    return $metadata
}
Export-ModuleMember -Function New-PublicOwnServerUpdatePayload,New-SignedPublicOwnServerUpdateEnvelope,Get-PublicOwnServerApkMetadata,Assert-PublicOwnServerUpdateBootstrapBoundary,Assert-PublicOwnServerUpdateUnlinkedDirectory,Enter-PublicOwnServerUpdateStageLock,Get-VerifiedPublicOwnServerUpdateState,Assert-PublicOwnServerUpdateStageAdvance,Publish-PublicOwnServerUpdateStage
