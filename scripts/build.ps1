param([switch]$Linux)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    New-Item -ItemType Directory -Force -Path bin | Out-Null
    if ($Linux) {
        $previousOS = $env:GOOS
        $previousArch = $env:GOARCH
        $previousCGO = $env:CGO_ENABLED
        try {
            $env:GOOS = 'linux'; $env:GOARCH = 'amd64'; $env:CGO_ENABLED = '0'
            go build -trimpath -ldflags '-s -w' -o bin/codexrm-server-linux-amd64 ./cmd/codexrm-server
            if ($LASTEXITCODE) { throw 'Linux server build failed' }
            go build -trimpath -ldflags '-s -w' -o bin/codexrm-agent-linux-amd64 ./cmd/codexrm-agent
            if ($LASTEXITCODE) { throw 'Linux agent build failed' }
        } finally {
            $env:GOOS = $previousOS; $env:GOARCH = $previousArch; $env:CGO_ENABLED = $previousCGO
        }
    } else {
        go build -trimpath -ldflags '-s -w' -o bin/codexrm-server.exe ./cmd/codexrm-server
        if ($LASTEXITCODE) { throw 'Server build failed' }
        go build -trimpath -ldflags '-s -w' -o bin/codexrm-agent.exe ./cmd/codexrm-agent
        if ($LASTEXITCODE) { throw 'Agent build failed' }
    }
} finally { Pop-Location }
