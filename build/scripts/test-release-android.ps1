# Release tests run fixture executables in a temporary project. No remote access occurs.
$ErrorActionPreference = 'Stop'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $tempRoot ('CodexRM Android release tests-' + [Guid]::NewGuid().ToString('N') + '-' + [char]0x4e2d + [char]0x6587)
$scriptDir = Join-Path $testRoot 'build\scripts'
$androidRoot = Join-Path $testRoot 'android'
foreach ($directory in @($scriptDir, (Join-Path $androidRoot 'app'), (Join-Path $androidRoot 'gradle\wrapper'))) {
    [void][IO.Directory]::CreateDirectory($directory)
}
foreach ($name in @('release-android.ps1', 'package-android.ps1', 'assert-android-package.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $scriptDir $name)
}
Copy-Item -LiteralPath (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'android\build.bat') -Destination (Join-Path $androidRoot 'build.bat')
[IO.File]::WriteAllText((Join-Path $androidRoot 'gradle\wrapper\gradle-wrapper.jar'), 'wrapper fixture')
$versionPath = Join-Path $androidRoot 'app\build.gradle.kts'
$releaseScript = Join-Path $scriptDir 'release-android.ps1'
$config = Join-Path $testRoot 'publish.config.json'
$logPath = Join-Path $testRoot 'events.log'
$fixtureTool = Join-Path $testRoot 'fixture.exe'
$savedEnvironment = @{}
foreach ($key in @('JAVA_EXE','BUILD_PROXY','ANDROID_HOME','ANDROID_SDK_ROOT','CODEXRM_ANDROID_TEST_ROOT','CODEXRM_ANDROID_TEST_MODE','CODEXRM_ANDROID_TEST_PACKAGE','CODEXRM_ANDROID_TEST_CODE','CODEXRM_ANDROID_TEST_NAME','CODEXRM_ANDROID_TEST_SIGNATURE')) {
    $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$env:JAVA_EXE = $fixtureTool
$env:BUILD_PROXY = ''
$env:CODEXRM_ANDROID_TEST_ROOT = $testRoot
$goCommand = (Get-Command go -CommandType Application -ErrorAction Stop).Source

function Assert-AndroidRelease([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Reset-Fixture {
    $env:CODEXRM_ANDROID_TEST_MODE = ''
    $env:CODEXRM_ANDROID_TEST_PACKAGE = ''
    $env:CODEXRM_ANDROID_TEST_CODE = ''
    $env:CODEXRM_ANDROID_TEST_NAME = ''
    $env:CODEXRM_ANDROID_TEST_SIGNATURE = ''
    # Include a BOM and CRLF to verify byte-for-byte rollback.
    $source = "// fixture`r`nandroid {`r`n    defaultConfig {`r`n        versionCode = 10`r`n        versionName = `"1.2.3`"`r`n    }`r`n}`r`n"
    [IO.File]::WriteAllText($versionPath, $source, (New-Object Text.UTF8Encoding $true))
    [IO.File]::WriteAllText($logPath, '')
}
function Get-Events { return @(Get-Content -LiteralPath $logPath -Encoding UTF8) }
function Expect-Failure([scriptblock]$Action) {
    $failed = $false
    try { & $Action } catch { $failed = $true }
    Assert-AndroidRelease $failed 'Expected release failure did not occur.'
}
function Assert-OriginalVersion([byte[]]$Expected) {
    Assert-AndroidRelease ([Linq.Enumerable]::SequenceEqual($Expected, [byte[]][IO.File]::ReadAllBytes($versionPath))) 'Version file was not preserved byte-for-byte.'
}
function go {
    Add-Content -LiteralPath $logPath -Value 'go' -Encoding UTF8
    $output = $null
    for ($index = 0; $index -lt $args.Count; $index++) {
        if ($args[$index] -eq '-o') { $output = $args[$index + 1] }
    }
    if (-not $output) { throw 'Fixture publisher build did not receive -o.' }
    Copy-Item -LiteralPath $fixtureTool -Destination $output -Force
    $global:LASTEXITCODE = 0
}

try {
    $fixtureSource = Join-Path $testRoot 'fixture.go'
    @'
package main

import (
    "archive/zip"
    "fmt"
    "os"
    "path/filepath"
    "regexp"
    "strings"
)

func main() {
    root := os.Getenv("CODEXRM_ANDROID_TEST_ROOT")
    mode := os.Getenv("CODEXRM_ANDROID_TEST_MODE")
    log, err := os.OpenFile(filepath.Join(root, "events.log"), os.O_APPEND|os.O_WRONLY, 0600)
    if err != nil { panic(err) }
    defer log.Close()
    executable := strings.ToLower(filepath.Base(os.Args[0]))
    if executable == "aapt2.exe" {
        if len(os.Args) != 4 || os.Args[1] != "dump" || os.Args[2] != "badging" { os.Exit(64) }
        version, err := os.ReadFile(filepath.Join(root, "android", "app", "build.gradle.kts"))
        if err != nil { panic(err) }
        code := regexp.MustCompile(`(?m)^\s*versionCode\s*=\s*(\d+)\s*$`).FindSubmatch(version)
        name := regexp.MustCompile(`(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$`).FindSubmatch(version)
        if len(code) != 2 || len(name) != 2 { panic("fixture version missing") }
        actualCode, actualName := string(code[1]), string(name[1])
        if override := os.Getenv("CODEXRM_ANDROID_TEST_CODE"); override != "" { actualCode = override }
        if override := os.Getenv("CODEXRM_ANDROID_TEST_NAME"); override != "" { actualName = override }
        packageName := "com.codexrm.mobile"
        if override := os.Getenv("CODEXRM_ANDROID_TEST_PACKAGE"); override != "" { packageName = override }
        fmt.Printf("package: name='%s' versionCode='%s' versionName='%s'\n", packageName, actualCode, actualName)
        return
    }
    if executable == "apksigner.exe" {
        if len(os.Args) != 4 || os.Args[1] != "verify" || os.Args[2] != "--print-certs" { os.Exit(65) }
        switch os.Getenv("CODEXRM_ANDROID_TEST_SIGNATURE") {
        case "unsigned":
            fmt.Fprintln(os.Stderr, "DOES NOT VERIFY")
            os.Exit(1)
        case "missing-certificate":
            return
        }
        fmt.Println("Signer #1 certificate DN: CN=Android Debug, O=Android, C=US")
        fmt.Println("Signer #1 certificate SHA-256 digest: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        return
    }
    if strings.Contains(filepath.Base(os.Args[0]), "codexrm-publish") {
        fmt.Fprintln(log, "publisher")
        if mode == "upload failure" { os.Exit(2) }
        return
    }
    fmt.Fprintln(log, "java:" + strings.Join(os.Args[1:], " "))
    if mode == "concurrent edit" {
        version, err := os.OpenFile(filepath.Join(root, "android", "app", "build.gradle.kts"), os.O_APPEND|os.O_WRONLY, 0600)
        if err != nil { panic(err) }
        fmt.Fprintln(version, "// concurrent edit")
        version.Close()
        os.Exit(2)
    }
    if mode == "build failure" { os.Exit(2) }
    path := filepath.Join(root, "android", "app", "build", "outputs", "apk", "release", "app-release.apk")
    if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil { panic(err) }
    if mode == "package failure" {
        if err := os.WriteFile(path, []byte("plain text is not an APK"), 0600); err != nil { panic(err) }
    } else {
        file, err := os.Create(path)
        if err != nil { panic(err) }
        archive := zip.NewWriter(file)
        for _, entry := range []struct{ name, contents string }{{"AndroidManifest.xml", "binary manifest fixture"}, {"classes.dex", "DEX fixture"}} {
            writer, err := archive.Create(entry.name)
            if err != nil { panic(err) }
            if _, err := writer.Write([]byte(entry.contents)); err != nil { panic(err) }
        }
        if err := archive.Close(); err != nil { panic(err) }
        if err := file.Close(); err != nil { panic(err) }
    }
    fmt.Println("BUILD SUCCESSFUL (fixture)")
}
'@ | Set-Content -LiteralPath $fixtureSource -Encoding UTF8
    & $goCommand build -o $fixtureTool $fixtureSource
    if ($LASTEXITCODE) { throw 'Could not build the fixture executable.' }
    $toolDirectory = Join-Path $testRoot 'sdk\build-tools\36.0.0'
    [void][IO.Directory]::CreateDirectory($toolDirectory)
    Copy-Item -LiteralPath $fixtureTool -Destination (Join-Path $toolDirectory 'aapt2.exe')
    Copy-Item -LiteralPath $fixtureTool -Destination (Join-Path $toolDirectory 'apksigner.exe')
    $env:ANDROID_HOME = Join-Path $testRoot 'sdk'
    $env:ANDROID_SDK_ROOT = ''
    @'
param($Manifest,$RM,$Name,[switch]$ValidateOnly)
$root = $env:CODEXRM_ANDROID_TEST_ROOT
Add-Content -LiteralPath (Join-Path $root 'events.log') -Value ('manifest:' + $Name + ':' + $ValidateOnly) -Encoding UTF8
if ($Name -cne 'android-update.json') { throw 'Incorrect manifest name.' }
if ($ValidateOnly -and $env:CODEXRM_ANDROID_TEST_MODE -eq 'preflight failure') { throw 'Fixture preflight failure.' }
if (-not $ValidateOnly -and $env:CODEXRM_ANDROID_TEST_MODE -eq 'manifest failure') { throw 'Fixture activation failure.' }
'@ | Set-Content -LiteralPath (Join-Path $scriptDir 'publish-manifest.ps1') -Encoding UTF8
    @'
{"cloudreve":{"base":"https://cloud.example","email":"fixture@example.com","password":"fixture-only"},"rm":{"server":"https://rm.example","sshTarget":"fixture-rm","updateDir":"/opt/codexrm/updates"}}
'@ | Set-Content -LiteralPath $config -Encoding UTF8
    $dist = Join-Path $testRoot 'build\dist'
    [void][IO.Directory]::CreateDirectory($dist)
    $windowsManifest = Join-Path $dist 'update.json'
    [IO.File]::WriteAllText($windowsManifest, 'Windows manifest sentinel')

    Reset-Fixture
    & $releaseScript -Config $config -ReleaseNotes 'fixture release'
    $events = @(Get-Events)
    $version = [IO.File]::ReadAllText($versionPath)
    Assert-AndroidRelease ($version.Contains('versionCode = 11') -and $version.Contains('versionName = "1.2.4"')) 'Default Android release did not increment both versions.'
    Assert-AndroidRelease ($events[0] -ceq 'manifest:android-update.json:True' -and $events[1] -ceq 'go') 'Preflight and publisher build did not precede Gradle.'
    Assert-AndroidRelease ($events[2].Contains('testDebugUnitTest assembleRelease -Pcodexrm.testSign')) 'Default release did not run tests and build the signed Release APK.'
    Assert-AndroidRelease ($events[-2] -ceq 'publisher' -and $events[-1] -ceq 'manifest:android-update.json:False') 'Manifest activation did not follow APK upload.'
    $metadata = Get-Content -LiteralPath (Join-Path $dist 'android-update.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-AndroidRelease ($metadata.releaseNotes -ceq 'fixture release' -and $metadata.versionCode -eq 11) 'Release notes/version were not packaged.'
    Assert-AndroidRelease ([IO.File]::ReadAllText($windowsManifest) -ceq 'Windows manifest sentinel') 'Android release modified the Windows manifest.'
    Write-Host 'PASS: Default release bumps only Android, runs tests, packages, uploads, then activates the Android manifest.'

    Reset-Fixture
    $original = [IO.File]::ReadAllBytes($versionPath)
    & $releaseScript -BuildOnly -KeepVersion -Config (Join-Path $testRoot 'missing-config.json') -BuildProxy '127.0.0.1:8848'
    Assert-OriginalVersion $original
    $events = @(Get-Events)
    Assert-AndroidRelease ($events.Count -eq 1 -and $events[0].Contains('-Dhttp.proxyHost=127.0.0.1') -and $events[0].Contains('-Dhttps.proxyPort=8848')) 'Build-only mode performed publication or lost proxy settings.'
    Assert-AndroidRelease (-not $env:BUILD_PROXY) 'Build proxy settings leaked into the caller environment.'
    Write-Host 'PASS: Local builds retain the requested version, accept proxy settings, and require no publication configuration.'

    foreach ($mode in @('build failure', 'package failure')) {
        Reset-Fixture
        $original = [IO.File]::ReadAllBytes($versionPath)
        $env:CODEXRM_ANDROID_TEST_MODE = $mode
        Expect-Failure { & $releaseScript -BuildOnly }
        Assert-OriginalVersion $original
        Assert-AndroidRelease (@(Get-Events | Where-Object { $_ -eq 'publisher' }).Count -eq 0) 'A failed build/package reached publication.'
        Write-Host "PASS: $mode restores the version file including BOM and CRLF."
    }

    Reset-Fixture
    $original = [IO.File]::ReadAllBytes($versionPath)
    $env:CODEXRM_ANDROID_TEST_MODE = 'preflight failure'
    Expect-Failure { & $releaseScript -Config $config }
    Assert-OriginalVersion $original
    Assert-AndroidRelease ((Get-Events).Count -eq 1) 'Failed publication preflight reached a build.'
    Write-Host 'PASS: Invalid publication configuration fails before version edits or builds.'

    foreach ($mode in @('upload failure', 'manifest failure')) {
        Reset-Fixture
        $env:CODEXRM_ANDROID_TEST_MODE = $mode
        Expect-Failure { & $releaseScript -Config $config }
        $builtVersion = [IO.File]::ReadAllBytes($versionPath)
        Assert-AndroidRelease ([IO.File]::ReadAllText($versionPath).Contains('versionCode = 11')) 'Publication failure rolled back a built version.'
        [IO.File]::WriteAllText($logPath, '')
        $env:CODEXRM_ANDROID_TEST_MODE = ''
        & $releaseScript -Config $config -PublishOnly
        Assert-OriginalVersion $builtVersion
        $events = @(Get-Events)
        Assert-AndroidRelease (@($events | Where-Object { $_ -like 'java:*' }).Count -eq 0) 'Publish-only rebuilt the APK.'
        Assert-AndroidRelease ($events[-2] -ceq 'publisher' -and $events[-1] -ceq 'manifest:android-update.json:False') 'Publish-only did not retry publication.'
        Write-Host "PASS: $mode retains the package/version and can retry without another build or bump."
    }

    [IO.File]::AppendAllText((Join-Path $dist 'CodexRM-Android-v1.2.4-11.apk'), 'tampered')
    [IO.File]::WriteAllText($logPath, '')
    Expect-Failure { & $releaseScript -Config $config -PublishOnly }
    Assert-AndroidRelease (@(Get-Events | Where-Object { $_ -eq 'publisher' }).Count -eq 0) 'A tampered package was uploaded.'
    Write-Host 'PASS: Publish-only rejects APK/manifest mismatches before upload.'

    & $releaseScript -BuildOnly -KeepVersion
    foreach ($case in @('wrong package', 'stale code', 'stale name', 'unsigned', 'missing certificate')) {
        $env:CODEXRM_ANDROID_TEST_PACKAGE = ''
        $env:CODEXRM_ANDROID_TEST_CODE = ''
        $env:CODEXRM_ANDROID_TEST_NAME = ''
        $env:CODEXRM_ANDROID_TEST_SIGNATURE = ''
        switch ($case) {
            'wrong package' { $env:CODEXRM_ANDROID_TEST_PACKAGE = 'com.example.other' }
            'stale code' { $env:CODEXRM_ANDROID_TEST_CODE = '10' }
            'stale name' { $env:CODEXRM_ANDROID_TEST_NAME = '1.2.3' }
            'unsigned' { $env:CODEXRM_ANDROID_TEST_SIGNATURE = 'unsigned' }
            'missing certificate' { $env:CODEXRM_ANDROID_TEST_SIGNATURE = 'missing-certificate' }
        }
        [IO.File]::WriteAllText($logPath, '')
        Expect-Failure { & $releaseScript -Config $config -PublishOnly }
        Assert-AndroidRelease (@(Get-Events | Where-Object { $_ -eq 'publisher' }).Count -eq 0) "Publish-only uploaded a $case APK."
    }
    $env:CODEXRM_ANDROID_TEST_PACKAGE = ''
    $env:CODEXRM_ANDROID_TEST_CODE = ''
    $env:CODEXRM_ANDROID_TEST_NAME = ''
    $env:CODEXRM_ANDROID_TEST_SIGNATURE = ''
    Write-Host 'PASS: Publish-only applies package, version and signing-certificate validation before upload.'

    [IO.File]::WriteAllText((Join-Path $dist 'CodexRM-Android-v1.2.4-11.apk'), 'plain text is not an APK')
    [IO.File]::WriteAllText($logPath, '')
    Expect-Failure { & $releaseScript -Config $config -PublishOnly }
    Assert-AndroidRelease (@(Get-Events | Where-Object { $_ -eq 'publisher' }).Count -eq 0) 'Publish-only uploaded plain text.'
    Write-Host 'PASS: Publish-only rejects plain-text APK content before upload.'

    Reset-Fixture
    $env:CODEXRM_ANDROID_TEST_MODE = 'concurrent edit'
    Expect-Failure { & $releaseScript -BuildOnly }
    Assert-AndroidRelease ([IO.File]::ReadAllText($versionPath).Contains('// concurrent edit')) 'Build rollback overwrote a concurrent edit.'
    Write-Host 'PASS: Failed builds preserve concurrent edits to the Android version file.'

    Reset-Fixture
    $original = [IO.File]::ReadAllBytes($versionPath)
    Expect-Failure { & $releaseScript -BuildOnly -PublishOnly }
    Expect-Failure { & $releaseScript -BuildOnly -BuildProxy 'user:password@host:8848' }
    Assert-OriginalVersion $original
    Assert-AndroidRelease ((Get-Events).Count -eq 0) 'Invalid arguments reached a build.'
    Write-Host 'PASS: Conflicting options and unsupported proxy URLs fail without modifying the version.'

    $helpOutput = & (Join-Path $androidRoot 'build.bat') nopause -Help -ReleaseNotes 'notes with spaces' 2>&1
    Assert-AndroidRelease ($LASTEXITCODE -eq 0 -and ($helpOutput -join "`n").Contains('CodexRM Android one-click release')) 'Batch wrapper failed outside its project directory or in a path with spaces/Unicode.'
    Assert-OriginalVersion $original
    Write-Host 'PASS: Batch entry forwards quoted arguments and works in paths with spaces and Unicode.'
} finally {
    foreach ($key in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key], 'Process') }
    $resolvedRoot = [IO.Path]::GetFullPath($testRoot)
    if ($resolvedRoot.StartsWith($tempRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedRoot).StartsWith('CodexRM Android release tests-')) {
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
}
