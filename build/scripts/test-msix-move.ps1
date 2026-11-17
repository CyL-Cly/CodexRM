param(
    [string]$TargetDrive,
    [string]$EvidenceDir
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $EvidenceDir) { $EvidenceDir = Join-Path $projectRoot ('build\.work\msix-move-test-' + [Guid]::NewGuid().ToString('N')) }
$EvidenceDir = [IO.Path]::GetFullPath($EvidenceDir)
[void][IO.Directory]::CreateDirectory($EvidenceDir)
$certificate = $null
$package = $null
$identity = 'CodexRM.MoveTest' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$running = $null
$resultPath = Join-Path $EvidenceDir 'result.json'
function Write-Result($Value) { $Value | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultPath -Encoding UTF8 }
function Assert-Move([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Read-AgentLog([string]$Path) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    $reader = New-Object IO.StreamReader $stream
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}
function Read-InstallationInfo($Installed, [string]$Phase) {
    $out = Join-Path $EvidenceDir ($Phase + '-info.json')
    $err = Join-Path $EvidenceDir ($Phase + '-info-error.txt')
    $exe = Join-Path $Installed.InstallLocation 'bin\codexrm-agent.exe'
    $process = Start-Process -FilePath $exe -ArgumentList '--installation-info' -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    Assert-Move ($process.ExitCode -eq 0) ('Packaged diagnostics failed: ' + [IO.File]::ReadAllText($err))
    $info = Get-Content -LiteralPath $out -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Move ($info.packageFamilyName -eq $Installed.PackageFamilyName) 'The executable did not receive its package identity.'
    Assert-Move ($info.configPath -like '*\LocalState\connection.json') 'Packaged settings are inside the installation directory.'
    return $info
}
function Stop-TestAgent($Process, [string]$StateDir) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($StateDir.ToLowerInvariant())) } finally { $sha.Dispose() }
    $name = 'Local\CodexRM-' + ([BitConverter]::ToString($hash[0..15]).Replace('-', '').ToLowerInvariant()) + '-stop'
    $event = [Threading.EventWaitHandle]::OpenExisting($name)
    try { [void]$event.Set() } finally { $event.Dispose() }
    Assert-Move ($Process.WaitForExit(15000)) 'The packaged Agent did not shut down gracefully.'
    Assert-Move ($Process.ExitCode -eq 0) 'The packaged Agent exited with an error.'
}
try {
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    Assert-Move ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) 'Run this integration test as administrator; its temporary signing certificate must be trusted by Windows.'
    $systemVolume = Get-AppxVolume | Where-Object { $_.IsSystemVolume -and -not $_.IsOffline } | Select-Object -First 1
    $targetVolume = Get-AppxVolume | Where-Object { -not $_.IsSystemVolume -and -not $_.IsOffline -and (-not $TargetDrive -or $_.PackageStorePath.StartsWith($TargetDrive, [StringComparison]::OrdinalIgnoreCase)) } | Select-Object -First 1
    Assert-Move ($null -ne $systemVolume -and $null -ne $targetVolume) 'An existing online package volume on a second drive is required.'
    $subject = 'CN=' + $identity
    $certificate = New-SelfSignedCertificate -Type Custom -KeyUsage DigitalSignature -CertStoreLocation 'Cert:\CurrentUser\My' -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3', '2.5.29.19={text}') -Subject $subject -FriendlyName 'CodexRM temporary MSIX integration test' -NotAfter (Get-Date).AddDays(1)
    $archive = Join-Path $EvidenceDir 'CodexRM-test.msix'
    & (Join-Path $PSScriptRoot 'package-msix.ps1') -IdentityName $identity -Publisher $subject -CertificateThumbprint $certificate.Thumbprint -OutputPath $archive -UpdateBaseUrl 'https://updates.example.test/native/' -NoStartup
    Import-Certificate -FilePath ([IO.Path]::ChangeExtension($archive, '.cer')) -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null
    Add-AppxPackage -Path $archive -Volume $systemVolume
    $package = Get-AppxPackage -Name $identity
    $before = Read-InstallationInfo $package 'before'
    $state = Split-Path -Parent $before.configPath
    [void][IO.Directory]::CreateDirectory($state)
    $config = '{"server":"http://127.0.0.1:1","enrollmentKey":"move-test-only","autoUpdate":true,"updateServer":"https://updates.example.test/"}'
    [IO.File]::WriteAllText($before.configPath, $config)
    $locations = @((Join-Path $systemVolume.PackageStorePath $package.PackageFullName))
    foreach ($phase in @('before', 'after')) {
        if ($phase -eq 'after') {
            Move-AppxPackage -Package $package.PackageFullName -Volume $targetVolume
            $package = Get-AppxPackage -Name $identity
            $after = Read-InstallationInfo $package 'after'
            Assert-Move ($after.configPath -eq $before.configPath) 'The settings path changed after moving.'
            # Windows may retain an installation mount point on the system
            # drive. Query the owning volume and its physical payload instead.
            $moved = Get-AppxPackage -Name $identity -Volume $targetVolume
            Assert-Move ($null -ne $moved -and $moved.PackageFullName -eq $package.PackageFullName) 'Windows did not register the package on the requested volume.'
            $physical = Join-Path $targetVolume.PackageStorePath $package.PackageFullName
            Assert-Move (Test-Path -LiteralPath (Join-Path $physical 'bin\codexrm-agent.exe') -PathType Leaf) 'The native executable is missing from the target volume.'
            $locations += $physical
        }
        Assert-Move ([IO.File]::ReadAllText($before.configPath) -ceq $config) 'Connection settings changed during the move.'
        $exe = Join-Path $package.InstallLocation 'bin\codexrm-agent.exe'
        $agentArguments = '--mock --data "' + (Join-Path $EvidenceDir 'agent-data') + '"'
        $running = Start-Process -FilePath $exe -ArgumentList $agentArguments -PassThru -WindowStyle Hidden
        $logPath = Join-Path $state 'agent.log'
        $ready = $false
        for ($attempt = 0; $attempt -lt 100; $attempt++) {
            if ($running.HasExited) { throw "The packaged Agent exited $($running.ExitCode)." }
            if ((Test-Path -LiteralPath $logPath) -and (Read-AgentLog $logPath).Contains('tray connector started')) { $ready = $true; break }
            Start-Sleep -Milliseconds 100
        }
        Assert-Move $ready 'The packaged Agent did not write its runtime log.'
        Stop-TestAgent $running $state
        $running = $null
        # Copy the text rather than EFS attributes from the Windows-managed
        # data volume into the evidence directory.
        [IO.File]::WriteAllText((Join-Path $EvidenceDir ($phase + '-agent.log')), (Read-AgentLog $logPath))
        Remove-Item -LiteralPath $logPath
    }
    $rejected = Start-Process -FilePath $exe -ArgumentList '--install-update --update-dir X:\must-not-be-created' -Wait -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $EvidenceDir 'updater-guard.txt')
    Assert-Move ($rejected.ExitCode -ne 0) 'The classic EXE updater ran inside the MSIX package.'
    Assert-Move ([IO.File]::ReadAllText((Join-Path $EvidenceDir 'updater-guard.txt')).Contains('MSIX')) 'The EXE updater failed for an unexpected reason.'
    Write-Result @{ passed = $true; identity = $package.PackageFamilyName; locations = $locations; configPath = $before.configPath; statePreserved = $true; nativeAgentStartedBeforeAndAfterMove = $true; classicUpdaterRejected = $true }
    Write-Host 'PASS: Windows moved the native MSIX to another drive; settings, launch and shutdown survived.'
} catch {
    Write-Result @{ passed = $false; error = $_.Exception.Message; detail = $_.ScriptStackTrace }
    throw
} finally {
    if ($running -and -not $running.HasExited) { Stop-Process -Id $running.Id -ErrorAction SilentlyContinue }
    Get-AppxPackage -Name $identity | Remove-AppxPackage -ErrorAction Continue
    if ($certificate) {
        Remove-Item -LiteralPath ('Cert:\LocalMachine\TrustedPeople\' + $certificate.Thumbprint) -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath ('Cert:\CurrentUser\My\' + $certificate.Thumbprint) -ErrorAction SilentlyContinue
    }
    Write-Host "Evidence: $EvidenceDir"
}
