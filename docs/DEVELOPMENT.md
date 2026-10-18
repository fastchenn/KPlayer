# 开发、测试与发布

## 本机文件

仓库保存源码、资源、固定依赖清单、构建脚本、测试与文档。

| 路径 | 用途 |
| --- | --- |
| `.gradle/`、`.kotlin/`、各模块 `build/` | 本机构建缓存和中间结果，不提交 |
| `.gradle/native-sources/` | 经哈希验证的原生源码下载缓存，可复用 |
| `.gradle/android-sdk-test/`、`.gradle/android-avd/` | 此工作区的模拟器环境，可复用，不属于发布内容 |
| `dist/0.2.5/` | 本机整理后的 Release 附件，不提交 Git |
| `local.properties`、签名私钥 | 本机配置，不提交 |

## 构建验证

安装 JDK 17、Android SDK 35 和 Build Tools 35.0.0 后运行：

```powershell
.\gradlew.bat :app:testReleaseUnitTest :playback:mpv:testReleaseUnitTest :app:lintRelease :app:assembleRelease
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :playback:mpv:assembleDebugAndroidTest
```

默认只包含 ARM64。x86_64 模拟器需给相关命令增加 `'-PmpvAbis=arm64-v8a,x86_64'`；PowerShell 中需保留参数的引号。首次下载完成后可使用 `--offline`。

连接专用测试设备/模拟器后，可执行 `:playback:mpv:connectedDebugAndroidTest` 和 `:app:connectedDebugAndroidTest`。应用测试会写入测试连接、设置和观看历史，应使用独立测试环境。CI 只构建设备测试 APK，不运行模拟器。

## 进程重启后的缓存回归

使用相同证书签署应用 APK 和测试 APK，先通过 `adb install -r` 安装。以下为 PowerShell 命令；`adb` 必须指向目标测试设备：

```powershell
$runner = 'dev.davplayer.app.test/androidx.test.runner.AndroidJUnitRunner'
$testClass = 'dev.davplayer.app.ui.PersistentPlaybackTest'
adb shell am instrument -w -e class "$testClass#a_primeCacheThroughOpenListAndPersistHistory" $runner
adb shell am force-stop dev.davplayer.app
adb shell am instrument -w -e class "$testClass#b_reopenHistoryWithoutOpenListAfterProcessRestart" $runner
```

第一阶段完成后模拟 WebDAV/CDN 服务已关闭；第二阶段必须使用上一阶段保留的应用数据，检查离线历史播放、字幕和拖动。两个阶段分别输出 `OK (1 test)` 才算通过。普通整类运行可验证逻辑复用，但不能替代外部 force-stop 的进程重启验证。

`PlayerLayoutTest` 检查界面和缓存设置；mpv 模块的 Android 测试检查真实解码、GPU 画面、字幕、控制与持久缓存。测试样本在本机生成，不需要真实 NAS 凭据。

## 发布附件

1. 更新 `app/build.gradle.kts` 中的版本号和递增的 versionCode，整理 `docs/releases/`、`docs/verification/` 和 README。
2. 使用维护者本地保管的证书签署 Release。源码默认不指定 Release 签名；可使用 Android Studio 的 Generate Signed Bundle / APK。维护者证书不上传 GitHub。
3. 校验 APK 版本、ABI、`apksigner verify` 和 `zipalign -c -P 16 4`，将对外 APK 复制到 `dist/<版本>/`。
4. 更新 `SOURCES.md` 中的标签/附件名称，以及 `playback/mpv/native-sources.json` 的应用 release 字段；原生组件版本不变时保留既有 commit 和哈希。
5. 使用 Python 3.11+ 生成对应源码附件：

```powershell
python tools/package_native_sources.py --output dist/0.2.5/k-player-0.2.5-native-sources.zip
```

6. 对 APK 和源码 ZIP 生成 `SHA256SUMS.txt`，核验 ZIP 内每个原始源码归档与清单哈希一致。
7. 将源码提交到 GitHub，为对应提交创建版本标签和 Release，上传 APK、原生源码 ZIP 与校验文件。应用源码使用该标签自动生成的 Source code ZIP / tar.gz。

历史发布说明保留，不将失败日志、旧 APK、本机 SDK、Gradle 缓存或签名配置上传到代码仓库。当前版的实际测试结果见 [0.2.5 验证记录](verification/v0.2.5.md)。
