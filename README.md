# K Player

安卓 WebDAV 播放器 K Player。手机端、中文、深色界面，最低 Android 8.0。当前版本为 `0.2.3-mpv` 测试版。

## 这一版的范围

- 媒体库、添加/编辑/移除连接、目录浏览、当前目录搜索与筛选、mpv 播放页面、最近记录空态、设置页面。
- 使用真实的 WebDAV `PROPFIND` 获取目录，支持 Basic 认证、中文及特殊字符路径、同源跳转、加载失败重试。
- 连接信息通过 Android Keystore 的 AES-GCM 密钥加密后保存在本机；不上传配置，不申请本地文件访问权限，不允许系统备份连接配置。
- 离线示例媒体库有明确的“示例”标识，文件为页面预览数据，不会请求服务器，也没有可播放视频。
- 打包官方 libmpv、FFmpeg 与 JNI 库，接入画面、音频、真实进度、缓冲、暂停、拖动、倍速和内嵌音轨/字幕选择。
- 自动发现同目录的 SRT、ASS、SSA、VTT 外挂字幕，支持同名、语言/字幕标记后缀（如 `.sc/.tc/.chs/.cht`）及保留视频扩展名的命名（如 `.mkv.ass`）；所有匹配轨道均可在字幕菜单切换或关闭，支持 WebDAV 认证及跨源直链跳转。
- 音频焦点处理、前台播放保持亮屏、切后台暂停、Surface 重建和退出资源释放。
- 播放控制接口和独立的 mpv 模块，预留后续 ExoPlayer 接入。

默认 APK 支持 **arm64-v8a**。首次构建会下载并校验 mpv-android 官方发布包，只提取原生库；无需在本机安装 NDK。
原生库来源、准确版本、许可与源码重建入口见 [第三方组件说明](playback/mpv/THIRD_PARTY_NOTICES.md)。
用户已验证 WebDAV 视频播放和同名/语言后缀外挂字幕可用；硬件解码、更多 NAS 与片源兼容性仍需更多设备验证。
示例媒体库始终是离线预览，不请求服务器。观看记录和断点续播尚未实现，相关设置不会虚假宣称生效。

## 下载与许可

从 [GitHub Releases](https://github.com/fastchenn/KPlayer/releases) 下载最新测试版 ARM64 APK。
`0.2.3-mpv` 汇总 mpv 接入、跨服务器直链修复及外挂字幕匹配/切换；不补发开发过程中的中间 APK。
当前为调试签名测试包，可覆盖安装之前由同一维护者证书签名的测试版；CI 构建使用临时调试证书，不能保证与 Release 安装包互相覆盖。

Copyright (C) 2026 fastchenn。K Player 应用源码采用 **GPL-3.0-or-later**，详见 [LICENSE](LICENSE)。
第三方源码保留各自的版权和许可证。Release 同时提供原生组件源码附件和校验值；应用及原生组件的对应源码入口见 [SOURCES.md](SOURCES.md)。

## 打开和构建

1. 在 Android Studio 中打开项目根目录（包含 `settings.gradle.kts` 的目录），选择 JDK 17。
2. 安装 Android SDK Platform 35 和 Build Tools 35.0.0，等待 Gradle 同步。
3. 首次同步需要联网获取原生发布包和 Maven 依赖。运行 `app` 到 Android 8.0 或更新的 ARM64 设备。

Windows 命令：

```powershell
.\gradlew.bat :playback:mpv:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

macOS / Linux：

```sh
chmod +x gradlew
./gradlew :playback:mpv:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

需要本机 Android SDK 路径时，在不提交到 Git 的 `local.properties` 中填写 `sdk.dir=...`。Gradle Wrapper 和原生发布包均启用了 SHA-256 校验。
CI 工作流运行单元测试、Lint、APK 构建和原生测试 APK 构建，不会发布应用。

连接匹配 ABI 的 Android 设备后，可以运行原生测试；测试在本地生成 PCM 音频和 RGB AVI 视频，
检查原生解码、进度/结束事件、重复初始化/释放，以及带认证的 HTTP 视频读取、GPU 画面像素、暂停、拖动、倍速、重新打开、外挂字幕解码和实际文字渲染：

```powershell
.\gradlew.bat :playback:mpv:connectedDebugAndroidTest
```

x86_64 模拟器需要额外打包对应的官方原生库，PowerShell 中请给含逗号的参数加引号：

```powershell
.\gradlew.bat '-PmpvAbis=arm64-v8a,x86_64' :playback:mpv:connectedDebugAndroidTest
```

固定工具版本：AGP 8.9.2、Gradle 8.11.1、Kotlin / Compose Compiler 2.1.20、Compose BOM 2025.04.01、JDK 17。

## 代码结构

```text
app/
  .../data/             连接加密存储、WebDAV、离线示例
  .../ui/               Compose 页面与主题
  .../AppViewModel.kt   页面状态、导航与请求生命周期
playback/api/           内核无关的播放接口、状态、能力与请求
playback/mpv/           libmpv/JNI、播放状态与逐文件视频流转发
```

`PlayerEngine` 的时间单位统一为毫秒。请求头只放在内存中的 `PlaybackRequest`，不写入观看历史；轨道 ID 属于各自内核。UI 不直接调用 mpv API。画面通过 `Surface` 交给内核，后续 ExoPlayer 可用 `setVideoSurface` 对接。

## 播放链路

1. 点击真实媒体文件后，播放器等待有效的 Surface 再创建原生会话，设置 GPU、AudioTrack 和硬件解码偏好。
2. 每个文件创建一个只监听 `127.0.0.1` 的临时流转发端点，使用随机路径，只接受当前文件的 GET/HEAD 和 Range 读取。
3. OkHttp 向 WebDAV 发起请求，处理认证并使用 Android 的证书信任校验。视频下载允许跳转到其他服务器的直链；跨源后不再转发 WebDAV 认证、Cookie 和私有请求头，保留 User-Agent 等公开请求信息和 Range/If-Range。每次读取从 WebDAV 原地址重新获取直链，不缓存可能过期的签名 URL；不允许 HTTPS 降级到 HTTP。目录浏览继续限制在连接范围内。
   libmpv 不接收服务器账号、密码或认证头，也不直接打开带凭据的服务器地址。
4. 从完整目录列表匹配外挂字幕：以完整视频文件名或去掉视频扩展名的名称为基准，接受完全同名或以分隔符连接的常见语言/字幕标记，不会仅凭一段相似前缀匹配其他集数。为每个字幕创建独立的认证流端点；使用原始 WebDAV 字幕地址，不从视频跳转后的 CDN 目录猜测路径。视频加载后异步添加全部匹配字幕，首个成功加载的外挂轨道自动选中；用户手动选轨或关闭后保留用户选择。字幕不可读不影响视频播放。
5. 从原生事件回传真实的时间、时长、可拖动状态、播放/暂停/缓冲/结束和音轨列表。秒与公共接口的毫秒在内核边界转换。
6. 停止或更换文件时关闭视频及字幕流端点、取消未完成的请求并销毁原生会话；旧会话回调不能覆盖新文件状态。后台播放尚未开放，切后台会暂停。

下一阶段优先进行真实设备和 NAS 的播放验证，再补观看记录、断点续播、手动导入字幕、后台播放和画中画。

官方参考：[mpv-android](https://github.com/mpv-android/mpv-android)、[mpv 手册](https://mpv.io/manual/stable/)。

## 当前兼容范围

- WebDAV 连接先支持匿名与 Basic 认证；Digest、OAuth、客户端证书和自签名证书配置尚未实现。
- 可以连接用户填写的 HTTP 服务，HTTPS 使用系统信任校验。不会跳过证书验证。
- 不递归扫描全库；每次读取当前目录。单次 XML 响应上限 4 MiB。
- 播放针对 WebDAV 中的普通视频文件，不支持 HLS/DASH 播放列表及任意外部引用；字幕仅从当前目录明确匹配的文件加载。
- 外挂字幕自动匹配示例：`电影.mkv` 对应 `电影.srt`、`电影.sc.ass`、`电影.tc.ass`、`电影.chs.ass`、`电影.cht.ass`、`电影.zh-CN.srt`、`电影.zh-Hant.ass`、`电影.mkv.ass`、`电影.mkv.chs.ass`；也支持 `.ssa` / `.vtt`，匹配忽略大小写。
- 常见语言/字幕标记可通过点、空格、下划线、连字符或括号连接，如 `电影[简体].ass`、`电影.chs&eng.ass`、`电影.en.SDH.srt`、`电影.zh-Hans.forced.srt`。保留原始文件名供菜单区分；未知的任意后缀、其他目录、手动导入和 IDX/SUB 双文件字幕尚未接入。
- 最近记录、断点续播、后台播放、画中画和 ExoPlayer 属于后续阶段。
- 当前没有要求用户提供服务器账号；真实服务器和真机上的连通性仍需实际配置后验证。

## 验证

目录解析及请求测试覆盖中文/特殊字符路径、父目录过滤、同源路径范围、认证失败、Basic 头、跳转方法保留和凭据隔离；字幕匹配测试覆盖用户提供的完整动画文件名、简繁/语言标记、保留视频扩展名、组合标记、多扩展名、中文/特殊字符、大小写、去重及相邻集数/跨目录/跨源排除。
mpv 模块单元测试覆盖视频字节范围读取、HEAD、分块响应、同源/跨源跳转、认证失败、停止取消连接和原生播放状态转换。
Android 原生测试检查外挂字幕的实际文字解码和 GPU 像素、五条外挂字幕同时加载及逐条切换、关闭/重新开启、重新打开、换片清除、跨源跳转、字幕缺失不影响视频及延迟加载期间保留手动关闭选择。
实际执行结果和尚未验证的部分见 `BUILD-RESULTS.txt`。
