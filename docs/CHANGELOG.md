# Changelog

版本号与 `versionCode` 均取自 `AndroidManifest.xml`。

## 1.29 — versionCode 23

针对"对调失败"的三处修复：

- **翻转回 custom 后补发通知**（`retryRefreshIfIdle`）：14:00 那次对调实测，
  mode 已经切回 custom、通知也发了，但 launcher 在接下来 8 秒里一次场景查询
  都没有（当时桌面停在别的页/编辑器盖着），桌面于是停在翻转前的画面，看起来
  就是"对调没成功"。现在按 300/900/1800ms 补三脚，一旦 `SceneServiceRewriter`
  的场景查询计数增加就停。
- **选卡后立刻刷新卡片池标题**：之前标题只在点「开始编辑」时更新，选完卡要再点
  一次「开始编辑」才变「左栏 · 卡名」。
- `roundms` 默认 1200 → **800**：缩短系统卡可见的那段往返时间。

## 1.28 — versionCode 22

- **修复：卡片池标题不跟随编辑状态**。之前点「开始编辑」后标题一直停在
  「请点击『开始编辑』」，从不变成「左栏 · 卡名」/「右栏 · 卡名」。
  根因：`gridRecycler` 被赋成了普通 ViewGroup——挂载 hook 走
  `hookAllMethods(RecyclerView, "onAttachedToWindow")` 会一路挂到
  `View#onAttachedToWindow`，于是每个子 View 都会命中，最后把 gridRecycler
  覆盖成 LinearLayout；随后 `findViewHolderForAdapterPosition` 抛
  `NoSuchMethodError` 被吞掉，标题永远不刷新。
  修法：只接受真正的 RecyclerView；并记下绑定层写入标题的那个 TextView，
  激活时直接改它，不再依赖 ViewHolder 查找。
- **修复：预览绑定 hook 每帧报错**（`editor bind hook fail ... c$a#f`）。
  卡片池适配器 `c` 继承自预览适配器 `h`、复用同一个 `onBindViewHolder`，
  池子的 holder（`c$a`，字段只有 a/b/c/d）没有 `f`/`g`。现在先按字段存在性分流。
- 顺带消掉了 `grid recycler attached: android.widget.LinearLayout` 这类刷屏日志
  （一次打开编辑器 410 行）。

## 1.27 — versionCode 21

- 只改**模块描述**与文档，无功能变更：描述里现在写了「启用后长按『智慧建议服务』→ 强行停止」，
  这行字出现在 LSPosed 管理器的模块卡片里，是首次安装者唯一必然看到的位置。
- README 的安装步骤同样改成「不用命令、不用重启」的路径。
- 不新增图标、不申请 root、不改任何 hook 逻辑。

## 1.26 — versionCode 20

- **迁移到 modern 模块格式**（libxposed API 102）：入口改为 META-INF/xposed/java_init.list
  + io.github.libxposed.api.XposedModule，作用域改为 META-INF/xposed/scope.list，
  并在 module.prop 里声明 staticScope=true。
- 结果：**作用域变成真正的静态作用域**，LSPosed 管理器里不可增删。
  （legacy 模块格式下 xposedscope 只是推荐，LSPosed 源码里 legacy 分支硬编码
  staticScope = false。）
- 兼容层：经典 API 的调用（XposedHelpers / XC_MethodHook / XposedBridge）在
  io.github.cechang256.xiaobu.xp 里以 libxposed 为底座重新实现，业务代码未改。
- 行为与 1.25 一致：双栏自定义、对调、编辑器选中框/标题、进程被杀自愈。

## 1.25 — versionCode 19

- **更换 application id**：com.xiaobu.unlocker → io.github.cechang256.xiaobu。
  原因是 LSPosed 官方仓库要求包名前缀可验证（io.github.<GitHub 用户名>），
  而原用户名以数字开头无法作为包名。功能无任何变化。
- Provider authority 随之改为 io.github.cechang256.xiaobu.layout。
- 注意：这是**新的应用**，与旧版 com.xiaobu.unlocker 不能互相覆盖，升级前需先卸载旧版
  （模块配置会重置，重新选一次卡即可）。

## 1.24 — versionCode 18

- 常驻日志降噪：稳态 logcat 从 ≈1.8 条/秒降到 **0**；删掉一条每次都跨进程 IPC 的诊断代码
  （原先为了打一行 `caller=`，每次查询都要 `Binder.getCallingUid()` + `getPackageName()`）。

## 1.23 — versionCode 17

- 清掉编辑器里三处“猜/等”实现（15 秒轮询、300ms 猜时机、视图树猜“第一个 RecyclerView”），
  全部换成确定性判据（选中框定位、标题刷新、遮罩状态）。

## 1.19 — versionCode 13

- 卡片池标题按栏区分：未编辑时「请点击「开始编辑」」，编辑中「左栏 · 卡名」/「右栏 · 卡名」。

## 1.17 — versionCode 11

- 编辑器选中框自动对位：点哪一栏，卡片池的蓝框落到该栏当前那张卡上；不在可视区会先平滑滚动过去。

## 1.12 — versionCode 6

修复代码审查发现的 3 个 P0/P1 问题，并删除全部逆向探针与实验分支（23 类 → 10 类）：

- **P0** `mode=native` 落盘后无自愈 → `roundtrip_at` 标记 + 读侧过期判定 + 启动自愈；
- **P0** 往返状态机先清状态再写盘、且无条件打印成功 → 改为“先写盘、成功才清”，失败重试 8s；
- **P1** 配置读-改-写无锁会丢列 → provider 加 `LAYOUT_LOCK`，编辑器两次 `setColumn` 合并为一次 `setColumns`；
- **P1** 改写失败静默，可能写出半新半旧实体 → 覆盖前快照、覆盖后回读校验，不一致整实体回滚；
- **P1** Provider 导出无鉴权 → `callerAllowed()` uid 白名单（self / root / system / ums，60s 缓存）；
- **P2** 版本号恒为 7.6（`BuildInfo.version()` 零调用方）→ 接上调用方，兜底值随版本更新。

## 1.10 — versionCode 4

- 双栏独立自定义可用；左右对调可用（会闪一下系统卡）；
- 切回系统样式后一切恢复，不改动系统数据库原始值。

## 1.0 — 早期

- 决策层注入方案定型：`DecisionProvider` + `SceneDecisionProvider.toCursor` 两个改写点；
- 确立五条硬结论（见 [HARD-LESSONS.md](HARD-LESSONS.md)）。
