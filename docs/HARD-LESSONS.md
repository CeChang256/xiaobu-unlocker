# 硬结论（这个项目最贵的资产）

每条都是真实实验得出的，重复踩一遍要花掉一整天。改动前先读完。

## 1. 纯对调不会触发重绘

1. launcher 的重绑判定按**「显示卡片集合」**去重；
2. 对调前后集合完全相同 `{A,B}` → `{B,A}`，被判定为“没变化” → 不重建；
3. 单卡更换会改变集合 → 一定重绘（这解释了“换单卡一直好使，唯独对调没反应”）；
4. 位置记忆保存在**渲染侧**：卡片只要还在场就锁在原栏位，只有“离场 → 再入场”才按新顺序重排。

## 2. 数据层改不动位置（六个杠杆全部实测无效）

| 杠杆 | 结果 |
|---|---|
| `intentPosition` 写 0/1 | 桌面不翻转 |
| `isSceneFocus` / `sceneShouldFocus` | 无变化 |
| 伪造 `timestamp` / `sceneUpdateTime` | 卡片数据被重置（步数变 `--`），位置仍不变 |
| `forceRebuildSeedling=1` | 系统按自己数据重建实体、覆盖我们写的卡，副作用有害 |
| 直接改 `scene_decision_result` 行 | 会被意图管线重新生成 |
| 改 `card_service_intent_mapping` | 决策结果走缓存复用（guaranteed, not re-notify） |

**结论**：位置重排只能从“离场再入场”（模式往返）或渲染侧动手。想做零闪必须深入
`com.coloros.assistantscreen` 的 `GroupCardStateManager` / `CardComposeManager`，
成本高，已由需求方决定放弃。

## 3. 模式往返是唯一有效手段（照搬系统切 tab）

系统的「系统工具 / 应用建议 / 自定义」三选项切换时**不加任何中转卡**，它只是改 mode：

- `mode=native` → 模块停止改写，系统卡上台，自定义两张卡自然离场；
- `mode=custom` → 两张卡重新入场，按新档案重排。

模块把这个过程自动化。**代价**：中间那段系统卡会露出来约 1 秒（`features.roundms` 可调）。

试过但失败的替代方案：

| 方案 | 失败原因 |
|---|---|
| 临时插一张集合外的“中转卡”逼重绘 | 150/250/700ms 三档全部失败：launcher 把两次数据变化合并成一次，集合看起来没变；中转卡加载前还会出现布局塌陷 |
| 把往返藏在编辑器后面（挂住关页） | ① 关页方法被 hook 吞掉后无法安全重放（`InvocationTargetException`）；② **桌面不可见时 launcher 只拉数据不绑定**，位置重排不会发生 |
| launcher 侧遮罩（alpha=0 + 延时淡入） | 机制自测通过，但属“零闪”方案，需求方已决定下线，代码已删 |

## 4. 槽位映射

```
桌面左栏 = 场景 210000000 第一张非固定卡（row0）
桌面右栏 = 场景 210000000 的 097 保证卡槽（policyName=DYNAMIC_SUBSTITUTE）
```

对调时两种映射互为镜像、显示结果相同，**只能用“只改一侧”的单向实验定案**。

## 5. 时序不能依赖定时器

本环境里 `Handler.postDelayed` 与独立 `Thread.sleep` 出现过**多次整条静默丢失**。
所有流程推进必须由**数据链路**（查询次数）或**系统回调**驱动；定时器只允许做兜底，
且兜底路径必须能被读侧自愈（见 ARCHITECTURE §5）。

## 6. 双存储库陷阱

`AppSettingUtilsKt.putSecureString`（代码里叫 `h()`）写的是 **OPlus 私有 provider**，
不是标准 settings 表。用 `content insert` 直写会被签名校验拒绝，只能在 ums 进程内调用 `h()`。
早期两轮“改了没生效”全部源于读错库。

## 7. 大卡片不是 appwidget

`dumpsys appwidget` 里 launcher 是唯一 host 且 `widgets.size=0`。
大卡片是**原生 View**，渲染/分组在 `com.coloros.assistantscreen` 进程
（`card/groupcard/*`、`groupcard/compose/CardComposeManager`、`groupcard/groupstate/*`）。
想做更深的位置控制只能从这里下手。

## 8. 应用建议不是“特殊机制”

它就是决策数据里的一行：

```
云端 CloudIntentConfig
 → intent pantanal.intent.applicationsuggestions.SUBSTITUTE（sceneId 210000000，
   intentPosition=1，policyName=DYNAMIC_SUBSTITUTE）
 → card_service_intent_mapping（service_govern.db）action → 536877097
 → metis SortAlgorithmManager 重排
 → ums ServiceDecisionManager
 → scene_decision_result 持久化 + 通知 launcher 插件
```

它的“特殊性”只在 `policyName=DYNAMIC_SUBSTITUTE` + `cannotReduceRecommend=1`。
插件侧还有硬编码兜底 `scene/t.o = ["536877097","536877738"]`。
