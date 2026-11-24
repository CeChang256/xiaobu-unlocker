$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # repo root
$Pkg = Join-Path $Root "module/src/io/github/cechang256/xiaobu"
$Tmp = Join-Path $Root "out/perturb-test"
Remove-Item $Tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Tmp | Out-Null
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
& javac -encoding UTF-8 -Xlint:-options -d $Tmp `
    (Join-Path $Pkg "PerturbGate.java") `
    (Join-Path $PSScriptRoot "PerturbGateTest.java")
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
& java -cp $Tmp "io.github.cechang256.xiaobu.PerturbGateTest"
if ($LASTEXITCODE -ne 0) { throw "PerturbGateTest failed" }
Write-Host "ALL PERTURB TESTS OK"

# 源码级回归断言：native 态的 sNativeTypes 记录必须早于 `skip: dswap off` 的 return，
# 否则 1.39 首轮那个 bug（native=? / useless 分支不可达）会静默回来。
$Ssr = Join-Path $Pkg "SceneServiceRewriter.java"
$src = Get-Content -Raw -Encoding UTF8 $Ssr
$iNote = $src.IndexOf('shouldNoteNative(LayoutStore.isCustomMode(mode)')
$iSkip = $src.IndexOf('skip: dswap off')
if ($iNote -lt 0) { throw "sNativeTypes 观测点不见了" }
if ($iSkip -lt 0) { throw "skip: dswap off 分支不见了" }
if ($iNote -gt $iSkip) { throw "回归：sNativeTypes 记录在 skip 之后（native 态永远记不到）" }
Write-Host "PASS source: native 态 sNativeTypes 记录早于 skip: dswap off"