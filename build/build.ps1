# Build the module APK on Windows without Gradle:
#   javac -> dex -> aapt2 -> jar -> zipalign -> apksigner
#
#   $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
#   .\build\build.ps1
#
# Optional environment variables:
#   KEYSTORE   keystore path   (unset -> produces an unsigned apk)
#   KS_PASS    keystore password
#   KEY_ALIAS  key alias (default: xiaobu)
#   DX_JAR     dx.jar path, only used when d8 cannot run (JDK older than 11)
#
# NOTE: keep the checkout path ASCII-only. aapt2 on Windows fails to open
#       directories whose path contains non-ASCII characters.
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Module = Join-Path $Root "module"
$Out = Join-Path $Root "out"
$Cls = Join-Path $Out "classes"
$Dex = Join-Path $Out "dex"
$Apk = Join-Path $Out "apk"

$Sdk = $env:ANDROID_HOME
if (-not $Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (-not $Sdk) { throw "set ANDROID_HOME (or ANDROID_SDK_ROOT) to your Android SDK" }
if (-not (Test-Path $Sdk)) { throw "SDK not found: $Sdk" }

$Bt = Get-ChildItem (Join-Path $Sdk "build-tools") -Directory |
    Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } |
    Select-Object -Last 1
if (-not $Bt) { throw "no build-tools under $Sdk\build-tools" }
$Aapt2 = Join-Path $Bt.FullName "aapt2.exe"
$Zipalign = Join-Path $Bt.FullName "zipalign.exe"
$Apksigner = Join-Path $Bt.FullName "apksigner.bat"
foreach ($t in @($Aapt2, $Zipalign, $Apksigner)) {
    if (-not (Test-Path $t)) { throw "missing tool: $t" }
}

$AndroidJar = Get-ChildItem (Join-Path $Sdk "platforms") -Directory |
    Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } |
    ForEach-Object { Join-Path $_.FullName "android.jar" } |
    Where-Object { Test-Path $_ } |
    Select-Object -Last 1
if (-not $AndroidJar) { throw "no platforms\android-*\android.jar in $Sdk" }

$Manifest = Join-Path $Module "AndroidManifest.xml"
$Xml = Get-Content $Manifest -Raw
$VersionName = [regex]::Match($Xml, 'android:versionName="([^"]*)"').Groups[1].Value
$VersionCode = [regex]::Match($Xml, 'android:versionCode="([0-9]+)"').Groups[1].Value
if (-not $VersionName) { throw "cannot read versionName from manifest" }

Write-Host "== module $VersionName (code $VersionCode)"
Write-Host "== SDK    $Sdk"
Write-Host "== tools  $($Bt.FullName)"
Write-Host "== jar    $AndroidJar"

Remove-Item $Out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Cls, $Dex, $Apk | Out-Null

Write-Host "== javac (libxposed API stub + module)"
# The libxposed API is shipped as Java 17 bytecode, which JDK 8 cannot read, so
# build/stub-libxposed mirrors the same signatures and is compiled first. Those
# stub classes stay out of the dex: at runtime LSPosed provides the real ones.
$StubOut = Join-Path $Out "libxposed-stub"
New-Item -ItemType Directory -Force -Path $StubOut | Out-Null
$StubSources = Get-ChildItem (Join-Path $Root "build\stub-libxposed") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $AndroidJar -d $StubOut $StubSources
if ($LASTEXITCODE -ne 0) { throw "javac (libxposed stub) failed" }

$Sources = Get-ChildItem (Join-Path $Module "src") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $AndroidJar `
    -classpath $StubOut -d $Cls $Sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== dex"
$D8 = Join-Path $Bt.FullName "lib\d8.jar"
$Dexed = $false
# d8 needs JDK 11+; probe the version first so JDK 8 users don't see a stack trace.
$JavaMajor = 0
$verText = ""
try { $verText = (& java -version 2>&1 | Out-String) } catch { $verText = "$_" }
if ($verText -match 'version "(\d+)(?:\.(\d+))?') {
    $JavaMajor = [int]$Matches[1]
    if ($JavaMajor -eq 1 -and $Matches[2]) { $JavaMajor = [int]$Matches[2] }
}
Write-Host "   java $JavaMajor"
if ($JavaMajor -ge 11 -and (Test-Path $D8)) {
    $Classes = Get-ChildItem $Cls -Recurse -Filter *.class | ForEach-Object { $_.FullName }
    & java -cp $D8 com.android.tools.r8.D8 --min-api 29 --lib $AndroidJar --output $Dex $Classes
    if ($LASTEXITCODE -eq 0) { $Dexed = $true } else { Write-Host "   d8 failed; falling back to dx" }
}
if (-not $Dexed) {
    if ($env:DX_JAR -and (Test-Path $env:DX_JAR)) {
        & java -cp $env:DX_JAR com.android.dx.command.Main --dex "--output=$Dex\classes.dex" $Cls
        if ($LASTEXITCODE -ne 0) { throw "dx failed" }
    } else {
        throw "no dexer: use JDK 11+ (d8) or set DX_JAR to a dx.jar (dex-tools 2.4)"
    }
}

Write-Host "== aapt2"
& $Aapt2 compile --dir (Join-Path $Module "res") -o (Join-Path $Out "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
& $Aapt2 link -o (Join-Path $Apk "base.apk") --manifest $Manifest `
    -I $AndroidJar -R (Join-Path $Out "res.zip") --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== pack (jar uf: classes.dex + META-INF/xposed)"
Copy-Item (Join-Path $Apk "base.apk") (Join-Path $Apk "unaligned.apk") -Force
& jar uf (Join-Path $Apk "unaligned.apk") -C $Dex classes.dex `
    -C (Join-Path $Module "meta") META-INF/xposed
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "== align"
& $Zipalign -f 4 (Join-Path $Apk "unaligned.apk") (Join-Path $Apk "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$Name = "xiaobu-unlocker-$VersionName-vc$VersionCode"
if ($env:KEYSTORE) {
    Write-Host "== sign"
    if (-not $env:KS_PASS) { throw "set KS_PASS together with KEYSTORE" }
    $Alias = if ($env:KEY_ALIAS) { $env:KEY_ALIAS } else { "xiaobu" }
    & $Apksigner sign --ks $env:KEYSTORE --ks-pass "pass:$($env:KS_PASS)" `
        --ks-key-alias $Alias --out (Join-Path $Apk "$Name.apk") (Join-Path $Apk "aligned.apk")
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
    & $Apksigner verify (Join-Path $Apk "$Name.apk")
} else {
    Write-Host "== unsigned (set KEYSTORE/KS_PASS/KEY_ALIAS to sign)"
    Copy-Item (Join-Path $Apk "aligned.apk") (Join-Path $Apk "$Name-unsigned.apk")
}

Write-Host "== done"
Get-ChildItem $Apk -Filter *.apk | Select-Object Name, Length
