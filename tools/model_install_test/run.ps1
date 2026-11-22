$ErrorActionPreference = "Stop"
# Offline harness for DefaultServiceHook.install(): fake ROM beans + counting XposedInterface.
# Needs a JDK (8+; 17 recommended for -bootclasspath with -source 8) and ANDROID_HOME.
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # repo root
$Sdk = $env:ANDROID_HOME
if (-not $Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (-not $Sdk) { throw "set ANDROID_HOME to the Android SDK" }
$AndroidJar = Get-ChildItem (Join-Path $Sdk "platforms") -Directory |
    Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } |
    ForEach-Object { Join-Path $_.FullName "android.jar" } |
    Where-Object { Test-Path $_ } | Select-Object -Last 1
if (-not $AndroidJar) { throw "no platforms\android-*\android.jar under $Sdk" }

$Tmp = Join-Path $Root "out\model-install-test"
Remove-Item $Tmp -Recurse -Force -ErrorAction SilentlyContinue
$StubOut = Join-Path $Tmp "stub"
$ModOut = Join-Path $Tmp "module"
$HostOut = Join-Path $Tmp "host"
$TestOut = Join-Path $Tmp "test"
foreach ($d in @($StubOut, $ModOut, $HostOut, $TestOut)) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

Write-Host "== javac (libxposed stub)"
$StubSources = Get-ChildItem (Join-Path $Root "build\stub-libxposed") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $AndroidJar -d $StubOut $StubSources
if ($LASTEXITCODE -ne 0) { throw "javac (stub) failed" }

Write-Host "== javac (module)"
$Sources = Get-ChildItem (Join-Path $Root "module\src") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $AndroidJar `
    -classpath $StubOut -d $ModOut $Sources
if ($LASTEXITCODE -ne 0) { throw "javac (module) failed" }

Write-Host "== javac (fake ROM beans)"
$HostSources = Get-ChildItem (Join-Path $PSScriptRoot "host") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 8 -target 8 -d $HostOut $HostSources
if ($LASTEXITCODE -ne 0) { throw "javac (host) failed" }

Write-Host "== javac (test)"
& javac -encoding UTF-8 -Xlint:-options `
    -classpath "$ModOut;$StubOut;$AndroidJar" -d $TestOut `
    (Join-Path $PSScriptRoot "src\io\github\cechang256\xiaobu\ModelInstallTest.java")
if ($LASTEXITCODE -ne 0) { throw "javac (test) failed" }

Write-Host "== run"
& java -cp "$TestOut;$ModOut;$StubOut;$AndroidJar" `
    io.github.cechang256.xiaobu.ModelInstallTest $HostOut
if ($LASTEXITCODE -ne 0) { throw "model install test failed" }
Write-Host "ALL PASS"
