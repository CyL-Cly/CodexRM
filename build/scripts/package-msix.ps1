param(
    [string]$OutputPath,
    [ValidatePattern('^[A-Za-z][A-Za-z0-9.-]{2,49}$')][string]$IdentityName = 'CodexRM.Agent',
    [string]$Publisher = 'CN=CodexRM',
    [string]$CertificateThumbprint,
    [switch]$MachineCertificateStore,
    [string]$TimestampUrl,
    [string]$UpdateBaseUrl,
    [string]$WindowsSdkBin,
    [switch]$NoStartup
)
$ErrorActionPreference = 'Stop'
$buildRoot = Split-Path -Parent $PSScriptRoot
$projectRoot = Split-Path -Parent $buildRoot

. (Join-Path $PSScriptRoot 'component-sources.ps1')

function Find-SdkTool([string]$Name) {
    if ($WindowsSdkBin) {
        $path = Join-Path $WindowsSdkBin $Name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "SDK tool missing: $path" }
        return $path
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $kits = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows Kits\Installed Roots' -ErrorAction SilentlyContinue
    if ($kits.KitsRoot10) {
        $bin = Join-Path $kits.KitsRoot10 'bin'
        foreach ($version in @(Get-ChildItem -LiteralPath $bin -Directory | Where-Object { $_.Name -match '^10\.\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending)) {
            $path = Join-Path $version.FullName ('x64\' + $Name)
            if (Test-Path -LiteralPath $path -PathType Leaf) { return $path }
        }
    }
    throw "Install the Windows 10/11 SDK to obtain $Name, or pass -WindowsSdkBin with its x64 bin directory."
}

function Escape-Xml([string]$Value) { return [Security.SecurityElement]::Escape($Value) }
function Write-Utf8([string]$Path, [string]$Value) { [IO.File]::WriteAllText($Path, $Value, (New-Object Text.UTF8Encoding $false)) }

if ($Publisher -notmatch '^CN=') { throw 'Publisher must be a certificate subject beginning with CN=.' }
if ($CertificateThumbprint -and $CertificateThumbprint -notmatch '^[0-9A-Fa-f]{40}$') { throw 'CertificateThumbprint must contain 40 hexadecimal digits.' }
if ($MachineCertificateStore -and -not $CertificateThumbprint) { throw '-MachineCertificateStore requires -CertificateThumbprint.' }
if ($TimestampUrl -and -not $CertificateThumbprint) { throw '-TimestampUrl requires -CertificateThumbprint.' }
if ($UpdateBaseUrl) {
    $uri = $null
    if (-not [Uri]::TryCreate($UpdateBaseUrl, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ne 'https' -or -not $uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
        throw 'UpdateBaseUrl must be an HTTPS directory URL without credentials, query or fragment.'
    }
    if (-not $CertificateThumbprint) { throw 'An App Installer update feed requires a signed MSIX; pass -CertificateThumbprint.' }
    $UpdateBaseUrl = $uri.AbsoluteUri.TrimEnd('/') + '/'
}
$makeAppx = Find-SdkTool 'makeappx.exe'
$signTool = $null
if ($CertificateThumbprint) {
    $signTool = Find-SdkTool 'signtool.exe'
    $store = if ($MachineCertificateStore) { 'LocalMachine' } else { 'CurrentUser' }
    $certificate = Get-Item -LiteralPath ("Cert:\$store\My\$CertificateThumbprint")
    if (-not $certificate.HasPrivateKey -or $certificate.Subject -cne $Publisher) { throw 'The signing certificate must have a private key and its Subject must exactly match Publisher.' }
    if ($certificate.NotAfter -le (Get-Date) -or $certificate.NotBefore -gt (Get-Date)) { throw 'The signing certificate is not currently valid.' }
}
$source = Get-Content -LiteralPath (Join-Path (Get-CodexRMComponentDir Agent) 'internal/release/release.go') -Raw -Encoding UTF8
$versionMatch = [regex]::Match($source, 'const VersionName = "(\d+\.\d+\.\d+)"')
if (-not $versionMatch.Success) { throw 'Cannot read the release version.' }
$packageVersion = $versionMatch.Groups[1].Value + '.0'
foreach ($part in $packageVersion.Split('.')) { if ([long]$part -gt 65535) { throw 'MSIX version components must fit in 16 bits.' } }
$suffix = if ($CertificateThumbprint) { '' } else { '-unsigned' }
if (-not $OutputPath) { $OutputPath = Join-Path $buildRoot ("dist\CodexRM-Agent-$packageVersion-x64$suffix.msix") }
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
if ([IO.Path]::GetExtension($OutputPath) -ine '.msix') { throw 'OutputPath must end in .msix.' }
[void][IO.Directory]::CreateDirectory((Split-Path -Parent $OutputPath))
$workRoot = Join-Path $buildRoot ('.work\msix-' + [Guid]::NewGuid().ToString('N'))
$stage = Join-Path $workRoot 'package'
[void][IO.Directory]::CreateDirectory((Join-Path $stage 'bin'))
[void][IO.Directory]::CreateDirectory((Join-Path $stage 'Assets'))
$oldOS = $env:GOOS
$oldArch = $env:GOARCH
$oldCGO = $env:CGO_ENABLED
Push-Location $projectRoot
try {
    $env:GOOS = 'windows'
    $env:GOARCH = 'amd64'
    $env:CGO_ENABLED = '0'
    Invoke-CodexRMGo build -mod=readonly -trimpath -ldflags '-s -w -H=windowsgui' -o (Join-Path $stage 'bin\codexrm-agent.exe') ($script:CodexRMComponentModules.Agent + '/cmd/codexrm-agent')
    if ($LASTEXITCODE) { throw 'MSIX Agent build failed.' }
} finally {
    $env:GOOS = $oldOS
    $env:GOARCH = $oldArch
    $env:CGO_ENABLED = $oldCGO
    Pop-Location
}

Add-Type -AssemblyName System.Drawing
$icon = [Drawing.Image]::FromFile((Join-Path (Get-CodexRMComponentDir Server) 'web/icons/icon-192.png'))
try {
    foreach ($size in @(44, 50, 150)) {
        $bitmap = New-Object Drawing.Bitmap $size, $size
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.DrawImage($icon, 0, 0, $size, $size)
            $bitmap.Save((Join-Path $stage ("Assets\Logo$size.png")), [Drawing.Imaging.ImageFormat]::Png)
        } finally { $graphics.Dispose(); $bitmap.Dispose() }
    }
} finally { $icon.Dispose() }

$startup = if ($NoStartup) { '' } else {
@'
      <Extensions>
        <desktop:Extension Category="windows.startupTask" Executable="bin\codexrm-agent.exe" EntryPoint="Windows.FullTrustApplication">
          <desktop:StartupTask TaskId="CodexRMAgentStartup" Enabled="true" DisplayName="CodexRM Agent" />
        </desktop:Extension>
      </Extensions>
'@
}
$manifest = @"
<?xml version="1.0" encoding="utf-8"?>
<Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
         xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
         xmlns:uap10="http://schemas.microsoft.com/appx/manifest/uap/windows10/10"
         xmlns:desktop="http://schemas.microsoft.com/appx/manifest/desktop/windows10"
         xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
         IgnorableNamespaces="uap uap10 desktop rescap">
  <Identity Name="$(Escape-Xml $IdentityName)" Publisher="$(Escape-Xml $Publisher)" Version="$packageVersion" ProcessorArchitecture="x64" />
  <Properties>
    <DisplayName>CodexRM Agent</DisplayName>
    <PublisherDisplayName>CodexRM</PublisherDisplayName>
    <Description>CodexRM native desktop connector</Description>
    <Logo>Assets\Logo50.png</Logo>
  </Properties>
  <Resources><Resource Language="zh-CN" /><Resource Language="en-US" /></Resources>
  <Dependencies><TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.19041.0" MaxVersionTested="10.0.26100.0" /></Dependencies>
  <Applications>
    <Application Id="Agent" Executable="bin\codexrm-agent.exe" uap10:RuntimeBehavior="win32App" uap10:TrustLevel="mediumIL">
      <uap:VisualElements DisplayName="CodexRM Agent" Description="CodexRM native desktop connector" BackgroundColor="transparent" Square150x150Logo="Assets\Logo150.png" Square44x44Logo="Assets\Logo44.png" />
$startup
    </Application>
  </Applications>
  <Capabilities><rescap:Capability Name="runFullTrust" /><rescap:Capability Name="unvirtualizedResources" /></Capabilities>
</Package>
"@
Write-Utf8 (Join-Path $stage 'AppxManifest.xml') $manifest
$packed = Join-Path $workRoot 'CodexRM.msix'
& $makeAppx pack /d $stage /p $packed /o
if ($LASTEXITCODE) { throw 'MakeAppx validation or packaging failed.' }
if ($CertificateThumbprint) {
    $signArguments = @('sign', '/fd', 'SHA256', '/sha1', $CertificateThumbprint)
    if ($MachineCertificateStore) { $signArguments += '/sm' }
    if ($TimestampUrl) { $signArguments += @('/tr', $TimestampUrl, '/td', 'SHA256') }
    & $signTool @signArguments $packed
    if ($LASTEXITCODE) { throw 'MSIX signing failed.' }
    # Export only the public certificate. Private keys stay in the certificate store.
    Export-Certificate -Cert $certificate -FilePath ([IO.Path]::ChangeExtension($OutputPath, '.cer')) -Force | Out-Null
}
Copy-Item -LiteralPath $packed -Destination $OutputPath -Force
if ($UpdateBaseUrl) {
    $installerPath = Join-Path (Split-Path -Parent $OutputPath) 'CodexRM-Agent.appinstaller'
    $packageUrl = $UpdateBaseUrl + [Uri]::EscapeDataString([IO.Path]::GetFileName($OutputPath))
    $installerUrl = $UpdateBaseUrl + 'CodexRM-Agent.appinstaller'
    Write-Utf8 $installerPath @"
<?xml version="1.0" encoding="utf-8"?>
<AppInstaller xmlns="http://schemas.microsoft.com/appx/appinstaller/2018" Version="$packageVersion" Uri="$(Escape-Xml $installerUrl)">
  <MainPackage Name="$(Escape-Xml $IdentityName)" Publisher="$(Escape-Xml $Publisher)" Version="$packageVersion" ProcessorArchitecture="x64" Uri="$(Escape-Xml $packageUrl)" />
  <UpdateSettings><OnLaunch HoursBetweenUpdateChecks="24" ShowPrompt="false" UpdateBlocksActivation="false" /><AutomaticBackgroundTask /></UpdateSettings>
</AppInstaller>
"@
    Write-Host "App Installer feed: $installerPath"
}
Write-Host "MSIX created: $OutputPath"
if (-not $CertificateThumbprint) { Write-Warning 'Unsigned build only. Sign with a trusted code-signing certificate before installing or distributing.' }
Write-Host "Package layout: $stage"
