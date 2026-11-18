param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$buildRoot = Split-Path -Parent $PSScriptRoot
$root = Split-Path -Parent $buildRoot
$webRoot = [IO.Path]::GetFullPath((Join-Path $root 'web'))
Push-Location $root
try {
    & (Join-Path $PSScriptRoot 'build.ps1')
    $demoDir = Join-Path $buildRoot '.state/demo'
    New-Item -ItemType Directory -Force -Path $demoDir | Out-Null
    $serverArgs = '--addr 127.0.0.1:{0} --data "{1}" --web "{2}"' -f $Port, (Join-Path $demoDir 'server'), $webRoot
    $server = Start-Process -FilePath (Join-Path $buildRoot 'bin/codexrm-server.exe') -ArgumentList $serverArgs -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $demoDir 'server.out.log') -RedirectStandardError (Join-Path $demoDir 'server.err.log') -PassThru
    try {
        $url = "http://127.0.0.1:$Port"
        $ready = $false
        for ($attempt = 0; $attempt -lt 40; $attempt++) {
            if ($server.HasExited) { throw 'Server exited; check .state/demo/server.err.log' }
            try { Invoke-RestMethod "$url/api/v1/health" | Out-Null; $ready = $true; break } catch { Start-Sleep -Milliseconds 250 }
        }
        if (-not $ready) { throw 'Server startup timed out' }
        . (Join-Path $PSScriptRoot 'client-install.ps1')
        $agentData = Join-Path $demoDir 'agent'
        $agentBinary = Join-Path $buildRoot 'bin/codexrm-agent.exe'
        $agentArgs = @('--server', $url, '--mock', '--data', $agentData)
        $agent = Start-Process -FilePath $agentBinary -ArgumentList (ConvertTo-CodexRMCommandLine $agentArgs) `
            -WorkingDirectory $root -WindowStyle Hidden -PassThru
        try {
            $credentials = Join-Path $agentData 'agent.json'
            for ($attempt = 0; $attempt -lt 40 -and -not (Test-Path -LiteralPath $credentials -PathType Leaf); $attempt++) {
                if ($agent.HasExited) { throw "Agent exited: $($agent.ExitCode)" }
                Start-Sleep -Milliseconds 250
            }
            $pairOutput = Invoke-CodexRMAgentCommand -Binary $agentBinary -Arguments ($agentArgs + '--pair')
            Write-Host $pairOutput.TrimEnd()
            Write-Host "Open $url and enter the pairing code above. Use the CodexRM tray icon to stop the agent."
            $agent.WaitForExit()
            if ($agent.ExitCode) { throw "Agent exited: $($agent.ExitCode)" }
        } finally {
            if (-not $agent.HasExited) { Stop-Process -Id $agent.Id }
            if (-not $server.HasExited) { Stop-Process -Id $server.Id }
        }
    } finally { if (-not $server.HasExited) { Stop-Process -Id $server.Id } }
} finally { Pop-Location }
