# preview_realign_test

离线断言：1.41 的两条修复面（不依赖 Android / ROM 类）。

- `PreviewPlan`：编辑器预览每一栏的动作（skip / keep / write / rebuild）。
  覆盖两条回退分支——自定义 tab 实体还没建出来（整体 skip）、右栏卡池里没有这张卡（rebuild）。
- `RealignGate`：切到「自定义」时按 col_0/col_1 重排 compose 的双重门控
  （`features.compose_realign` 关闭时一次都不做；同一 pairKey 只做一次）。

运行（需要 JDK 17）：

```powershell
pwsh -ExecutionPolicy Bypass -File tools/preview_realign_test/run.ps1
```

期望输出：`RESULT OK PASS 26 FAIL 0` + `ALL PREVIEW TESTS OK`。
