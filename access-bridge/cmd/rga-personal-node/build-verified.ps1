param(
    [Parameter(Mandatory = $true)][string]$PinnedSource,
    [Parameter(Mandatory = $true)][string]$Output
)

$ErrorActionPreference = 'Stop'
$commandDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$bridgeDir = (Resolve-Path -LiteralPath (Join-Path $commandDir '..\..')).Path
$repoDir = (Resolve-Path -LiteralPath (Join-Path $bridgeDir '..')).Path
$materializer = Join-Path $repoDir 'scripts\materialize-olcrtc-managed.py'
$sourceDir = (Resolve-Path -LiteralPath $PinnedSource).Path
$outputPath = [IO.Path]::GetFullPath($Output)
$outputParent = Split-Path -Parent $outputPath
if ((Test-Path -LiteralPath $outputPath) -or -not (Test-Path -LiteralPath $outputParent -PathType Container)) {
    throw 'output already exists or its parent is unavailable'
}
$tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$workDir = Join-Path $tempParent ('rga-personal-node-' + [guid]::NewGuid().ToString('N'))
$oldGoWork = $env:GOWORK
$oldGoFlags = $env:GOFLAGS
$oldMaxProcs = $env:GOMAXPROCS

New-Item -ItemType Directory -Path $workDir -ErrorAction Stop | Out-Null
try {
    # The existing materializer verifies the authoritative engine pin, both
    # patch digests, a clean exact-base source and git apply before any build.
    & python $materializer --source $sourceDir --output (Join-Path $workDir 'managed')
    if ($LASTEXITCODE -ne 0) { throw 'managed materialization failed' }

    Push-Location $workDir
    try {
        & go work init $bridgeDir (Join-Path $workDir 'managed')
        if ($LASTEXITCODE -ne 0) { throw 'temporary workspace setup failed' }
    } finally {
        Pop-Location
    }
    $env:GOWORK = Join-Path $workDir 'go.work'
    $env:GOFLAGS = '-p=2'
    $env:GOMAXPROCS = '2'
    Push-Location $bridgeDir
    try {
        & go test ./cmd/rga-personal-node/... -count=1
        if ($LASTEXITCODE -ne 0) { throw 'personal node tests failed' }
        & go build -o (Join-Path $workDir 'rga-personal-node.exe') ./cmd/rga-personal-node
        if ($LASTEXITCODE -ne 0) { throw 'personal node build failed' }
        if (Test-Path -LiteralPath $outputPath) { throw 'output already exists' }
        [IO.File]::Move((Join-Path $workDir 'rga-personal-node.exe'), $outputPath)
    } finally {
        Pop-Location
    }
} finally {
    $env:GOWORK = $oldGoWork
    $env:GOFLAGS = $oldGoFlags
    $env:GOMAXPROCS = $oldMaxProcs
    $stage = Get-Item -LiteralPath $workDir -Force -ErrorAction Stop
    $tempRoot = Get-Item -LiteralPath $tempParent -Force -ErrorAction Stop
    $resolvedWorkDir = (Resolve-Path -LiteralPath $stage.FullName).ProviderPath
    $resolvedTempRoot = (Resolve-Path -LiteralPath $tempRoot.FullName).ProviderPath
    $isReparse = (($stage.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) -or
        (($tempRoot.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) -or
        ($null -ne $stage.ResolveLinkTarget($true))
    if (-not $isReparse -and
        (Split-Path -Parent $resolvedWorkDir).TrimEnd('\') -eq $resolvedTempRoot.TrimEnd('\') -and
        (Split-Path -Leaf $resolvedWorkDir) -like 'rga-personal-node-*') {
        Remove-Item -LiteralPath $stage.FullName -Recurse -Force
    } else {
        throw 'refusing cleanup outside system temp'
    }
}
