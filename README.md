# 小布建议双栏自定义（Xposed / LSPosed）

给 OPPO / 一加「小布建议」桌面小组件换掉**左右两栏**的内容。

系统原本只允许在「系统工具 + 应用建议」这类固定组合里挑卡；本模块借用系统自带的
**「编辑默认样式」**界面，让你把左栏、右栏分别换成卡片池里的**任意一张卡**
（原生卡 / 快应用卡 / 系统工具卡），不新增任何自己的 UI。

- 模块包名：`io.github.cechang256.xiaobu`
- 当前版本：**1.25**（versionCode 19）
- 注入进程：**只有** `com.oplus.pantanal.ums`（决策引擎），不碰桌面进程
- 已验证环境：一加 PLG110 / Android 17 / ColorOS `PLG110_17.0.0.105(CN01)` / LSPosed 2.1.1

---

## 功能

| 能力 | 说明 |
|---|---|
| 左右栏独立选卡 | 两栏各自从系统的卡片池里选，互不影响 |
| 左右对调 | 把两栏内容互换（会看到约 1 秒系统卡，机制固有，见下文） |
| 切回系统样式 | 选系统的「系统工具+应用建议」页签保存即可，模块配置保留 |
| 编辑器体验 | 选中蓝框自动落到当前栏那张卡、卡片池标题按栏显示「左栏 · 卡名 / 右栏 · 卡名」 |
| 抗进程被杀 | 对调途中进程被杀，桌面不会永久停在系统卡（`roundtrip_at` 自愈） |

## 安装

1. 手机已 Root，装好 **LSPosed**（2.1.1 上验证）；
2. 安装本模块 APK；
3. 在 LSPosed 管理器里启用模块。作用域由模块指定（`com.oplus.pantanal.ums`），管理器里不可增删。
4. 在 LSPosed 的作用域列表里长按「**智慧建议服务**」→ **强行停止**，让决策进程重新加载模块
   （不用命令，也不用重启手机）。

   > 也可以用命令：`adb shell su -c 'am force-stop com.oplus.pantanal.ums'`。
   > 如果停在桌面发现卡片没变，切一页桌面再回来。

5. 回到桌面，长按卡片 → **编辑默认样式**，即可看到多出来的自定义页签。

## 使用

```
桌面长按卡片 → 编辑默认样式 → 切到自定义页签
   → 点预览里的「左栏」或「右栏」→ 开始编辑
   → 在下面卡片池里点一张卡
   → 完成
```

- 只改一栏：另一栏不受影响；
- **两栏对调**：分别给两栏选中对方的卡并保存，桌面会短暂显示系统卡随后换栏；
- 保存失败/想反悔：点「取消」即还原，prefs 不会被污染。

## 配置

配置在模块自己的 SharedPreferences（`layout_json` 键），跨进程通过自建 Provider 读取：

```json
{
  "mode": "custom",
  "features": { "roundms": 1200 },
  "tab_0": {
    "col_0": { "serviceId": "536878636", "type": 100790, "size": 1, "name": "运动计步" },
    "col_1": { "serviceId": "536878384", "type": 100515, "size": 1, "name": "天气时钟" }
  }
}
```

| 键 | 含义 |
|---|---|
| `mode` | `custom` = 桌面走你的配置；`native` = 用系统页签（配置保留） |
| `tab_0.col_0` / `col_1` | 左栏 / 右栏的目标卡（`serviceId` 必填） |
| `features.roundms` | 对调时系统卡停留时长，默认 1200ms（低于 700 会被 launcher 合并成一次变化） |

免装机改配置：见 [tools/](tools/) 里的脚本。

## 构建

无 Gradle，纯 `javac → dex → aapt2 → zipalign → apksigner`：

```bash
# Linux / macOS（JDK 11+ 用 d8，JDK 8 用 dx）
ANDROID_HOME=$HOME/Android/Sdk ./build/build.sh

# Windows
$env:ANDROID_HOME="C:\Users\me\AppData\Local\Android\Sdk"; .\build\build.ps1
```

详细依赖、签名与常见报错见 [build/README.md](build/README.md)。

## 已知限制

- **只在这一套 ROM 上验证过**：所有钩子都绑定了当前 ROM 的类名/方法名/混淆名，OTA 后大概率要重新对位；
- **对调时会短暂显示系统卡**；
- **两栏不能是同一张卡**：同 `serviceId` 会被宿主丢弃整个组合；
- **大卡片不是 appwidget**：渲染在 `com.coloros.assistantscreen` 进程，所以位置控制只能在数据层做。

排查手册见 [docs/KNOWN-ISSUES.md](docs/KNOWN-ISSUES.md)。

## 目录

```
module/   模块源码（AndroidManifest / res / assets / src，10 个类）
build/    无 Gradle 构建脚本（Windows + Linux/macOS）
docs/     架构、硬结论、已知问题、变更历史
tools/    免装机的配置读写与日志切片脚本
```

## 免责声明

非官方项目，与 OPPO / 一加 / LSPosed 无关。改动的是系统决策数据，**刷机风险自负**；
升级/卸载前建议先切回系统样式。

## License

MIT，见 [LICENSE](LICENSE)。
