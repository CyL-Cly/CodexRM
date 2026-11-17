param(
    [string]$Manifest,
    [string]$OutputPath
)
$ErrorActionPreference = 'Stop'
$buildRoot = Split-Path -Parent $PSScriptRoot
$projectRoot = Split-Path -Parent $buildRoot

if (-not $Manifest) {
    $sourcePath = Join-Path $buildRoot 'update-source.json'
    if (Test-Path -LiteralPath $sourcePath -PathType Leaf) {
        $source = Get-Content -LiteralPath $sourcePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $Manifest = [string]$source.manifestUrl
    }
}
if (-not $Manifest) {
    $publishPath = Join-Path $buildRoot 'publish.config.json'
    if (Test-Path -LiteralPath $publishPath -PathType Leaf) {
        $publish = Get-Content -LiteralPath $publishPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($publish.rm -and $publish.rm.server) {
            $Manifest = ([string]$publish.rm.server).TrimEnd('/') + '/update.json'
        }
    }
}
if (-not $Manifest) { $Manifest = 'https://codexrm.dagongren.tech/update.json' }
$manifestUri = $null
if (-not [Uri]::TryCreate($Manifest, [UriKind]::Absolute, [ref]$manifestUri) -or
        $manifestUri.Scheme -notin @('http', 'https') -or -not $manifestUri.Host -or
        $manifestUri.UserInfo -or $manifestUri.Fragment) {
    throw 'The setup manifest must be an absolute HTTP(S) URL without user information or a fragment.'
}
$Manifest = $manifestUri.AbsoluteUri
if (-not $OutputPath) { $OutputPath = Join-Path $buildRoot 'dist\CodexRM-Setup.exe' }
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
[void][IO.Directory]::CreateDirectory((Split-Path -Parent $OutputPath))

$previousOS = $env:GOOS
$previousArch = $env:GOARCH
$previousCGO = $env:CGO_ENABLED
Push-Location $projectRoot
try {
    $env:GOOS = 'windows'
    $env:GOARCH = 'amd64'
    $env:CGO_ENABLED = '0'
    $ldflags = '-s -w -X main.defaultManifestURL=' + $Manifest
    & go build -trimpath -ldflags $ldflags -o $OutputPath ./cmd/codexrm-setup
    if ($LASTEXITCODE) { throw "Online installer build failed (exit code $LASTEXITCODE)." }
} finally {
    $env:GOOS = $previousOS
    $env:GOARCH = $previousArch
    $env:CGO_ENABLED = $previousCGO
    Pop-Location
}
Write-Host "Online installer created: $OutputPath"
