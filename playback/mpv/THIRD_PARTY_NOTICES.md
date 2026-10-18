# mpv 原生依赖来源

当前集成使用 [mpv-android 官方 2026-09-17 发布](https://github.com/mpv-android/mpv-android/releases/tag/2026-09-17)。
Gradle 只提取发布 APK 中的原生 `.so` 文件，不合并上游的应用代码、资源、Manifest 或权限。
上游 Kotlin JNI 声明单独保留在 `src/main/java/is/xyz/mpv/MPVLib.kt`，必须与对应的 `libplayer.so` 一起升级。

## 下载和校验

版本与 SHA-256 固定在 `native-dependencies.properties`。默认支持 `arm64-v8a`，可使用
`-PmpvAbis=arm64-v8a,x86_64` 额外打包模拟器架构。首次联网构建会下载约 34 MB 的 ARM64 官方 APK；
原始下载缓存到 Gradle 用户目录的 `caches/kplayer-mpv/<sha256>.apk`，提取结果保存在模块的 `build/generated/mpv/jniLibs/`。
每次重新提取前都会核验下载文件；校验不匹配时构建失败，不加载其他来源的库。
先执行一次 `:playback:mpv:prepareMpvNative` 后可以离线构建。不要将下载 APK 或生成的 `.so` 提交到 Git。

原生库包括 libmpv、上游 JNI libplayer、libc++、libavcodec、libavdevice、libavfilter、libavformat、libavutil、libswresample 和 libswscale。

## 源码版本和许可

下表版本来自官方发布说明。MIT JNI 绑定和完整的原生构建脚本位于
[mpv-android fdf74f6830c47dbaa8a22ac79726e8303f1db5af](https://github.com/mpv-android/mpv-android/tree/fdf74f6830c47dbaa8a22ac79726e8303f1db5af)。
第三方组件的原始版权声明与许可文本随 APK 保存在 `assets/mpv/licenses/`；应用设置中的“开源组件与许可”提供来源说明。

| 组件 | 版本 / revision | 上游源码与许可 |
| --- | --- | --- |
| mpv-android JNI | fdf74f6830c47dbaa8a22ac79726e8303f1db5af | [MIT](https://github.com/mpv-android/mpv-android/tree/fdf74f6830c47dbaa8a22ac79726e8303f1db5af) |
| libmpv | 0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6 | [GPL-2.0-or-later 构建](https://github.com/mpv-player/mpv/tree/0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6) |
| FFmpeg | 094a2f8a2a5e7fa64736e067de224ce28fdf5979 | [GPL-3.0-or-later 构建](https://github.com/FFmpeg/FFmpeg/tree/094a2f8a2a5e7fa64736e067de224ce28fdf5979) |
| Mbed TLS | 3.6.7 | [Apache-2.0 / GPL-2.0-or-later](https://github.com/Mbed-TLS/mbedtls/tree/v3.6.7) |
| dav1d | 362bc1138228390a0a521ecf3af18b23c6afeb9e | [BSD-2-Clause](https://github.com/videolan/dav1d/tree/362bc1138228390a0a521ecf3af18b23c6afeb9e) |
| libxml2 | 2.15.4 | [MIT](https://github.com/GNOME/libxml2/tree/v2.15.4) |
| fontconfig | 2.18.3 | [HPND](https://gitlab.freedesktop.org/fontconfig/fontconfig/-/tree/2.18.3) |
| FreeType | 2.14.3 | [FTL / GPL-2.0-or-later](https://github.com/freetype/freetype/tree/VER-2-14-3) |
| FriBidi | 1.0.16 | [LGPL-2.1-or-later](https://github.com/fribidi/fribidi/tree/v1.0.16) |
| HarfBuzz | 14.4.0 | [MIT](https://github.com/harfbuzz/harfbuzz/tree/14.4.0) |
| libunibreak | 8.0 | [Zlib](https://github.com/adah1972/libunibreak/tree/libunibreak_8_0) |
| libass | b2fe9d8770678a7b5271387d38c20657ebf3429a | [ISC](https://github.com/libass/libass/tree/b2fe9d8770678a7b5271387d38c20657ebf3429a) |
| Lua | 5.2.4 | [MIT](https://www.lua.org/ftp/lua-5.2.4.tar.gz) |
| libplacebo | 3330a515d62139259c26239014f286e233bd3a5c | [LGPL-2.1-or-later](https://github.com/haasn/libplacebo/tree/3330a515d62139259c26239014f286e233bd3a5c) |
| curl | 8.21.0 | [curl/MIT](https://github.com/curl/curl/tree/curl-8_21_0) |
| Android NDK / libc++ | r30 | [Apache-2.0 WITH LLVM-exception](https://github.com/llvm/llvm-project/blob/llvmorg-22.1.0/libcxx/LICENSE.TXT) |

## 从源码重建

使用上表的 mpv-android commit，并依照其
[buildscripts/README.md](https://github.com/mpv-android/mpv-android/blob/fdf74f6830c47dbaa8a22ac79726e8303f1db5af/buildscripts/README.md)
在 Linux/macOS 上构建。`buildscripts/include/depinfo.sh` 固定 SDK、NDK 和有版本号的依赖；
运行下载脚本后，将 `buildscripts/deps/` 下的 dav1d、FFmpeg、libass、libplacebo 和 mpv checkout 到上表的 revision，
再按上游步骤构建对应 ABI。上游脚本说明 Windows/WSL 不属于支持的原生构建环境；本项目日常 Windows 构建使用校验过的官方二进制。
上游构建会包含 GPL 组件，不是仅使用 LGPL 选项的替代构建；不得把这些二进制当作 LGPL-only 分发。

## APK 分发

上游将这组二进制组合标注为 GPL-3.0-or-later。发布本应用 APK 时，除上述版权及许可文件外，
还需要按相应许可提供**与所分发 APK 对应的应用及原生依赖源码、必要修改和构建脚本**。
K Player 应用源码采用 GPL-3.0-or-later，完整文本位于根目录 `LICENSE`；第三方代码保留其自身许可。
每个发布 APK 的应用源码标签、原生源码附件和重建步骤见根目录 [SOURCES.md](../../SOURCES.md)。
原生源码清单 `native-sources.json` 固定所有归档的地址、revision 和 SHA-256，包含上游构建脚本及 FreeType/libplacebo 子模块。
维护者使用 `tools/package_native_sources.py` 生成 Release 的原生源码附件；生成的二进制和源码压缩包不提交到 Git。
