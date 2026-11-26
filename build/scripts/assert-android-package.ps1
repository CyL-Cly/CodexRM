param(
    [Parameter(Mandatory=$true)][string]$ApkPath,
    [Parameter(Mandatory=$true)][long]$ExpectedVersionCode,
    [Parameter(Mandatory=$true)][string]$ExpectedVersionName,
    [string]$ExpectedUpdateOrigin
)
$ErrorActionPreference = 'Stop'
$buildRoot = Split-Path -Parent $PSScriptRoot
$projectRoot = Split-Path -Parent $buildRoot

function Resolve-CodexRMAndroidTools {
    $sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, 'E:\app\android_studio_sdk')
    if ($env:LOCALAPPDATA) { $sdkCandidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    foreach ($sdk in $sdkCandidates) {
        if (-not $sdk) { continue }
        $buildTools = Join-Path $sdk 'build-tools'
        if (-not (Test-Path -LiteralPath $buildTools -PathType Container)) { continue }
        $toolsets = @()
        foreach ($directory in @(Get-ChildItem -LiteralPath $buildTools -Directory)) {
            $version = $null
            if ([version]::TryParse($directory.Name, [ref]$version)) {
                $toolsets += [pscustomobject]@{ Version = $version; Directory = $directory.FullName }
            }
        }
        foreach ($toolset in @($toolsets | Sort-Object Version -Descending)) {
            $aapt2 = $null
            $apksigner = $null
            foreach ($name in @('aapt2.exe', 'aapt2')) {
                $candidate = Join-Path $toolset.Directory $name
                if (Test-Path -LiteralPath $candidate -PathType Leaf) { $aapt2 = $candidate; break }
            }
            foreach ($name in @('apksigner.bat', 'apksigner.cmd', 'apksigner.exe', 'apksigner')) {
                $candidate = Join-Path $toolset.Directory $name
                if (Test-Path -LiteralPath $candidate -PathType Leaf) { $apksigner = $candidate; break }
            }
            if ($aapt2 -and $apksigner) {
                return [pscustomobject]@{ Aapt2 = $aapt2; ApkSigner = $apksigner }
            }
        }
    }
    throw 'Android SDK build-tools with aapt2 and apksigner were not found. Set ANDROID_HOME or ANDROID_SDK_ROOT.'
}

function Invoke-CodexRMAndroidTool([string]$Tool, [string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    try {
        # apksigner can emit harmless JVM warnings on stderr under Windows PowerShell.
        $ErrorActionPreference = 'Continue'
        $output = & $Tool @Arguments 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode) {
        throw "Android validation tool failed (exit $exitCode): $([IO.Path]::GetFileName($Tool)): $($output.Trim())"
    }
    return $output
}

$ApkPath = [IO.Path]::GetFullPath($ApkPath)
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf) -or [IO.Path]::GetExtension($ApkPath) -ine '.apk') {
    throw "APK not found: $ApkPath"
}
$apk = Get-Item -LiteralPath $ApkPath
if ($apk.Length -le 0 -or $apk.Length -gt 512MB) { throw 'APK size must be between 1 byte and 512 MiB.' }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = $null
try {
    $archive = [IO.Compression.ZipFile]::OpenRead($ApkPath)
    $manifestEntries = @($archive.Entries | Where-Object { $_.FullName.Replace('\', '/') -ceq 'AndroidManifest.xml' })
    if ($manifestEntries.Count -ne 1 -or $manifestEntries[0].Length -le 0) {
        throw 'The APK ZIP does not contain exactly one non-empty AndroidManifest.xml.'
    }
    if ($ExpectedUpdateOrigin) {
        # DEX strings store their UTF-16 length as ULEB128, followed by data and a NUL.
        # Origins are ASCII, so matching the full string avoids accepting a URL prefix.
        $encoding = [Text.Encoding]::GetEncoding(28591)
        $prefix = [Text.StringBuilder]::new()
        $length = $ExpectedUpdateOrigin.Length
        while ($length -ge 128) {
            [void]$prefix.Append([char](($length -band 127) -bor 128))
            $length = $length -shr 7
        }
        [void]$prefix.Append([char]$length)
        $needle = $prefix.ToString() + $ExpectedUpdateOrigin + "`0"
        $found = $false
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -cnotmatch '^classes(\d+)?\.dex$') { continue }
            $reader = [IO.StreamReader]::new($entry.Open(), $encoding, $false)
            try {
                if ($reader.ReadToEnd().Contains($needle)) { $found = $true; break }
            } finally { $reader.Dispose() }
        }
        if (-not $found) { throw 'The expected default update origin is missing from the Release APK.' }
    }
} catch {
    throw "APK is not a valid ZIP/Android package: $ApkPath. $($_.Exception.Message)"
} finally {
    if ($archive) { $archive.Dispose() }
}

$tools = Resolve-CodexRMAndroidTools
$badging = Invoke-CodexRMAndroidTool -Tool $tools.Aapt2 -Arguments @('dump', 'badging', $ApkPath)
$package = [regex]::Match($badging, "(?m)^package:\s+name='([^']+)'\s+versionCode='(\d+)'\s+versionName='([^']*)'")
if (-not $package.Success) { throw 'aapt2 did not return APK package/version badging.' }
if ($package.Groups[1].Value -cne 'com.codexrm.mobile') {
    throw "Unexpected Android package: $($package.Groups[1].Value). Expected com.codexrm.mobile."
}
if ([long]$package.Groups[2].Value -ne $ExpectedVersionCode -or $package.Groups[3].Value -cne $ExpectedVersionName) {
    throw "APK version $($package.Groups[3].Value) ($($package.Groups[2].Value)) does not match Gradle $ExpectedVersionName ($ExpectedVersionCode)."
}

$signature = Invoke-CodexRMAndroidTool -Tool $tools.ApkSigner -Arguments @('verify', '--print-certs', $ApkPath)
$hasCertificate = [regex]::IsMatch($signature, '(?m)^Signer #\d+ certificate DN:\s*\S.*\r?$') -and
    [regex]::IsMatch($signature, '(?m)^Signer #\d+ certificate SHA-256 digest:\s*[0-9a-fA-F]{64}\s*$')
if (-not $hasCertificate) { throw 'APK signature verification did not report a signing certificate and SHA-256 digest.' }
