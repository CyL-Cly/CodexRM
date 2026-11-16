param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & (Join-Path $PSScriptRoot 'build.ps1')
    $demoDir = Join-Path $root '.state/demo'
    New-Item -ItemType Directory -Force -Path $demoDir | Out-Null
    $serverArgs = '--addr 127.0.0.1:{0} --data "{1}"' -f $Port, (Join-Path $demoDir 'server')
    $server = Start-Process -FilePath (Join-Path $root 'bin/codexrm-server.exe') -ArgumentList $serverArgs -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $demoDir 'server.out.log') -RedirectStandardError (Join-Path $demoDir 'server.err.log') -PassThru
    try {
        $url = "http://127.0.0.1:$Port"
        $ready = $false
        for ($attempt = 0; $attempt -lt 40; $attempt++) {
            if ($server.HasExited) { throw 'Server exited; check .state/demo/server.err.log' }
            try { Invoke-RestMethod "$url/api/v1/health" | Out-Null; $ready = $true; break } catch { Start-Sleep -Milliseconds 250 }
        }
        if (-not $ready) { throw 'Server startup timed out' }
        Write-Host "Open $url and enter the pairing code below. Press Ctrl+C to stop."
        & (Join-Path $root 'bin/codexrm-agent.exe') --server $url --mock --data (Join-Path $demoDir 'agent')
    } finally { if (-not $server.HasExited) { Stop-Process -Id $server.Id } }
} finally { Pop-Location }
