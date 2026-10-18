# K Player 0.2.3 对应源码

应用源码采用 **GPL-3.0-or-later**，完整许可见根目录 `LICENSE`；第三方代码保留各自许可。

## 应用源码

Release 的 `Source code (zip)` / `Source code (tar.gz)` 对应 `v0.2.3-mpv` 标签，包含应用、JNI 声明、Gradle Wrapper、原生依赖版本/哈希、构建及测试代码：

- [应用源码 ZIP](https://github.com/fastchenn/KPlayer/archive/refs/tags/v0.2.3-mpv.zip)
- [应用源码 tar.gz](https://github.com/fastchenn/KPlayer/archive/refs/tags/v0.2.3-mpv.tar.gz)
- [构建说明](https://github.com/fastchenn/KPlayer/blob/v0.2.3-mpv/README.md)

按 README 使用 JDK 17、Android SDK 35 / Build Tools 35.0.0 和 Gradle Wrapper 构建。
默认构建下载已校验的官方原生二进制，不需要 NDK。若要替换原生组件，按下面步骤重建，
将对应 ABI 的十个 `.so` 放入 `playback/mpv/build/generated/mpv/jniLibs/<abi>/`，
再用 `./gradlew :app:assembleDebug -x :playback:mpv:prepareMpvNative` 打包自建库。
发布的 APK 使用维护者本地调试证书；源码不包含私钥。重新构建后可用自己的证书安装。

## 原生组件源码和构建脚本

Release 的 `k-player-0.2.3-native-sources.zip` 附件包含 mpv-android、libmpv、FFmpeg 和所有列出的原生依赖源码归档、
FreeType/libplacebo 使用的固定子模块源码、许可文本、来源清单及 SHA-256；无需依赖浮动分支。
`native-sources.json` 记录每个版本、commit、原始下载地址、归档名和哈希。
源码未作本地下游修改；mpv-android 归档里的 `buildscripts/` 包含上游构建脚本和补丁。

1. 解压归档后，可按 `native-sources.json` 查看每个组件及其子模块的准确版本。
2. 按 mpv-android `fdf74f6830c47dbaa8a22ac79726e8303f1db5af` 的 `buildscripts/README.md` 准备 Linux/macOS 构建环境。
3. 使用上游脚本下载 SDK/NDK 和依赖后，将五个未固定版本的 Git 组件（dav1d、FFmpeg、libass、libplacebo、mpv）切换到清单的 commit，
   对 FreeType/libplacebo 执行 `git submodule update --init --recursive`，再按上游说明构建 `arm64`。
   离线准备依赖时，可将相应归档去掉顶层目录后解压到 `buildscripts/deps/` 对应组件目录；`parent` 字段标明子模块的目标路径。
   Git 源码归档不包含 `.git` 元数据；需要 Git 版本探测的构建步骤可使用清单列出的仓库和固定 commit 检出。
4. 工具链是 Android NDK r30（30.0.16248370）；编译器、SDK、NDK 为独立工具链，未放入源码附件。
   libc++ 来自该 NDK，许可保留在 `licenses/libcxx.txt`，源码可从 [Android LLVM ndk-r30-release](https://android.googlesource.com/toolchain/llvm-project/+/refs/heads/ndk-r30-release/libcxx/) 获取。

组件版本、原生 APK 哈希及上游重建入口也记录在 `playback/mpv/THIRD_PARTY_NOTICES.md` 和 `native-dependencies.properties`。
若本文件位于原生源码附件内，上述仓库文件可从应用源码标签获得。

维护者重新生成此附件（Python 3.11 或更新版本）：

```sh
python tools/package_native_sources.py --output .gradle/deliverables/k-player-0.2.3-native-sources.zip
```

脚本只从清单的上游地址下载，核验锁定的 SHA-256，保留压缩源码原件，不执行下载内容。
Release 的 `SHA256SUMS.txt` 提供 APK 与原生源码附件的完整文件校验值。
