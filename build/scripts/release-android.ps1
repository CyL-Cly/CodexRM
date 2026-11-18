param(
    [Parameter(Position=0)][ValidateSet('nopause')][string]$PauseMode,
    [switch]$BuildOnly,
    [switch]$KeepVersion,
    [switch]$PublishOnly,
    [string]$ReleaseNotes = '',
    [string]$Config,
    [string]$OutputDirectory,
    [string]$JavaExe = $env:JAVA_EXE,
    [string]$BuildProxy = $env:BUILD_PROXY,
    [switch]$Help
)
$ErrorActionPreference = 'Stop'
if ($Help) {
    Write-Host @'
CodexRM Android one-click release
  android\build.bat [nopause] [options]

Default: bump Android version, run tests, build a signed Release APK,
         upload to Cloudreve, then publish RM android-update.json.
  nopause             Skip the final pause (must be the first argument).
  -BuildOnly          Build/package locally without uploading.
  -KeepVersion        Build the current version without incrementing it.
  -PublishOnly        Retry publication of the current package, without rebuilding.
  -ReleaseNotes TEXT  Update notes stored in android-update.json.
  -Config PATH        Default: build\publish.config.json.
  -OutputDirectory D  Default: build\dist.
  -JavaExe PATH       Java executable; also accepts the JAVA_EXE environment variable.
  -BuildProxy HOST:P  Optional build proxy; also accepts BUILD_PROXY. Use direct to bypass it.
  -Help               Print this help without building or publishing.

Signing uses the existing local debug certificate (-Pcodexrm.testSign),
matching the APKs currently distributed by this project.
'@
    return
}
if ($BuildOnly -and $PublishOnly) { throw '-BuildOnly and -PublishOnly cannot be combined.' }
$buildRoot = Split-Path -Parent $PSScriptRoot
$projectRoot = Split-Path -Parent $buildRoot
$androidRoot = Join-Path $projectRoot 'android'
$versionPath = Join-Path $androidRoot 'app\build.gradle.kts'
$buildLog = Join-Path $androidRoot 'build.log'
if (-not $Config) { $Config = Join-Path $buildRoot 'publish.config.json' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $buildRoot 'dist' }
$Config = [IO.Path]::GetFullPath($Config)
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)

function Assert-AndroidPackage($Apk, $Manifest, [long]$Code, [string]$Name) {
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf) -or -not (Test-Path -LiteralPath $Manifest -PathType Leaf)) {
        throw 'No APK/android-update.json for this version. Build the package first.'
    }
    & (Join-Path $PSScriptRoot 'assert-android-package.ps1') -ApkPath $Apk -ExpectedVersionCode $Code -ExpectedVersionName $Name
    $metadata = Get-Content -LiteralPath $Manifest -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($metadata.versionCode -ne $Code -or $metadata.versionName -cne $Name -or
        $metadata.fileSize -ne (Get-Item -LiteralPath $Apk).Length -or
        $metadata.sha256 -ine (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash) {
        throw 'APK/manifest version, size or SHA-256 mismatch. Rebuild before publishing.'
    }
}

$lock = $null
$originalVersion = $null
$changedVersion = $null
$builtPackage = $false
Push-Location $projectRoot
try {
    $binDirectory = Join-Path $buildRoot 'bin'
    [void][IO.Directory]::CreateDirectory($binDirectory)
    try {
        $lock = [IO.File]::Open((Join-Path $binDirectory '.android-release.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    } catch { throw 'Another Android release is running, or the release lock cannot be opened.' }

    if (-not $BuildOnly) {
        if (-not (Test-Path -LiteralPath $Config -PathType Leaf)) { throw 'Configure build/publish.config.json first, or use -BuildOnly.' }
        $publishConfig = Get-Content -LiteralPath $Config -Raw -Encoding UTF8 | ConvertFrom-Json
        if (-not $publishConfig.cloudreve.base -or -not $publishConfig.cloudreve.email -or
            (-not $publishConfig.cloudreve.password -and -not $env:CLOUDREVE_PASSWORD)) {
            throw 'Configure Cloudreve base/email/password (or CLOUDREVE_PASSWORD) first.'
        }
        & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -RM $publishConfig.rm -Name 'android-update.json' -ValidateOnly
        if (-not (Get-Command go -ErrorAction SilentlyContinue)) { throw 'Go is required to build the publisher. Add it to PATH or use -BuildOnly.' }
    }

    $originalVersion = [IO.File]::ReadAllBytes($versionPath)
    $text = [Text.Encoding]::UTF8.GetString($originalVersion)
    $codePattern = '(?m)^(\s*versionCode\s*=\s*)(\d+)([ \t]*\r?)$'
    $namePattern = '(?m)^(\s*versionName\s*=\s*")(\d+(?:\.\d+)+)("[ \t]*\r?)$'
    $codeMatches = [regex]::Matches($text, $codePattern)
    $nameMatches = [regex]::Matches($text, $namePattern)
    if ($codeMatches.Count -ne 1 -or $nameMatches.Count -ne 1) { throw 'Expected one numeric versionCode and one dotted numeric versionName in Android Gradle configuration.' }
    $code = [long]$codeMatches[0].Groups[2].Value
    $name = $nameMatches[0].Groups[2].Value
    $oldName = $name
    $oldCode = $code
    if (-not $PublishOnly -and -not $KeepVersion) {
        if ($code -ge 2100000000) { throw 'Android versionCode has reached its maximum.' }
        $code++
        $parts = $name.Split('.')
        $parts[$parts.Length - 1] = [string]([long]$parts[-1] + 1)
        $name = $parts -join '.'
        $text = [regex]::Replace($text, $codePattern, { param($m) $m.Groups[1].Value + $code + $m.Groups[3].Value })
        $text = [regex]::Replace($text, $namePattern, { param($m) $m.Groups[1].Value + $name + $m.Groups[3].Value })
    }
    $apk = Join-Path $OutputDirectory "CodexRM-Android-v$name-$code.apk"
    $manifest = Join-Path $OutputDirectory 'android-update.json'

    if ($PublishOnly) {
        Assert-AndroidPackage $apk $manifest $code $name
        $builtPackage = $true
    } else {
        if ($JavaExe) {
            $javaCommand = Get-Command $JavaExe -CommandType Application -ErrorAction SilentlyContinue
            if (-not $javaCommand) { throw 'JAVA_EXE/-JavaExe does not point to an existing Java executable.' }
            $JavaExe = $javaCommand.Source
        } else {
            $candidates = @()
            if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin\java.exe' }
            if ($env:ANDROID_STUDIO_HOME) { $candidates += Join-Path $env:ANDROID_STUDIO_HOME 'jbr\bin\java.exe' }
            $candidates += 'E:\app\android-studio\jbr\bin\java.exe'
            if ($env:ProgramFiles) { $candidates += Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\java.exe' }
            $JavaExe = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
            if (-not $JavaExe) { $JavaExe = (Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source }
            if (-not $JavaExe) { throw 'Java not found. Set JAVA_HOME or JAVA_EXE to an installed JDK.' }
        }
        $wrapper = Join-Path $androidRoot 'gradle\wrapper\gradle-wrapper.jar'
        if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) { throw 'Android Gradle wrapper JAR not found.' }
        $proxyArguments = @()
        if ($BuildProxy -and $BuildProxy -ine 'direct') {
            $proxyUri = $null
            if (-not [Uri]::TryCreate(('http://' + $BuildProxy), [UriKind]::Absolute, [ref]$proxyUri) -or
                $BuildProxy -notmatch ':\d+$' -or $proxyUri.UserInfo -or $proxyUri.AbsolutePath -ne '/' -or $proxyUri.Query -or $proxyUri.Fragment) {
                throw 'BUILD_PROXY/-BuildProxy must be host:port, or direct.'
            }
            $proxyArguments = @("-Dhttp.proxyHost=$($proxyUri.Host)", "-Dhttp.proxyPort=$($proxyUri.Port)", "-Dhttps.proxyHost=$($proxyUri.Host)", "-Dhttps.proxyPort=$($proxyUri.Port)")
        } elseif ($BuildProxy -ieq 'direct') {
            $proxyArguments = @('-Dhttp.proxyHost=', '-Dhttps.proxyHost=', '-Djava.net.useSystemProxies=false')
        }
    }

    $publisher = Join-Path $binDirectory 'codexrm-publish.exe'
    if (-not $BuildOnly) {
        go build -trimpath -ldflags '-s -w' -o $publisher ./cmd/codexrm-publish
        if ($LASTEXITCODE) { throw 'Publisher build failed.' }
    }

    Write-Host "CodexRM Android: $oldName ($oldCode) -> $name ($code)"
    if (-not $PublishOnly) {
        if (-not $KeepVersion) {
            $changedVersion = [Text.Encoding]::UTF8.GetBytes($text)
            [IO.File]::WriteAllBytes($versionPath, $changedVersion)
        }
        Write-Host "Java: $JavaExe"
        Write-Host "Running Android tests and Release build. Log: $buildLog"
        Push-Location $androidRoot
        $previousPreference = $ErrorActionPreference
        try {
            # PowerShell 5.1 wraps native stderr as errors, including harmless JVM warnings.
            $ErrorActionPreference = 'Continue'
            $global:LASTEXITCODE = 1
            & $JavaExe @proxyArguments -jar $wrapper testDebugUnitTest assembleRelease '-Pcodexrm.testSign' --console=plain @proxyArguments *> $buildLog
            $gradleExit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousPreference
            Pop-Location
        }
        if ($gradleExit) { throw "Android tests/build failed (exit $gradleExit). See $buildLog" }
        & (Join-Path $PSScriptRoot 'package-android.ps1') -ApkPath (Join-Path $androidRoot 'app\build\outputs\apk\release\app-release.apk') -OutputPath $apk -ReleaseNotes $ReleaseNotes
        Assert-AndroidPackage $apk $manifest $code $name
        $builtPackage = $true
        Get-Content -LiteralPath $buildLog -Tail 12
        Write-Host "APK: $apk"
        Write-Host "Manifest: $manifest"
    }
    if (-not $BuildOnly) {
        & $publisher -config $Config -package $apk -manifest $manifest
        if ($LASTEXITCODE) { throw 'Cloudreve APK publication failed.' }
        & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -Manifest $manifest -RM $publishConfig.rm -Name 'android-update.json'
        Write-Host ('Android update source: ' + ([string]$publishConfig.rm.server).TrimEnd('/') + '/android-update.json')
    }
    Write-Host 'Android release completed.' -ForegroundColor Green
} catch {
    if ($builtPackage -and -not $BuildOnly) {
        Write-Host 'Package retained. Retry with: android\build.bat nopause -PublishOnly (repeat any custom -Config/-OutputDirectory options).' -ForegroundColor Yellow
    }
    throw
} finally {
    if ($changedVersion -and -not $builtPackage) {
        $current = [IO.File]::ReadAllBytes($versionPath)
        if ([Linq.Enumerable]::SequenceEqual([byte[]]$current, [byte[]]$changedVersion)) {
            [IO.File]::WriteAllBytes($versionPath, $originalVersion)
            Write-Host 'Build/package failed; Android version file restored.' -ForegroundColor Yellow
        } else {
            Write-Warning 'The Android version file was edited during the build; preserving those edits instead of overwriting them.'
        }
    }
    if ($lock) { $lock.Dispose() }
    Pop-Location
}
