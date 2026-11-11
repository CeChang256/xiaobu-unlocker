# Architecture

## 1. 整体数据流

```
[系统「编辑默认样式」界面]  （com.oplus.pantanal.ums 进程）
        │ 用户选左/右栏 → 点完成
        ▼
[EditorHook]  注入编辑器 Fragment
        │ ① 把选择写进模块配置 tab_0.col_0 / col_1（走自建 Provider）
        │ ② 左栏：替换系统保存函数 h() 的入参 json，放行系统自己的写入
        │ ③ 若只是"两栏对调" → 启动模式往返（§3）
        ▼
[系统存储层]
        ├─ OPlus 私有 provider: content://com.oplus.appplatform.settings/secure
        │    键 group_card_user_selected_config  ← 决策引擎只读这一套
        └─ 标准 settings 表  ← 决策引擎不读（早期踩过，已废弃）
        ▼
[SceneServiceRewriter]  注入 ums 的决策出口
        │ 拦截查询结果，把 097 槽（右栏）与 row0（左栏）换成配置里的卡
        ▼
[launcher / metis]  按「显示卡片集合」决定要不要重建视图
        ▼
[com.coloros.assistantscreen]  真正渲染分组卡（原生 View，不是 appwidget）
```

## 2. 为什么必须两个改写点

| 出口 | 钩子 | 负责 |
|---|---|---|
| 决策行（`ArrayList<ResultEntity>`） | `DecisionProvider.guaranteed` / `cardConfigDecision` | 右栏（097 保证卡槽） |
| 场景列表（序列化前） | `SceneDecisionProvider.toCursor(List, String[])` | 左栏（场景 210000000 的第一张非固定卡） |

两个出口覆盖不同的上游分支（数据库行 / 保证卡 / 补偿），**只挂一个会漏**。

## 3. 槽位映射（曾反复搞错）

```
桌面左栏 = 场景 210000000 的第一张非固定卡行（row0）
桌面右栏 = 场景 210000000 的 097 保证卡槽（policyName = DYNAMIC_SUBSTITUTE）
```

对调时两种映射互为镜像、**显示结果相同**，所以只能靠“只改一侧”的单向实验定案，
不要用对调结果反推映射。

## 4. 改写一张卡做了什么

实体是 `SceneResultEntity`（字段名来自 javap）：

| 字段 | 写入 |
|---|---|
| `serviceId` / `name` | 目标卡身份 |
| `cardType` | 1=快应用, 2=原生 provider, 100=播种卡 |
| `supportSize` | 目标尺寸 |
| `hostPackage` / `url` | 目标包与 RPK url（原生卡为空） |
| `cardConfig` | 优先用抓包得到的逐字模板，没有才现场合成 |
| `sgExtraInfo` | 补 `isUserSelected`，否则长按菜单不显示“编辑当前卡片” |

槽位语义字段（`policyName` / `intentPosition` / `action` / `sceneId` …）**必须保留原值**，
插件靠它们判断左右栏。实现里用 `PRESERVE_FIELDS` 白名单在覆盖后还原。

改写完成会**回读校验**（`serviceId`/`name` 是否真的写进去了），不一致就整实体回滚 ——
半新半旧的实体比不改更糟（会渲染成 A、点开却是 B 的数据）。

## 5. 模式往返（对调的唯一可行手段）

1. `save` → `beginRoundTripMark()`：写入 `roundtrip_at` 时间戳，`mode` 置 native（同一次原子写）；
2. 系统卡上台、自定义两张卡**离场** → launcher 判定“集合变了” → 重建；
3. 数据查询把状态机推到 `endRoundTripMark()`：清标记、回 custom，两张卡按新顺序**重新入场** → 重排。

### 抗进程被杀

`mode=native` 一旦落盘就是持久状态，若第 3 步没执行（进程被杀/回调丢失/期间无查询），
桌面会永久停在系统卡。所以读侧按标记过期自动纠正：

| 状态 | `mode()` 返回 |
|---|---|
| 有 `roundtrip_at` 且未过期 | native（往返进行中） |
| 有 `roundtrip_at` 但已过期（>`roundms` + 3000ms） | **custom**（自动纠正） |
| 无 `roundtrip_at` | 落盘的 mode（用户合法选择） |

普通 `setMode()` 会顺带删标记，所以“切回系统样式”这个**合法** native 永远不会被自愈误伤。
进程启动时另有一次 `healInterruptedRoundTrip()` 清脏标记。

> slack 必须有下限：取 `roundms + 3000ms`。取小了，慢设备上 launcher 还在重绑就被判成“已过期”，
> 两张卡提前入场 → 重排被跳过 → 对调失效。

## 6. 代码地图（10 个类）

| 文件 | 职责 |
|---|---|
| `ModuleInit.java` | 入口；只对 `com.oplus.pantanal.ums` 生效；装配所有钩子 + 启动自愈 |
| `EditorHook.java` | 「编辑默认样式」界面：拦截保存、写配置、触发往返、选中框/标题 |
| `SceneServiceRewriter.java` | 决策层改写 + 往返状态机 |
| `DefaultServiceHook.java` | 编辑器模型：追加自定义页签、同步预览 |
| `LayoutStore.java` | 配置读写（带 TTL 缓存）、mode/feature 访问器 |
| `LayoutProvider.java` | 自建 ContentProvider：跨进程读写配置与日志（uid 白名单） |
| `Diag.java` | 诊断日志缓冲 + 节流刷盘（512KB 轮转） |
| `AppContext.java` | 尽早拿到 Application Context |
| `CardTemplates.java` | 抓包得到的卡片逐字模板（按 serviceId） |
| `BuildInfo.java` | 版本号（读不到 PackageManager 时用兜底值） |

## 7. 编码铁律

`install()` 里**每个反射查找都必须单独容错**。1.13 有一次某个 `findClass` 失败，
导致整个模型层装配中断，但桌面渲染走另一条链路所以看起来一切正常，
只在编辑器里表现为“少了一个页签”——排查了两小时。现在每个查找都独立 try/catch 并记日志。
