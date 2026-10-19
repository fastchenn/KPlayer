# 通过 GitHub 更新和发布

GitHub 仓库是源码的维护入口，不需要在本机同步、编译或上传 APK。签名采用 0.3.1 的固定发布证书，后续版本可以覆盖升级 0.3.1。

## 云端验证结果

2026-10-19（Asia/Shanghai）已完成[Signed release 云端构建验证](https://github.com/fastchenn/KPlayer/actions/runs/39194464509)。133 项应用及播放器 Release 单元测试、10 项发布工具回归检查通过，Lint 无错误，正式 APK 签名和 16 KB 对齐检查通过。下载云端产物后，另行核对了附件 SHA-256、关闭调试、ARM64 架构以及与 0.3.1 一致的证书指纹。

该次运行的发布选项关闭，未创建新版本，也未替换现有 0.3.1 Release。自动发布步骤已配置，供后续递增版本时运行。

## 修改代码

可以在手机 ChatGPT 中连接 GitHub，让 AI 修改 `fastchenn/KPlayer` 的源码并提交到 `main`。也可以通过 PR 审阅后合并。每次主分支更新都会执行 `Android checks`：单元测试、Lint、Release/Debug 和设备测试 APK 编译。CI 不会自动把每次代码提交发布成新版本。

## 只获取签名正式包

1. 打开仓库的 **Actions → Signed release → Run workflow**。
2. 选择 `main`，保持 **Publish a new version** 不勾选，运行工作流。
3. 成功后，在该次运行底部的 Artifacts 下载 `k-player-<版本>-signed`，解压后安装其中的 APK。

此入口会重新检查 Release 测试、Lint、关闭调试、固定证书、版本和 ARM64 架构，并提供签名与校验信息。不会修改已有 GitHub Release。

## 发布新版本

可以直接告诉连接 GitHub 的 AI：“更新 KPlayer，完成后通过 Signed release 发布 0.3.2 正式版”。具体步骤为：

1. 在 `app/build.gradle.kts` 更新 `versionName` 和递增的 `versionCode`，例如 0.3.2 / 10。
2. 同步 `playback/mpv/native-sources.json` 的 `release`、`SOURCES.md`、README 下载链接以及 `docs/releases/v<版本>.md`。整理本次真实验证范围；播放和原生依赖没有变化时不要虚构设备测试。
3. 提交到 `main`，检查 `Android checks`。
4. 打开 **Actions → Signed release → Run workflow**，选择 `main`，勾选 **Publish a new version** 后运行。
5. 工作流构建和验证固定证书签名的正式 APK，生成原生对应源码及校验附件，再为该次运行的准确提交创建 `v<版本>` 标签和最新正式 Release。

发布要求版本名和版本代码都高于此前版本。已有 Release 不会被替换，已有标签不得指向其他提交。新 Release 先以草稿创建，附件上传完成后才公开；若发布中途失败，先检查草稿及日志，不能直接覆盖已有正式发布。

## 固定签名和备份

仓库的 **Settings → Secrets and variables → Actions → Repository secrets** 使用 `KPLAYER_RELEASE_SIGNING` 保存加密的签名配置，内容包括密钥库的 Base64、密钥库密码、别名和密钥密码。签名步骤只在手动运行的 `main` 工作流中使用该 Secret；PR 检查不使用它。私钥不进入 Git 源码、公开附件或构建日志，运行期间的临时密钥文件在签名后删除。

固定证书 SHA-256：

```text
83aeeb18e7986ca34bad312dc1c0374235002cb30974cc7a8b50fa0d9b550f44
```

工作流会拒绝其他证书或可调试 APK。不要生成新密钥替换现有配置，否则会再次打断覆盖升级。维护者仍需保留独立的密钥库、密码和别名备份；日常代码维护和发布不依赖本机源码或本机在线。

0.2.5、0.3.0 使用不同的旧签名，无法直接覆盖到 0.3.1 或后续固定证书版本；卸载会清除应用数据。

## Fork 仓库

Fork 包含源码和工作流，不包含原仓库的 Actions Secrets。Fork 默认无法使用此固定证书签名；分叉开发者需要配置自己的密钥及对应的证书指纹。不同签名的 APK 不能直接覆盖此仓库的正式版。维护者应审阅合并进 `main` 的代码，尤其是工作流和签名脚本，并谨慎授予仓库写权限。
