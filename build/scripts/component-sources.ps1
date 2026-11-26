# Resolve and build the pinned modules independently of any developer go.work.
$script:CodexRMProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$script:CodexRMComponentModules = @{
    Agent = 'github.com/CyL-Cly/CodexRM-Agent'
    Server = 'github.com/CyL-Cly/CodexRM-Server'
}

function Invoke-CodexRMGo {
    $previousWork = $env:GOWORK
    try {
        $env:GOWORK = 'off'
        & go -C $script:CodexRMProjectRoot @args
    } finally { $env:GOWORK = $previousWork }
}

function Get-CodexRMComponentDir([ValidateSet('Agent', 'Server')][string]$Component) {
    $module = $script:CodexRMComponentModules[$Component]
    $json = Invoke-CodexRMGo list -mod=readonly -m -json $module
    if ($LASTEXITCODE) { throw "Unable to resolve the pinned $Component module in go.mod." }
    $selected = ($json -join "`n") | ConvertFrom-Json
    if ($selected.Path -cne $module -or -not $selected.Version -or $selected.Replace) {
        throw "Pin $Component in go.mod without a replace directive before packaging."
    }
    $json = Invoke-CodexRMGo mod download -json "$module@$($selected.Version)"
    if ($LASTEXITCODE) { throw "Unable to download pinned $module@$($selected.Version)." }
    $download = ($json -join "`n") | ConvertFrom-Json
    if ($download.Path -cne $module -or $download.Version -cne $selected.Version -or
        -not $download.Dir -or -not (Test-Path -LiteralPath $download.Dir -PathType Container)) {
        throw "The pinned $Component sources are missing."
    }
    return $download.Dir
}
