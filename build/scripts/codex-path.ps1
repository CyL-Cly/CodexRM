function Resolve-CodexDesktopCli {
    param([string]$CodexPath, [string]$PreviousPath)

    if ($CodexPath) {
        if (-not (Test-Path -LiteralPath $CodexPath -PathType Leaf)) {
            throw "Codex CLI not found: $CodexPath"
        }
        return (Resolve-Path -LiteralPath $CodexPath).ProviderPath
    }

    $candidates = New-Object 'System.Collections.Generic.List[string]'
    $runningCliPaths = New-Object 'System.Collections.Generic.List[string]'
    # Desktop releases use either Codex.exe or ChatGPT.exe for the UI.
    foreach ($process in @(Get-Process -Name Codex, ChatGPT -ErrorAction SilentlyContinue)) {
        try {
            $processPath = $process.Path
            if (-not $processPath) { continue }
            if ($processPath -match '\\resources\\codex\.exe$') {
                $runningCliPaths.Add($processPath)
                continue
            }
            $processDir = Split-Path -Parent $processPath
            $candidates.Add((Join-Path $processDir 'resources\codex.exe'))
            $candidates.Add((Join-Path $processDir 'app\resources\codex.exe'))
        } catch {
            # Processes owned by another user may not expose their executable.
        }
    }

    # Prefer the active desktop installation over an older CLI still running after an update.
    foreach ($path in $runningCliPaths) { $candidates.Add($path) }

    try {
        if (Get-Command Get-AppxPackage -ErrorAction SilentlyContinue) {
            foreach ($package in @(Get-AppxPackage -Name 'OpenAI.Codex*' -ErrorAction SilentlyContinue |
                    Sort-Object Version -Descending)) {
                if ($package.InstallLocation) {
                    $candidates.Add((Join-Path $package.InstallLocation 'app\resources\codex.exe'))
                    $candidates.Add((Join-Path $package.InstallLocation 'resources\codex.exe'))
                }
            }
        }
    } catch {
        # Store enumeration can fail in WinRM; continue with the saved path and filesystem candidates.
    }

    # Keep a working last-known path when Store package enumeration is restricted.
    if ($PreviousPath) { $candidates.Add($PreviousPath) }
    foreach ($baseDir in @($env:LOCALAPPDATA, $env:ProgramW6432, $env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if (-not $baseDir) { continue }
        foreach ($relativePath in @('Codex\resources\codex.exe',
                'Programs\Codex\resources\codex.exe', 'Codex\app\resources\codex.exe',
                'Programs\Codex\app\resources\codex.exe')) {
            $candidates.Add((Join-Path $baseDir $relativePath))
        }
    }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf -ErrorAction SilentlyContinue) {
            return (Resolve-Path -LiteralPath $candidate).ProviderPath
        }
    }

    # Store apps may live on a drive other than C:. Do not assume a version number.
    foreach ($drive in @(Get-PSDrive -PSProvider FileSystem)) {
        foreach ($relativeRoot in @('Program Files\WindowsApps', 'WindowsApps')) {
            $packageRoot = Join-Path $drive.Root $relativeRoot
            foreach ($package in @(Get-ChildItem -LiteralPath $packageRoot -Directory `
                    -Filter 'OpenAI.Codex_*' -ErrorAction SilentlyContinue |
                    Sort-Object LastWriteTime -Descending)) {
                foreach ($relativePath in @('app\resources\codex.exe', 'resources\codex.exe')) {
                    $candidate = Join-Path $package.FullName $relativePath
                    if (Test-Path -LiteralPath $candidate -PathType Leaf -ErrorAction SilentlyContinue) {
                        return (Resolve-Path -LiteralPath $candidate).ProviderPath
                    }
                }
            }
        }
    }

    throw 'Codex desktop CLI was not found. Open the Codex desktop app and retry, or pass -CodexPath with its resources\codex.exe path.'
}
