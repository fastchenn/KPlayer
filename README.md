# K Player

安卓 WebDAV 播放器 K Player 的第一阶段工程壳子。手机端、中文、深色界面，最低 Android 8.0。

## 这一版的范围

- 媒体库、添加/编辑/移除连接、目录浏览、当前目录搜索与筛选、播放预览、最近记录空态、设置页面。
- 使用真实的 WebDAV `PROPFIND` 获取目录，支持 Basic 认证、中文及特殊字符路径、同源跳转、加载失败重试。
- 连接信息通过 Android Keystore 的 AES-GCM 密钥加密后保存在本机；不上传配置，不申请本地文件访问权限，不允许系统备份连接配置。
- 离线示例媒体库有明确的“示例”标识，文件为页面预览数据，不会请求服务器，也没有可播放视频。
- 播放控制接口和独立的 mpv 模块，预留后续 ExoPlayer 接入。

**当前 APK 没有打包 libmpv，也不能解码或播放视频。** `MpvPlayerEngine` 明确返回“组件未接入”，播放按钮、倍速、音轨、字幕选择保持禁用，没有模拟进度。偏好设置已保存，接入真正的播放组件后生效。最近记录不会把未成功播放的文件当作观看历史。

## 打开和构建

1. 在 Android Studio 中打开项目根目录（包含 `settings.gradle.kts` 的目录），选择 JDK 17。
2. 安装 Android SDK Platform 35 和 Build Tools 35.0.0，等待 Gradle 同步。
3. 运行 `app` 到 Android 8.0 或更新的设备。当前壳子不需要 NDK。

Windows 命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

macOS / Linux：

```sh
chmod +x gradlew
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

需要本机 Android SDK 路径时，在不提交到 Git 的 `local.properties` 中填写 `sdk.dir=...`。Gradle Wrapper 已包含，发行包启用了 SHA-256 校验。CI 工作流会运行同样的检查并生成调试 APK，不会发布应用。

固定工具版本：AGP 8.9.2、Gradle 8.11.1、Kotlin / Compose Compiler 2.1.20、Compose BOM 2025.04.01、JDK 17。

## 代码结构

```text
app/
  .../data/             连接加密存储、WebDAV、离线示例
  .../ui/               Compose 页面与主题
  .../AppViewModel.kt   页面状态、导航与请求生命周期
playback/api/           内核无关的播放接口、状态、能力与请求
playback/mpv/           mpv 接入边界（当前未绑定原生库）
```

`PlayerEngine` 的时间单位统一为毫秒。请求头只放在内存中的 `PlaybackRequest`，不写入观看历史；轨道 ID 属于各自内核。UI 不直接调用 mpv API。画面通过 `Surface` 交给内核，后续 ExoPlayer 可用 `setVideoSurface` 对接。

## 下一步接入 mpv

1. 选择并固定 libmpv 的 Android 构建及 JNI 绑定，优先验证 arm64-v8a；保留第三方许可证与构建来源。
2. 在 `playback/mpv` 内初始化原生库，接好 Surface、音频输出、属性观察和资源释放。
3. 把 mpv 的秒转换为公共接口的毫秒；映射加载、缓冲、播放、暂停、结束、错误状态。
4. 逐文件配置认证头并明确跨源重定向策略，避免把服务器凭据发送到其他来源。当前目录浏览拒绝跨源跳转。
5. 再开放播放控制、音轨/字幕选择、真实观看进度与偏好应用。当前播放页的这些控件只是禁用布局。
6. 用真实手机和片源验证拖动、ASS 字体、音频焦点、切后台、旋转及网络中断，再接入 ExoPlayer。

官方参考：[mpv-android](https://github.com/mpv-android/mpv-android)、[mpv 手册](https://mpv.io/manual/stable/)。mpv-android 本身不是可直接导入的 AAR。

## 当前兼容范围

- WebDAV 连接先支持匿名与 Basic 认证；Digest、OAuth、客户端证书和自签名证书配置尚未实现。
- 可以连接用户填写的 HTTP 服务，HTTPS 使用系统信任校验。不会跳过证书验证。
- 不递归扫描全库；每次读取当前目录。单次 XML 响应上限 4 MiB。
- 最近记录、字幕、后台播放、画中画和实际视频播放属于后续阶段。
- 当前没有要求用户提供服务器账号；真实服务器和真机上的连通性仍需实际配置后验证。

## 验证

目录解析及请求测试覆盖中文/特殊字符路径、父目录过滤、同源路径范围、认证失败、Basic 头、跳转方法保留和凭据隔离。实际执行结果见随包的 `BUILD-RESULTS.txt`。
