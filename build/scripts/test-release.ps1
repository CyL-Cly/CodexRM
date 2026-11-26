# Release-entry tests use a temporary project and a fixture publisher; no release or network operation occurs.
$ErrorActionPreference = 'Stop'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $tempRoot ('CodexRM-release-tests-' + [Guid]::NewGuid().ToString('N'))
$scriptDir = Join-Path $testRoot 'build\scripts'
[void][IO.Directory]::CreateDirectory($scriptDir)
[void][IO.Directory]::CreateDirectory((Join-Path $testRoot 'agent-module\internal\release'))
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'release.ps1') -Destination (Join-Path $scriptDir 'release.ps1')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'client-install.ps1') -Destination (Join-Path $scriptDir 'client-install.ps1')
@'
function Get-CodexRMComponentDir([string]$Component) {
    if ($Component -cne 'Agent') { throw "Unexpected source component: $Component" }
    return Join-Path (Split-Path -Parent $env:CODEXRM_RELEASE_TEST_LOG) 'agent-module'
}
function Invoke-CodexRMGo { go @args }
'@ | Set-Content -LiteralPath (Join-Path $scriptDir 'component-sources.ps1') -Encoding UTF8
$logPath = Join-Path $testRoot 'events.log'
$previousLog = $env:CODEXRM_RELEASE_TEST_LOG
$previousFixtureAgent = $env:CODEXRM_RELEASE_FIXTURE_AGENT
$previousFixtureCode = $env:CODEXRM_RELEASE_FIXTURE_CODE_OVERRIDE
$previousFixtureName = $env:CODEXRM_RELEASE_FIXTURE_NAME_OVERRIDE
$env:CODEXRM_RELEASE_TEST_LOG = $logPath
$goCommand = (Get-Command go -ErrorAction Stop).Source
$fixtureSource = Join-Path $testRoot 'publisher.go'
$fixturePublisher = Join-Path $testRoot 'fixture-publisher.exe'
@'
package main

import (
    "bytes"
    "fmt"
    "os"
)

func main() {
    if len(os.Args) == 2 && os.Args[1] == "--version" {
        executable, err := os.Executable()
        if err != nil { panic(err) }
        contents, err := os.ReadFile(executable)
        if err != nil { panic(err) }
        marker := []byte("\nCODEXRM_FIXTURE_VERSION:")
        index := bytes.LastIndex(contents, marker)
        if index < 0 { os.Exit(2) }
        fmt.Print(string(contents[index + len(marker):]))
        return
    }
    f, err := os.OpenFile(os.Getenv("CODEXRM_RELEASE_TEST_LOG"), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0600)
    if err != nil { panic(err) }
    defer f.Close()
    if _, err = fmt.Fprintln(f, "publisher"); err != nil { panic(err) }
}
'@ | Set-Content -LiteralPath $fixtureSource -Encoding UTF8
& $goCommand build -o $fixturePublisher $fixtureSource
if ($LASTEXITCODE) { throw 'Could not build the fixture publisher.' }
$env:CODEXRM_RELEASE_FIXTURE_AGENT = $fixturePublisher
function go {
    Add-Content -LiteralPath $env:CODEXRM_RELEASE_TEST_LOG -Value ('go:' + ($args -join ' ')) -Encoding UTF8
    $output = $null
    for ($index = 0; $index -lt $args.Count; $index++) {
        if ($args[$index] -eq '-o') { $output = $args[$index + 1] }
    }
    if (-not $output) { throw 'Fixture Go build did not receive an output path.' }
    Copy-Item -LiteralPath $fixturePublisher -Destination $output -Force
    $global:LASTEXITCODE = 0
}
@'
param($Manifest,$RM,[switch]$ValidateOnly)
Add-Content -LiteralPath $env:CODEXRM_RELEASE_TEST_LOG -Value ('manifest:' + $ValidateOnly) -Encoding UTF8
'@ | Set-Content -LiteralPath (Join-Path $scriptDir 'publish-manifest.ps1') -Encoding UTF8
@'
param([switch]$Linux,[string]$Target)
Add-Content -LiteralPath $env:CODEXRM_RELEASE_TEST_LOG -Value ('build:' + $Linux + ':' + $Target) -Encoding UTF8
if ($Linux) {
    $path = Join-Path (Split-Path -Parent $PSScriptRoot) 'bin\codexrm-server-linux-amd64'
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $path))
    Set-Content -LiteralPath $path -Value 'Linux Server fixture' -Encoding UTF8
}
'@ | Set-Content -LiteralPath (Join-Path $scriptDir 'build.ps1') -Encoding UTF8
@'
param($OutputPath,$Server,$ReleaseNotes)
Add-Content -LiteralPath $env:CODEXRM_RELEASE_TEST_LOG -Value 'package' -Encoding UTF8
$match = [regex]::Match($OutputPath, '-v(\d+\.\d+\.\d+)-(\d+)\.zip$')
if (-not $match.Success) { throw 'Fixture package filename has no release version.' }
$name = $match.Groups[1].Value
$code = [long]$match.Groups[2].Value
$binaryName = if ($env:CODEXRM_RELEASE_FIXTURE_NAME_OVERRIDE) { $env:CODEXRM_RELEASE_FIXTURE_NAME_OVERRIDE } else { $name }
$binaryCode = if ($env:CODEXRM_RELEASE_FIXTURE_CODE_OVERRIDE) { [long]$env:CODEXRM_RELEASE_FIXTURE_CODE_OVERRIDE } else { $code }
$testRoot = Split-Path -Parent $env:CODEXRM_RELEASE_TEST_LOG
$stage = Join-Path $testRoot 'package-stage\CodexRM\bin'
[void][IO.Directory]::CreateDirectory($stage)
$agent = Join-Path $stage 'codexrm-agent.exe'
Copy-Item -LiteralPath $env:CODEXRM_RELEASE_FIXTURE_AGENT -Destination $agent -Force
$version = @{versionCode=$binaryCode;versionName=$binaryName} | ConvertTo-Json -Compress
[IO.File]::AppendAllText($agent, "`nCODEXRM_FIXTURE_VERSION:" + $version, (New-Object Text.UTF8Encoding $false))
[void][IO.Directory]::CreateDirectory((Split-Path -Parent $OutputPath))
Compress-Archive -LiteralPath (Split-Path -Parent $stage) -DestinationPath $OutputPath -Force
$manifest = @{versionCode=$code;versionName=$name;downloadUrl='updates/fixture.zip';fileSize=(Get-Item -LiteralPath $OutputPath).Length;sha256=(Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash.ToLowerInvariant()}
[IO.File]::WriteAllText((Join-Path (Split-Path -Parent $OutputPath) 'update.json'), ($manifest | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))
'@ | Set-Content -LiteralPath (Join-Path $scriptDir 'package-client.ps1') -Encoding UTF8
@'
param($Manifest)
Add-Content -LiteralPath $env:CODEXRM_RELEASE_TEST_LOG -Value 'setup' -Encoding UTF8
'@ | Set-Content -LiteralPath (Join-Path $scriptDir 'package-setup.ps1') -Encoding UTF8
@'
param($Binary,$RM,[switch]$ValidateOnly)
Add-Content -LiteralPath $env:CODEXRM_RELEASE_TEST_LOG -Value ('deploy:' + $ValidateOnly) -Encoding UTF8
if (-not $ValidateOnly -and -not (Test-Path -LiteralPath $Binary -PathType Leaf)) { throw 'Server binary missing.' }
'@ | Set-Content -LiteralPath (Join-Path $scriptDir 'deploy-server.ps1') -Encoding UTF8
@'
param($RM,[switch]$ValidateOnly)
Add-Content -LiteralPath $env:CODEXRM_RELEASE_TEST_LOG -Value ('web:' + $ValidateOnly) -Encoding UTF8
'@ | Set-Content -LiteralPath (Join-Path $scriptDir 'deploy-web.ps1') -Encoding UTF8
@'
param([long]$ExpectedVersionCode,[string]$ExpectedVersionName,[string]$InstallDir,[switch]$ValidateOnly)
$kind = if ($ValidateOnly) { 'local-preflight' } else { 'local' }
Add-Content -LiteralPath $env:CODEXRM_RELEASE_TEST_LOG -Value ($kind + ':' + $ExpectedVersionCode + ':' + $ExpectedVersionName + ':' + $InstallDir) -Encoding UTF8
$fixtureRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ($ValidateOnly -and (Test-Path -LiteralPath (Join-Path $fixtureRoot 'local-preflight.fail'))) { throw 'Fixture local preflight failure.' }
'@ | Set-Content -LiteralPath (Join-Path $scriptDir 'update-local-client.ps1') -Encoding UTF8
$config = Join-Path $testRoot 'publish.config.json'
@'
{"cloudreve":{"base":"https://cloud.example","email":"test@example.com"},"rm":{"server":"https://rm.example","sshTarget":"test-rm","updateDir":"/opt/codexrm/data/updates","installDir":"/opt/codexrm","service":"codexrm"}}
'@ | Set-Content -LiteralPath $config -Encoding UTF8
$versionPath = Join-Path $testRoot 'agent-module\internal\release\release.go'
$releaseScript = Join-Path $scriptDir 'release.ps1'
function Set-ReleaseVersion([long]$Code,[string]$Name) {
    @"
package release

const VersionCode int64 = $Code
const VersionName = "$Name"
"@ | Set-Content -LiteralPath $versionPath -Encoding UTF8
}
function Get-ReleaseEvents { return @(Get-Content -LiteralPath $logPath -Encoding UTF8) }
function Assert-Release([bool]$Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
try {
    Set-ReleaseVersion 10 '1.2.3'
    & $releaseScript -Config $config -ReleaseNotes 'fixture release'
    $events = Get-ReleaseEvents
    $version = Get-Content -LiteralPath $versionPath -Raw -Encoding UTF8
    Assert-Release ($version.Contains('VersionCode int64 = 10') -and $version.Contains('VersionName = "1.2.3"')) 'Default release changed the pinned Agent source.'
    Assert-Release (@($events | Where-Object { $_ -eq 'build:False:Agent' }).Count -eq 1) 'Default release did not build the Agent.'
    Assert-Release (-not @($events | Where-Object { $_ -like 'build:True:*' -or $_ -like 'deploy:*' -or $_ -like 'web:*' -or $_ -like 'local:*' }).Count) 'Default release executed an optional step.'
    Assert-Release (($events[-2] -ceq 'publisher') -and ($events[-1] -ceq 'manifest:False')) 'Cloudreve publication and manifest publication are out of order.'
    Write-Host 'PASS: Default release keeps the pinned Agent source immutable and leaves both optional steps disabled.'

    Clear-Content -LiteralPath $logPath
    Set-ReleaseVersion 20 '2.0.0'
    & $releaseScript -Config $config -DeployServer -TestLocalUpdate -LocalInstallDir 'D:\custom client'
    $events = Get-ReleaseEvents
    $version = Get-Content -LiteralPath $versionPath -Raw -Encoding UTF8
    Assert-Release ($version.Contains('VersionCode int64 = 20') -and $version.Contains('VersionName = "2.0.0"')) 'Optional release changed the version unexpectedly.'
    Assert-Release (@($events | Where-Object { $_ -eq 'build:True:Server' }).Count -eq 1) '-DeployServer did not build Linux Server.'
    $manifestIndex = [Array]::LastIndexOf([object[]]$events, [object]'manifest:False')
    $deployIndex = [Array]::LastIndexOf([object[]]$events, [object]'deploy:False')
    $webIndex = [Array]::LastIndexOf([object[]]$events, [object]'web:False')
    $localIndex = [Array]::LastIndexOf([object[]]$events, [object]'local:20:2.0.0:D:\custom client')
    $preflightIndex = [Array]::IndexOf([object[]]$events, [object]'local-preflight:20:2.0.0:D:\custom client')
    $packageIndex = [Array]::IndexOf([object[]]$events, [object]'package')
    Assert-Release ($preflightIndex -ge 0 -and $packageIndex -gt $preflightIndex) 'Local installation preflight did not run before packaging.'
    Assert-Release ($manifestIndex -ge 0 -and $deployIndex -gt $manifestIndex -and $webIndex -gt $deployIndex -and $localIndex -gt $webIndex) 'Optional steps did not run after client publication in the requested order.'
    Assert-Release (@($events | Where-Object { $_ -eq 'deploy:True' }).Count -eq 1) 'Server configuration was not validated before packaging.'
    Assert-Release (@($events | Where-Object { $_ -eq 'web:True' }).Count -eq 1) 'Frontend configuration was not validated before packaging.'
    Write-Host 'PASS: Optional Server deployment builds Linux Server, then Server, frontend and local verification follow client publication.'

    Clear-Content -LiteralPath $logPath
    & $releaseScript -Config $config -PublishOnly -DeployServer -TestLocalUpdate
    $events = Get-ReleaseEvents
    $version = Get-Content -LiteralPath $versionPath -Raw -Encoding UTF8
    Assert-Release ($version.Contains('VersionCode int64 = 20') -and $version.Contains('VersionName = "2.0.0"')) '-PublishOnly bumped the version.'
    Assert-Release (-not @($events | Where-Object { $_ -like 'build:*' -or $_ -eq 'package' }).Count) '-PublishOnly rebuilt or repackaged the release.'
    Assert-Release (@($events | Where-Object { $_ -eq 'deploy:False' }).Count -eq 1 -and @($events | Where-Object { $_ -eq 'local:20:2.0.0:' }).Count -eq 1) '-PublishOnly could not retry the optional steps.'
    Assert-Release (@($events | Where-Object { $_ -eq 'web:False' }).Count -eq 1) '-PublishOnly did not retry frontend deployment.'
    Write-Host 'PASS: Publish-only retries reuse artifacts and can rerun deployment and local verification without another version bump.'

    $archive = Join-Path $testRoot 'build\dist\CodexRM-windows-amd64-v2.0.0-20.zip'
    $manifestPath = Join-Path $testRoot 'build\dist\update.json'
    $validManifest = [IO.File]::ReadAllText($manifestPath)
    foreach ($field in @('versionCode','versionName')) {
        $metadata = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($field -eq 'versionCode') { $metadata.versionCode = 19 } else { $metadata.versionName = '1.9.9' }
        [IO.File]::WriteAllText($manifestPath, ($metadata | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))
        Clear-Content -LiteralPath $logPath
        $rejected = $false
        try { & $releaseScript -Config $config -PublishOnly } catch { $rejected = $true }
        Assert-Release $rejected "Publish-only accepted a manifest $field mismatch."
        Assert-Release (-not @(Get-ReleaseEvents | Where-Object { $_ -eq 'publisher' }).Count) "A manifest $field mismatch reached upload."
        [IO.File]::WriteAllText($manifestPath, $validManifest, (New-Object Text.UTF8Encoding $false))
    }
    Write-Host 'PASS: Publish-only rejects dist update.json versionCode/versionName mismatches before upload.'

    $env:CODEXRM_RELEASE_FIXTURE_CODE_OVERRIDE = '19'
    $env:CODEXRM_RELEASE_FIXTURE_NAME_OVERRIDE = '1.9.9'
    & (Join-Path $scriptDir 'package-client.ps1') -OutputPath $archive
    $env:CODEXRM_RELEASE_FIXTURE_CODE_OVERRIDE = $null
    $env:CODEXRM_RELEASE_FIXTURE_NAME_OVERRIDE = $null
    Clear-Content -LiteralPath $logPath
    $rejected = $false
    try { & $releaseScript -Config $config -PublishOnly } catch { $rejected = $true }
    Assert-Release $rejected 'Publish-only accepted a ZIP client version mismatch.'
    Assert-Release (-not @(Get-ReleaseEvents | Where-Object { $_ -eq 'publisher' }).Count) 'A ZIP client version mismatch reached upload.'
    & (Join-Path $scriptDir 'package-client.ps1') -OutputPath $archive
    Write-Host 'PASS: Publish-only executes and rejects a stale ZIP client before upload.'

    Clear-Content -LiteralPath $logPath
    Set-ReleaseVersion 30 '3.0.0'
    New-Item -ItemType File -Path (Join-Path $testRoot 'local-preflight.fail') -Force | Out-Null
    $rejected = $false
    try { & $releaseScript -Config $config -TestLocalUpdate } catch { $rejected = $true }
    $events = Get-ReleaseEvents
    $version = Get-Content -LiteralPath $versionPath -Raw -Encoding UTF8
    Assert-Release $rejected 'A local preflight failure did not stop the release.'
    Assert-Release ($version.Contains('VersionCode int64 = 30') -and $version.Contains('VersionName = "3.0.0"')) 'A failed local preflight changed the release version.'
    Assert-Release (-not @($events | Where-Object { $_ -like 'go:*' -or $_ -like 'build:*' -or $_ -eq 'package' -or $_ -eq 'publisher' -or $_ -eq 'manifest:False' }).Count) 'A failed local preflight reached build, package, or publication.'
    Write-Host 'PASS: Local preflight failures stop before version changes, builds, uploads, or publication.'
} finally {
    $env:CODEXRM_RELEASE_TEST_LOG = $previousLog
    $env:CODEXRM_RELEASE_FIXTURE_AGENT = $previousFixtureAgent
    $env:CODEXRM_RELEASE_FIXTURE_CODE_OVERRIDE = $previousFixtureCode
    $env:CODEXRM_RELEASE_FIXTURE_NAME_OVERRIDE = $previousFixtureName
    $resolvedRoot = [IO.Path]::GetFullPath($testRoot)
    if ($resolvedRoot.StartsWith($tempRoot.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedRoot).StartsWith('CodexRM-release-tests-')) {
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
}
