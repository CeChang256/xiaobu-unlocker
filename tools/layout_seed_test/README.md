# LayoutSeed / SwapGate 离线单测（1.35）

两个纯 Java + `org.json` 的配置逻辑类，可脱离 Android 直接在 JVM 上验证：

- `LayoutSeed`（1.34 引入）：给 layout_json 的 `features` 补 `model` / `custom_tab`
  两项（缺键才补，已存在的值——包括 `dswap`——一律不读不写不删）。
  **1.35 起不再补 `dswap`**：闸门已改由 mode 驱动，补一个显式值只会把实时的
  用户选择冻成一次性快照。
- `SwapGate`（1.35 新增）：桌面改写的闸门判据。`features.dswap` 存在时是显式
  覆盖（true/false 都照办）；缺键时看 mode（custom → 开，native/空 → 关）。

## 运行

需要一个 `org.json` jar（本机 Android Studio / Gradle 缓存里通常已有，例如
`~/.gradle/caches/modules-2/files-2.1/org.json/json/20231013/*/json-20231013.jar`）。

```powershell
$env:JSON_JAR = "C:\path\to\json-20231013.jar"
pwsh -ExecutionPolicy Bypass -File tools/layout_seed_test/run.ps1
```

脚本会用当前 `java` / `javac`（JDK 8+ 均可）把两个被测类与两个测试类编到
`out/seed-test`（`build.ps1` 会重建 `out/`，不会污染仓库），依次跑
`LayoutSeedTest` 与 `SwapGateTest`，任一失败即非零退出。

## 覆盖

- 验收矩阵：{无 features}+custom→on、{无 features}+native→off、
  {dswap:false}+custom→off、{dswap:true}+native→on（闸门本身；mode 另有一道 skip）。
- 缺键的三种形态（无 features 键 / `features:{}` / 空串、null、坏 JSON）。
- 真值规则与 1.33 的 `optBoolean` 一致（`"junk"` / 0 → false，1 → true）。
- mode 前缀语义、diag 标注 `on(mode)` / `off(explicit)` 等。
- LayoutSeed 反向断言：seed 绝不新增 `dswap`，已存在的 `dswap`（true/false）原样保留。
