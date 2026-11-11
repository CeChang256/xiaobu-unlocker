# Build

无 Gradle。整条链路是 `javac → dex → aapt2 → jar → zipalign → apksigner`，
和模块早期手工发布的流程一致，产物与官方 APK 结构相同（entries 完全一致）。

## 依赖

| 需要 | 说明 |
|---|---|
| JDK | `javac` 8+ 即可；**11+ 推荐**（build-tools 里的 `d8` 需要 JDK 11+） |
| Android SDK | `ANDROID_HOME` 指向它；需要 `build-tools` + **任意一个** `platforms/android-*/android.jar` |
| Python | **不需要**（打包用 JDK 自带的 `jar uf`） |
| Xposed API | **不需要下载**：`build/stub/` 里有最小 API 桩，构建时先编译它们 |

> `build/stub` 只是编译期桩，**不会**打进 APK；运行时由 LSPosed 提供真实实现。

## Windows

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\build\build.ps1
```

JDK 8 且没配 `DX_JAR` 时报 `no dexer` —— 二选一：

```powershell
# A. 换 JDK 11+（推荐，走 d8）
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
# B. 继续用 JDK 8，指定 dx.jar（dex-tools 2.4）
$env:DX_JAR = "C:\tools\dex-tools\lib\dx-30.0.2.jar"
```

## Linux / macOS

```bash
ANDROID_HOME=$HOME/Android/Sdk ./build/build.sh
```

## 签名

不传 `KEYSTORE` 时产出 `*-unsigned.apk`（可直接装但无法覆盖官方版本）。
官方发布的 APK 用同一个密钥签名，签名不同的包**不能覆盖安装**。

```bash
KEYSTORE=/path/to/release.jks KS_PASS=*** KEY_ALIAS=xiaobu ./build/build.sh
```

```powershell
$env:KEYSTORE = "D:\keys\release.jks"; $env:KS_PASS = "***"; $env:KEY_ALIAS = "xiaobu"
.\build\build.ps1
```

## 产物

```
out/
├── classes/            javac 输出
├── stub-classes/       Xposed API 桩（不进 APK）
├── dex/classes.dex
├── res.zip
└── apk/
    ├── base.apk            aapt2 link 结果（无 dex）
    ├── unaligned.apk       加入 classes.dex
    ├── aligned.apk         zipalign 后
    └── xiaobu-unlocker-<versionName>-vc<versionCode>.apk
```

## 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| `aapt2: failed to open directory` | **路径含中文**。aapt2 在 Windows 上无法打开非 ASCII 目录，把仓库放到纯英文路径 |
| `no dexer` | JDK 8 且未设 `DX_JAR`，见上文 |
| `javac: 错误: 找不到符号 de.robv...` | `build/stub` 被删了；恢复它，或改成依赖 Xposed API jar |
| `apksigner: Missing --ks` | 设了 `KEYSTORE` 但没设 `KS_PASS` |
| 装完刷不进/模块不生效 | 装完必须 `adb shell su -c 'am force-stop com.oplus.pantanal.ums'`，见 README |

## CI

`.github/workflows/build.yml` 在每次 push / PR 时用 JDK 17 + `d8` 构建一个
**未签名** APK，作为“源码仍可编译”的守卫。发布用的签名包在本地构建后手动上传。
