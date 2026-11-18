param(
 [string]$Sdk = 'E:/app/android_studio_sdk',
 [string]$Jdk = 'E:/app/android-studio/jbr',
 [string]$Output = '../../../.work/p2p-20261118/android-build'
)
$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path -Parent $PSScriptRoot
if (-not [IO.Path]::IsPathRooted($Output)) { $Output = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $Output)) }
New-Item -ItemType Directory -Force -Path $Output, "$Output/classes", "$Output/dex", "$Output/lib/arm64-v8a" | Out-Null
$buildTools = Join-Path $Sdk 'build-tools/36.0.0'
$androidJar = Join-Path $Sdk 'platforms/android-36/android.jar'
$ndkBin = Join-Path $Sdk 'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin'
function Check([string]$stage) { if ($LASTEXITCODE -ne 0) { throw "$stage failed: $LASTEXITCODE" } }
$prior = @{}
foreach ($name in @('GOOS','GOARCH','CGO_ENABLED','CC','CGO_LDFLAGS')) { $prior[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
 $env:GOOS = 'android'; $env:GOARCH = 'arm64'; $env:CGO_ENABLED = '1'
 $env:CC = Join-Path $ndkBin 'aarch64-linux-android28-clang.cmd'
 $env:CGO_LDFLAGS = '-Wl,-z,max-page-size=16384'
 Push-Location $moduleRoot
 try { & go build -buildmode=c-shared -trimpath -ldflags '-s -w' -o "$Output/lib/arm64-v8a/libp2pprobe.so" ./cmd/mobile; Check 'Go native library' } finally { Pop-Location }
} finally { foreach ($name in $prior.Keys) { [Environment]::SetEnvironmentVariable($name,$prior[$name],'Process') } }
& "$Jdk/bin/javac.exe" --release 8 -classpath $androidJar -d "$Output/classes" "$moduleRoot/android/MainActivity.java"; Check 'javac'
$classFiles = @(Get-ChildItem -LiteralPath "$Output/classes" -Recurse -Filter '*.class' | ForEach-Object FullName)
& "$buildTools/d8.bat" --release --min-api 28 --lib $androidJar --output "$Output/dex" @classFiles; Check 'd8'
& "$buildTools/aapt2.exe" link -o "$Output/unsigned.apk" -I $androidJar --manifest "$moduleRoot/android/AndroidManifest.xml" --min-sdk-version 28 --target-sdk-version 35; Check 'aapt2'
& python "$PSScriptRoot/package-apk.py" "$Output/unsigned.apk" "$Output/dex/classes.dex" "$Output/lib/arm64-v8a/libp2pprobe.so"; Check 'package APK'
& "$buildTools/zipalign.exe" -f -p 4 "$Output/unsigned.apk" "$Output/aligned.apk"; Check 'zipalign'
$debugKeystore = Join-Path $env:USERPROFILE '.android/debug.keystore'
& "$buildTools/apksigner.bat" sign --ks $debugKeystore --ks-pass pass:android --key-pass pass:android --out "$Output/p2p-probe.apk" "$Output/aligned.apk"; Check 'apksigner'
Get-Item -LiteralPath "$Output/p2p-probe.apk" | Select-Object FullName,Length
