param(
    [string]$OutputPath,
    [string]$AgentPath,
    [string]$InnoCompiler
)
$ErrorActionPreference = 'Stop'
$buildRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'component-sources.ps1')
. (Join-Path $PSScriptRoot 'client-install.ps1')
if (-not $AgentPath) { $AgentPath = Join-Path $buildRoot 'bin\codexrm-agent.exe' }
$AgentPath = [IO.Path]::GetFullPath($AgentPath)
if (-not (Test-Path -LiteralPath $AgentPath -PathType Leaf)) {
    throw 'Build the Windows Agent first: build\scripts\build.ps1 -Target Agent'
}
if (-not $InnoCompiler) { $InnoCompiler = $env:INNO_ISCC }
if (-not $InnoCompiler) {
    $cmd = Get-Command ISCC.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { $InnoCompiler = $cmd.Source }
}
if (-not $InnoCompiler) {
    $locations = @($env:ProgramW6432,
        ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA + '\Programs', (Join-Path $buildRoot '.work'))
    foreach ($location in $locations) {
        if (-not $location) { continue }
        $candidate = Join-Path $location 'Inno Setup 7\ISCC.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $InnoCompiler = $candidate
            break
        }
    }
}
if (-not $InnoCompiler -or -not (Test-Path -LiteralPath $InnoCompiler -PathType Leaf)) {
    throw 'Inno Setup 7 compiler (ISCC.exe) not found. Install Inno Setup 7 or set INNO_ISCC to its full path.'
}
$InnoCompiler = [IO.Path]::GetFullPath($InnoCompiler)
$version = Get-CodexRMBinaryVersion $AgentPath
if ([string]$version.versionName -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid Agent version.' }
if (-not $OutputPath) { $OutputPath = Join-Path $buildRoot 'dist\CodexRM-Setup.exe' }
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
if ([IO.Path]::GetExtension($OutputPath) -ine '.exe') { throw 'Setup output must end in .exe.' }
$outputDir = Split-Path -Parent $OutputPath
[void][IO.Directory]::CreateDirectory($outputDir)
$outputName = [IO.Path]::GetFileNameWithoutExtension($OutputPath)
$script = Join-Path (Get-CodexRMComponentDir Agent) 'build/installer/CodexRM.iss'
& $InnoCompiler '/Qp' "/DAgentPath=$AgentPath" "/DAppVersion=$($version.versionName)" "/O$outputDir" "/F$outputName" $script
if ($LASTEXITCODE -or -not (Test-Path -LiteralPath $OutputPath -PathType Leaf)) {
    throw "Inno Setup build failed (exit code $LASTEXITCODE)."
}
Write-Host "Offline installer created: $OutputPath"
