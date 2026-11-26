param(
    [Parameter(Mandatory=$true)][string]$HostApk,
    [Parameter(Mandatory=$true)][string]$Keystore,
    [Parameter(Mandatory=$true)][string]$Alias,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$PublishConfig = 'build/publish.config.json',
    [switch]$Publish
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $root
try {
    if (!$AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
    if (!$AndroidSdk) { throw 'Set ANDROID_HOME or -AndroidSdk.' }
    $AndroidSdk = (Resolve-Path -LiteralPath $AndroidSdk).Path
    $HostApk = (Resolve-Path -LiteralPath $HostApk).Path
    $Keystore = (Resolve-Path -LiteralPath $Keystore).Path
    if (!$env:CODEXRM_RUNTIME_KS_PASS -or !$env:CODEXRM_RUNTIME_KEY_PASS) {
        throw 'Set CODEXRM_RUNTIME_KS_PASS and CODEXRM_RUNTIME_KEY_PASS in the environment.'
    }
    $work = Join-Path $root '.work/android-local-codex'
    $out = Join-Path $root 'build/dist'
    New-Item -Force -ItemType Directory -Path $work, $out | Out-Null
    $version = [regex]::Match((Get-Content -Raw -Encoding utf8 'android/codex-runtime/build.gradle.kts'), '(?m)^\s*versionName = "([\d.]+)"').Groups[1].Value
    if (!$version) { throw 'Missing standalone runtime version.' }
    $apk = Join-Path $out "CodexRM-Android-Local-Runtime-v$version-arm64.apk"
    $manifest = Join-Path $out 'android-codex-runtime.json'
    $agent = Join-Path $work 'codexrm-local-agent'
    $compiler = Join-Path $AndroidSdk 'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android30-clang.cmd'
    $oldEnv = @{}
    foreach ($name in 'ANDROID_HOME','CGO_ENABLED','GOOS','GOARCH','CC') { $oldEnv[$name] = [Environment]::GetEnvironmentVariable($name) }
    try {
        $env:ANDROID_HOME = $AndroidSdk
        Push-Location android
        try {
            & .\gradlew.bat :codex-runtime:assembleRelease --console=plain
            if ($LASTEXITCODE) { throw 'Unable to build the extension app.' }
        } finally { Pop-Location }
        $env:CGO_ENABLED='1'; $env:GOOS='android'; $env:GOARCH='arm64'; $env:CC=$compiler
        go build -trimpath -buildmode=pie -ldflags '-s -w' -o $agent ./cmd/codexrm-android-local
        if ($LASTEXITCODE) { throw 'Unable to cross-compile the local Agent.' }
    } finally { foreach ($name in $oldEnv.Keys) { [Environment]::SetEnvironmentVariable($name, $oldEnv[$name]) } }
    & python build/scripts/package-android-codex-runtime.py --sdk $AndroidSdk --extension android/codex-runtime/build/outputs/apk/release/codex-runtime-release-unsigned.apk --host $HostApk --output $apk --manifest $manifest --keystore $Keystore --alias $Alias --cache $work --agent $agent
    if ($LASTEXITCODE) { throw 'Unable to package and sign the runtime APK.' }
    if ($Publish) {
        go run ./cmd/codexrm-publish -config $PublishConfig -package $apk -manifest $manifest
        if ($LASTEXITCODE) { throw 'Cloudreve runtime publication failed.' }
        Write-Host "Verified Cloudreve release manifest: $manifest"
        Write-Host 'After smoke testing, use this manifest as android/app/src/main/assets/android-codex-runtime.json in the main APK.'
    }
} finally { Pop-Location }
