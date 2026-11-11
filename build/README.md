# Build

无 Gradle。整条链路是 `javac → dex → aapt2 → jar → zipalign → apksigner`。

模块是 **modern 格式**（`META-INF/xposed/` + libxposed API），所以打包时除了
`classes.dex` 还要把标头文件塞进 APK 根目录：

```
META-INF/xposed/module.prop      minApiVersion / targetApiVersion / staticScope=true
META-INF/xposed/scope.list       com.oplus.pantanal.ums
META-INF/xposed/java_init.list   io.github.cechang256.xiaobu.ModernEntry
```

这三份文件在 `module/meta/` 里，构建脚本会用 `jar uf` 打进 APK。

## 依赖

| 需要 | 说明 |
|---|---|
| JDK | **8 即可**（`build/stub-libxposed` 提供了 API 102 的签名，绕开"官方 jar 是 Java 17 字节码"的问题） |
| Android SDK | `ANDROID_HOME` 指向它；需要 `build-tools` + 任意一个 `platforms/android-*/android.jar` |
| Python | **不需要** |
| libxposed API | **不需要下载**；`build/stub-libxposed` 只是编译期桩，**不进 APK** |
| dx / d8 | JDK 11+ 用 build-tools 自带的 `d8`；JDK 8 需要 `DX_JAR` 指向 dex-tools 的 `dx.jar` |

> `build/stub-libxposed` 的签名是从官方 `io.github.libxposed:api:102.0.0` 逐条对齐的。
> 升级 API 时（例如 103）要同步改这里，否则运行时会 `NoSuchMethodError`。

## Windows

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\build\build.ps1
```

JDK 8 且没配 `DX_JAR` 时会报 `no dexer`：

```powershell
# A. 换 JDK 17（走 d8）
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
# B. 继续用 JDK 8，指定 dx.jar
$env:DX_JAR = "C:\tools\dex-tools\lib\dx-30.0.2.jar"
```

## Linux / macOS

```bash
ANDROID_HOME=$HOME/Android/Sdk ./build/build.sh
```

## 签名

不传 `KEYSTORE` 时产出 `*-unsigned.apk`（可安装，但无法覆盖官方版本）。

```bash
KEYSTORE=/path/to/release.jks KS_PASS=*** KEY_ALIAS=xiaobu ./build/build.sh
```

## 产物

```
out/
├── classes/            javac 输出（模块类，进 dex）
├── libxposed-stub/     API 桩（不进 dex）
├── dex/classes.dex
├── res.zip
└── apk/
    ├── base.apk            aapt2 link 结果（无 dex、无 META-INF/xposed）
    ├── unaligned.apk       加入 classes.dex 与 META-INF/xposed
    ├── aligned.apk         zipalign 后
    └── xiaobu-unlocker-<versionName>-vc<versionCode>.apk
```

## 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| `aapt2: failed to open directory` | **路径含中文**。aapt2 在 Windows 上打不开非 ASCII 目录，把仓库放到纯英文路径 |
| `no dexer` | JDK 8 且未设 `DX_JAR` |
| `javac: 找不到符号 io.github.libxposed...` | `build/stub-libxposed` 被删了 |
| 装好后管理器里显示"未知模块"/不出现 | 确认 APK 里有 `META-INF/xposed/java_init.list`（`jar tf` 查看） |
| 模块启用后没有日志 | 见 README「装完必须 `force-stop com.oplus.pantanal.ums`」 |

## CI

`.github/workflows/build.yml` 用 JDK 17 + `d8` 构建**未签名** APK，作为"源码仍可编译"的守卫。
