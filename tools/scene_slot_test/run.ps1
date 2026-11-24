$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # repo root
$Sep = [IO.Path]::PathSeparator
$Pkg = Join-Path $Root "module/src/io/github/cechang256/xiaobu"
$Tmp = Join-Path $Root "out/slot-test"
Remove-Item $Tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Tmp | Out-Null
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
& javac -encoding UTF-8 -Xlint:-options -d $Tmp `
    (Join-Path $Pkg "SceneSlots.java") `
    (Join-Path $PSScriptRoot "SceneSlotsTest.java")
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
& java -cp $Tmp "io.github.cechang256.xiaobu.SceneSlotsTest"
if ($LASTEXITCODE -ne 0) { throw "SceneSlotsTest failed" }
Write-Host "ALL SLOT TESTS OK"
