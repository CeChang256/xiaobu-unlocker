# Changelog

版本号与 `versionCode` 均取自 `AndroidManifest.xml`。

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
