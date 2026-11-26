function Get-ReleaseUpdateLinkerFlags {
    $origin = ([string]$env:CODEXRM_RELEASE_UPDATE_BASE).Trim().TrimEnd('/')
    if (-not $origin) { return '' }
    $uri = $null
    if ($origin -cnotmatch '\Ahttps://[A-Za-z0-9][A-Za-z0-9.-]*(?::[0-9]{1,5})?\z' -or
        -not [Uri]::TryCreate($origin, [UriKind]::Absolute, [ref]$uri) -or $uri.Port -lt 1) {
        throw 'CODEXRM_RELEASE_UPDATE_BASE must be an HTTPS origin without credentials or a path.'
    }
    return " -X github.com/CyL-Cly/CodexRM-Server/release.updateBaseURL=$origin"
}

function Assert-ReleaseOriginEmbedded([string]$Binary) {
    $origin = ([string]$env:CODEXRM_RELEASE_UPDATE_BASE).Trim().TrimEnd('/')
    if ($origin -and -not [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($Binary)).Contains($origin)) {
        throw "Release update origin is missing from $([IO.Path]::GetFileName($Binary))."
    }
}
