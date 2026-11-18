param(
    [Parameter(Mandatory=$true)][string]$ApkPath,
    [string]$OutputPath,
    [string]$Server,
    [string]$DownloadUrl,
    [string]$ReleaseNotes = ''
)
$ErrorActionPreference = 'Stop'
$buildRoot = Split-Path -Parent $PSScriptRoot
$projectRoot = Split-Path -Parent $buildRoot
$ApkPath = [IO.Path]::GetFullPath($ApkPath)
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf) -or [IO.Path]::GetExtension($ApkPath) -ine '.apk') {
    throw "Signed APK not found: $ApkPath"
}
$gradleText = [IO.File]::ReadAllText((Join-Path $projectRoot 'android\app\build.gradle.kts'), [Text.Encoding]::UTF8)
$codeMatch = [regex]::Match($gradleText, '(?m)^\s*versionCode\s*=\s*(\d+)\s*$')
$nameMatch = [regex]::Match($gradleText, '(?m)^\s*versionName\s*=\s*"([^"\r\n]+)"\s*$')
if (-not $codeMatch.Success -or -not $nameMatch.Success) { throw 'Unsupported Android version format.' }
$versionCode = [long]$codeMatch.Groups[1].Value
$versionName = $nameMatch.Groups[1].Value
& (Join-Path $PSScriptRoot 'assert-android-package.ps1') -ApkPath $ApkPath -ExpectedVersionCode $versionCode -ExpectedVersionName $versionName
if (-not $OutputPath) {
    $OutputPath = Join-Path $buildRoot "dist\CodexRM-Android-v$versionName-$versionCode.apk"
}
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
[void][IO.Directory]::CreateDirectory((Split-Path -Parent $OutputPath))
if (-not $ApkPath.Equals($OutputPath, [StringComparison]::OrdinalIgnoreCase)) {
    Copy-Item -LiteralPath $ApkPath -Destination $OutputPath -Force
}
$apk = Get-Item -LiteralPath $OutputPath
if ($apk.Length -le 0 -or $apk.Length -gt 512MB) { throw 'APK size must be between 1 byte and 512 MiB.' }
$packageName = [Uri]::EscapeDataString($apk.Name)
if (-not $DownloadUrl) {
    $DownloadUrl = 'updates/' + $packageName
    if ($Server) { $DownloadUrl = $Server.TrimEnd('/') + '/' + $DownloadUrl }
}
$manifest = [ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    downloadUrl = $DownloadUrl
    releaseNotes = $ReleaseNotes
    fileSize = $apk.Length
    sha256 = (Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash.ToLowerInvariant()
}
$manifestPath = Join-Path $apk.DirectoryName 'android-update.json'
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))
Write-Host "Android package prepared: $OutputPath"
Write-Host "Android update manifest created: $manifestPath"
Write-Host 'Publish only a signed APK whose signing certificate matches installed copies.'
