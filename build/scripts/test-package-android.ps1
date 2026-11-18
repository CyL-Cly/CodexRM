$ErrorActionPreference = 'Stop'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $tempRoot ('CodexRM-android-package-tests-' + [Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot)
$savedEnvironment = @{}
foreach ($key in @('ANDROID_HOME','ANDROID_SDK_ROOT','CODEXRM_ANDROID_FIXTURE_MODE','CODEXRM_ANDROID_FIXTURE_CODE','CODEXRM_ANDROID_FIXTURE_NAME')) {
    $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
function Assert-AndroidPackage([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function New-ApkFixture([string]$Path, [switch]$OmitManifest) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        if (-not $OmitManifest) {
            $entry = $archive.CreateEntry('AndroidManifest.xml')
            $writer = New-Object IO.StreamWriter($entry.Open())
            try { $writer.Write('binary Android manifest fixture') } finally { $writer.Dispose() }
        }
        $entry = $archive.CreateEntry('classes.dex')
        $writer = New-Object IO.StreamWriter($entry.Open())
        try { $writer.Write('DEX fixture') } finally { $writer.Dispose() }
    } finally { $archive.Dispose() }
}
function Assert-PackageRejected([string]$ApkPath, [string]$Message) {
    $output = Join-Path $testRoot ([Guid]::NewGuid().ToString('N') + '.apk')
    $failure = $null
    try { & (Join-Path $PSScriptRoot 'package-android.ps1') -ApkPath $ApkPath -OutputPath $output } catch { $failure = $_ }
    Assert-AndroidPackage ($null -ne $failure) $Message
    Assert-AndroidPackage (-not (Test-Path -LiteralPath (Join-Path $testRoot 'android-update.json'))) "$Message A manifest was generated."
}
try {
    $toolDirectory = Join-Path $testRoot 'sdk\build-tools\36.0.0'
    [void][IO.Directory]::CreateDirectory($toolDirectory)
    $aapt = Join-Path $toolDirectory 'aapt2.exe'
    Add-Type -OutputAssembly $aapt -OutputType ConsoleApplication -TypeDefinition @'
using System;
public static class AndroidToolFixture {
    public static int Main(string[] args) {
        string tool = System.IO.Path.GetFileNameWithoutExtension(System.Reflection.Assembly.GetExecutingAssembly().Location);
        string mode = Environment.GetEnvironmentVariable("CODEXRM_ANDROID_FIXTURE_MODE") ?? "";
        if (tool.Equals("aapt2", StringComparison.OrdinalIgnoreCase)) {
            if (args.Length != 3 || args[0] != "dump" || args[1] != "badging") return 64;
            if (mode == "badging-failure") { Console.Error.WriteLine("bad manifest"); return 2; }
            string name = mode == "wrong-package" ? "com.example.other" : "com.codexrm.mobile";
            string code = Environment.GetEnvironmentVariable("CODEXRM_ANDROID_FIXTURE_CODE");
            if (mode == "wrong-version-code") code = (long.Parse(code) + 1).ToString();
            string version = Environment.GetEnvironmentVariable("CODEXRM_ANDROID_FIXTURE_NAME");
            if (mode == "wrong-version-name") version += ".stale";
            Console.WriteLine("package: name='" + name + "' versionCode='" + code + "' versionName='" + version + "'");
            return 0;
        }
        if (args.Length != 3 || args[0] != "verify" || args[1] != "--print-certs") return 65;
        if (mode == "unsigned") { Console.Error.WriteLine("DOES NOT VERIFY"); return 1; }
        if (mode == "missing-certificate") return 0;
        Console.WriteLine("Signer #1 certificate DN: CN=Android Debug, O=Android, C=US");
        Console.WriteLine("Signer #1 certificate SHA-256 digest: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        return 0;
    }
}
'@
    Copy-Item -LiteralPath $aapt -Destination (Join-Path $toolDirectory 'apksigner.exe')
    $env:ANDROID_HOME = Join-Path $testRoot 'sdk'
    $env:ANDROID_SDK_ROOT = ''
    $projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $gradle = [IO.File]::ReadAllText((Join-Path $projectRoot 'android\app\build.gradle.kts'))
    $env:CODEXRM_ANDROID_FIXTURE_CODE = [regex]::Match($gradle, '(?m)^\s*versionCode\s*=\s*(\d+)\s*$').Groups[1].Value
    $env:CODEXRM_ANDROID_FIXTURE_NAME = [regex]::Match($gradle, '(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$').Groups[1].Value
    Assert-AndroidPackage ($env:CODEXRM_ANDROID_FIXTURE_CODE -and $env:CODEXRM_ANDROID_FIXTURE_NAME) 'Could not read the Gradle fixture version.'

    $plainApk = Join-Path $testRoot 'plain.apk'
    [IO.File]::WriteAllBytes($plainApk, [Text.Encoding]::UTF8.GetBytes('CodexRM Android package fixture'))
    Assert-PackageRejected $plainApk 'The original plain-text APK fixture was not rejected.'
    Write-Host 'PASS: Plain text is rejected before APK copying or manifest generation.'

    $notApk = Join-Path $testRoot 'not-an-apk.apk'
    New-ApkFixture $notApk -OmitManifest
    Assert-PackageRejected $notApk 'A ZIP without AndroidManifest.xml was accepted as an APK.'
    Write-Host 'PASS: A generic ZIP is not treated as an APK.'

    $inputApk = Join-Path $testRoot 'input.apk'
    New-ApkFixture $inputApk
    foreach ($mode in @('badging-failure','wrong-package','wrong-version-code','wrong-version-name','unsigned','missing-certificate')) {
        $env:CODEXRM_ANDROID_FIXTURE_MODE = $mode
        Assert-PackageRejected $inputApk "Android validation accepted $mode."
    }
    $env:CODEXRM_ANDROID_FIXTURE_MODE = ''
    Write-Host 'PASS: Bad badging, wrong package/version, unsigned APKs and missing certificates fail closed.'

    $outputApk = Join-Path $testRoot 'CodexRM Android.apk'
    $manifestPath = Join-Path $testRoot 'android-update.json'
    & (Join-Path $PSScriptRoot 'package-android.ps1') -ApkPath $inputApk -OutputPath $outputApk -DownloadUrl 'updates/CodexRM%20Android.apk' -ReleaseNotes 'test'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.versionCode -ne [long]$env:CODEXRM_ANDROID_FIXTURE_CODE -or $manifest.versionName -cne $env:CODEXRM_ANDROID_FIXTURE_NAME) { throw 'Android version was not read from Gradle.' }
    if ($manifest.downloadUrl -cne 'updates/CodexRM%20Android.apk' -or $manifest.releaseNotes -cne 'test') { throw 'Android manifest metadata differs from the package request.' }
    if ($manifest.fileSize -ne (Get-Item -LiteralPath $inputApk).Length) { throw 'Android manifest file size is incorrect.' }
    $expectedHash = (Get-FileHash -LiteralPath $inputApk -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($manifest.sha256 -cne $expectedHash) { throw 'Android manifest SHA-256 is incorrect.' }
    if (-not [Linq.Enumerable]::SequenceEqual([byte[]][IO.File]::ReadAllBytes($inputApk), [byte[]][IO.File]::ReadAllBytes($outputApk))) { throw 'Packaged APK differs from its input.' }
    $env:ANDROID_HOME = ''
    $env:ANDROID_SDK_ROOT = Join-Path $testRoot 'sdk'
    & (Join-Path $PSScriptRoot 'package-android.ps1') -ApkPath $inputApk -OutputPath $outputApk -Server 'https://rm.example/base/'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.downloadUrl -cne 'https://rm.example/base/updates/CodexRM%20Android.apk') { throw 'Default Android package URL was not resolved against the RM base URL.' }
    Write-Host 'PASS: Valid APK metadata, both SDK variables, URLs, size, SHA-256 and copied bytes are correct.'
} finally {
    foreach ($key in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key], 'Process') }
    $resolvedRoot = [IO.Path]::GetFullPath($testRoot)
    if ($resolvedRoot.StartsWith($tempRoot.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedRoot).StartsWith('CodexRM-android-package-tests-')) {
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
}
