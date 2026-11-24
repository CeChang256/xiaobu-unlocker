$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # repo root
$Pkg = Join-Path $Root "module/src/io/github/cechang256/xiaobu"
$Tmp = Join-Path $Root "out/hook-targets-test"
Remove-Item $Tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Tmp | Out-Null
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
& javac -encoding UTF-8 -Xlint:-options -d $Tmp `
    (Join-Path $Pkg "HookTargets.java") `
    (Join-Path $PSScriptRoot "HookTargetsTest.java")
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
& java -cp $Tmp "io.github.cechang256.xiaobu.HookTargetsTest"
if ($LASTEXITCODE -ne 0) { throw "HookTargetsTest failed" }
Write-Host "ALL HOOK TARGET TESTS OK"
