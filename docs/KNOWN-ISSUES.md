# 已知问题与排查手册

## 1. 对调时闪一下系统卡

机制固有，见 [HARD-LESSONS.md](HARD-LESSONS.md) §3。`features.roundms` 默认 1200ms；
低于 700ms 会被 launcher 合并成一次数据变化，对调直接失效。

## 2. 两栏不能选同一张卡

同 `serviceId` 会让宿主丢弃整个卡片组合，退化成别的通栏卡。编辑器里会直接拒绝保存。

## 3. 兼容性

所有钩子都绑定了当前 ROM 的类名/方法名/混淆名：

| 目标 | 位置 |
|---|---|
| `DecisionProvider` / `SceneDecisionProvider` | ums |
| `EditDefaultCardFragment`、`AppSettingUtilsKt`、`activity.b/d/e` | ums 编辑器 |
| `com.oplus.sdp.nv.p` / `mv.t`（系统卡片工厂） | ums |

OTA 后大概率需要重新对位。工厂类找不到时会降级为“现场合成 cardConfig”，
表现为卡片能出来但样式/数据可能不对。

## 4. 改写“一条都没命中”时是静默的

可观测性缺口，不是功能缺陷：查询结果里没有 097 槽（或没有 row0）时，改写器什么都不做也不报错。
排查时看 `diag.log` 里有没有 `dswap rewrote`。

## 5. 五个会让你白查一天的坑

**① 装完 APK 必须 `force-stop com.oplus.pantanal.ums`**
注入进程不会因为模块 APK 更新而重载 dex。判据：确认 hook 安装日志出现，才说明新代码在跑。

```bash
adb shell su -c 'am force-stop com.oplus.pantanal.ums'
```

**② 改 prefs 必须同时停模块 App**

provider 进程的内存缓存会把文件刷回旧值：

```bash
adb shell su -c 'am force-stop io.github.cechang256.xiaobu; am force-stop com.oplus.pantanal.ums'
# 再 push 新文件并 chown/chmod
```

**③ logd 缓冲会被 metis 的巨量输出挤爆**

启动期日志会先被淘汰，表现成“代码似乎没执行”。查启动期先 `adb logcat -G 16M`。

**④ 三张天气类卡片名字太像**

`536878390` 天气时钟(快应用)、`536878709` 实时天气、`536878193` 今日天气。
选错卡时 `isPureSwap` 会正确返回 false、不触发往返，看起来像“对调失效”。用这条日志分辨：

```
editor save: swap check new=(L,R) served=(L,R) -> PURE SWAP / not a swap
```

**⑤ 遥控点击坐标别猜**

1272×2772 屏：长按「编辑默认样式」在 `(692, 1016)`；长按卡片要落在**单栏中心**
（左栏 x≈345, y≈470），x≈636 是两栏缝隙。`force-stop com.android.launcher` 会让设备回锁屏，
远程解不开。

## 6. 日志策略

稳态 **0 条/秒**（1.24 起）。启动/安装期 3 条一次性日志。

| 想看的 | 去哪 |
|---|---|
| 改写结果、对调状态机 | `Diag` 文件：`/data/data/io.github.cechang256.xiaobu/files/diag.log`（512KB 轮转） |
| 编辑器交互（选卡/标题/选中框） | logcat，`grep XiaoBuUnlocker` |
| 启动期 | 先 `adb logcat -G 16M` 再复现 |

## 7. 卸载 / 回滚

1. 先在编辑器里切到系统页签保存（`mode=native`），桌面即回系统样式；
2. LSPosed 里停用模块 → 再卸载 APK；
3. 配置残留在 `/data/data/io.github.cechang256.xiaobu/`，随卸载删除。
