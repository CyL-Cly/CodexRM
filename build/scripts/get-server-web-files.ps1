param([Parameter(Mandatory=$true)][string]$WebDir)

$ErrorActionPreference = 'Stop'
$files = @('index.html', 'app.js', 'styles.css', 'dsh-theme.css', 'dsh-LICENSE.txt', 'pwa.js', 'sw.js', 'theme.js', 'manifest.webmanifest')
foreach ($file in @($files + @('icons/icon.svg', 'icons/icon-192.png', 'icons/icon-512.png', 'icons/maskable-512.png'))) {
    if (-not (Test-Path -LiteralPath (Join-Path $WebDir $file) -PathType Leaf)) { throw "Frontend asset missing: $file" }
}
$files += @(Get-ChildItem -LiteralPath (Join-Path $WebDir 'icons') -Recurse -File | ForEach-Object {
    $_.FullName.Substring($WebDir.TrimEnd('\', '/').Length + 1).Replace('\', '/')
})
$files
