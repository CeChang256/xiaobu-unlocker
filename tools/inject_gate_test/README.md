# inject_gate_test

离线断言（纯 JVM，不需要设备）覆盖 1.38 新增的两块纯逻辑：

- `InjectGateTest` — 注入闸门：首轮武装 / 次数上限 3 / 第 4 轮停 / 签名变化重新武装 /
  `features.rebuild_inject=false` 立即停且不改状态 / `rebuild_inject_max` 覆盖 /
  空签名不注入 / prefs 读回后不复位。
- `CardConfigStampTest` — cardConfig 打 `isUserSelected`：正常结构补标记且其余键原样保留 /
  坏 JSON、空串、`[]`、null 一律重建 / 元素缺 `full_card_info` / 内层本身坏 /
  非对象元素跳过 / 多尺寸每个都打 / 重复调用幂等。

跑法（需要 org.json jar）：

```
$env:JSON_JAR=<...>/json-20231013.jar
pwsh -ExecutionPolicy Bypass -File tools/inject_gate_test/run.ps1
```

预期：两套都 `RESULT OK PASS n FAIL 0`，最后 `ALL SUITES OK`。
