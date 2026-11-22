$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # repo root
# OS-neutral paths/classpath so this also runs on the Linux CI runner.
$Sep = [IO.Path]::PathSeparator
$Pkg = Join-Path $Root "module/src/io/github/cechang256/xiaobu"
$Jar = $env:JSON_JAR
if (-not $Jar -or -not (Test-Path $Jar)) {
    throw "set JSON_JAR to an org.json jar (e.g. gradle cache .../json-20231013.jar)"
}
# 输出目录放在 out/ 下：build.ps1 每次都会重建 out/，不会污染仓库。
$Tmp = Join-Path $Root "out/seed-test"
Remove-Item $Tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Tmp | Out-Null
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
& javac -encoding UTF-8 -Xlint:-options -cp $Jar -d $Tmp `
    (Join-Path $Pkg "LayoutSeed.java") `
    (Join-Path $Pkg "SwapGate.java") `
    (Join-Path $PSScriptRoot "LayoutSeedTest.java") `
    (Join-Path $PSScriptRoot "SwapGateTest.java")
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$failed = 0
foreach ($suite in @("LayoutSeedTest", "SwapGateTest")) {
    Write-Host "---- $suite"
    & java -cp "$Tmp$Sep$Jar" "io.github.cechang256.xiaobu.$suite"
    if ($LASTEXITCODE -ne 0) { $failed++ }
}
if ($failed -ne 0) { throw "$failed suite(s) failed" }
Write-Host "ALL SUITES OK"
