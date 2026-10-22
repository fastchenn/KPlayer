# 连续快进后的缓存白条回归

日期：2026-10-22（Asia/Shanghai）。

## 问题与修复

缓存读取请求在收到上游响应头之前被快进取消时，原逻辑将取消当作缓存读取失败，返回直连降级。回环服务随后会将本次播放的时间区间记录标记为不可用；因此后续数据仍能落盘，白条却不再增加。这一公共读取路径不依赖视频格式。

修复将主动取消与真实网络/存储失败区分开：旧请求直接结束，不再触发直连降级。同一次跳转内被新字节请求替换的预读也使用取消结果。请求在探测文件信息前即记录所属跳转，过期探测不得在新跳转中启动旧位置的下载；新增块下载与跳转取消同步，等待块数据后也再次检查请求是否过期。

时间区间采样同时校验应用跳转序号与原生播放器跳转序号，避免跳转前已排队的采样进入新位置的白条记录。

## 回归用例

新增五项检查：连续四次在响应头到达前快进后仍能增加不连续白条；同一次跳转内替换预读不降级；文件信息探测期间快进不启动旧位置；原生跳转前的排队采样被丢弃；真实 HTTP 回环取消旧请求后不补发直连请求，且新位置仍能生成白条。

最先加入的连续快进与原生采样两项检查，已在修复前确认失败、修复后通过。

## 本机验证结果

应用 67 项、播放器 97 项 Release 单元测试（共 164 项）全部通过，无失败、错误或跳过；10 项发布工具检查通过。Release Lint 无错误、7 项警告；Release/Debug APK 及应用、播放器两组设备测试 APK 均编译通过。

## 验证命令

```powershell
.\gradlew.bat :playback:mpv:testReleaseUnitTest :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :playback:mpv:assembleDebugAndroidTest --console=plain --no-daemon
python -m unittest discover -s tools -p test_release.py -v
```

本轮未运行真机或模拟器自动回归。设备测试 APK 的编译通过不代表设备测试已执行；具体片源和手机上的体验仍需实测。
