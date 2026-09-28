# TVPlayer — 小米电视万能播放器

针对 **小米电视（55" 4K / Cortex-A17 / Mali-760MP4 / 2GB DDR3 / 8GB eMMC / Android 5.1）**
优化的本地+网络视频播放器，带**自动中文字幕匹配**。

## 功能概览

| 类别 | 功能 |
|---|---|
| 播放 | ijkplayer 内核，H.264/HEVC/MPEG2 MediaCodec 硬解，RMVB/DivX/WMV/VC-1 等自动降级软解；AC3/EAC3/DTS 音轨 FFmpeg 解码为 PCM |
| 字幕 | 文件名解析 + OpenSubtitles 指纹双通道，聚合 Assrt（射手）/ FakeSub / OpenSubtitles 三源评分排序，自动下载最优中字并渲染 |
| 浏览 | 本机存储 + USB 热插拔 + SMB2/3 网络共享（可编译期关闭，省 ~2MB） |
| 网络播放 | SMB→127.0.0.1 HTTP Range 桥，零落盘直读 |
| 容错 | 硬解失败自动关硬解重建；休眠唤醒自动断点恢复；播放卡死看门狗自动恢复（最多 2 次） |
| 体积 | 单 ABI (armeabi-v7a)、R8 全量混淆、资源收缩、字幕缓存 LRU 上限 |

## 项目结构

```
app/src/main/java/com/tvplayer/universal/
├── browse/     LocalSource(本机+USB探测) SmbSource(smbj SMB2/3) FileSource 抽象
├── data/       Prefs(SharedPreferences) EventLog(运行日志) NativeLog(crash取证)
├── player/     PlayerEngine(ijk封装+硬解降级) LocalHttpBridge(SMB→HTTP Range流)
│               SeekController(快进防重叠) SystemCodecs(设备解码能力探测)
├── subtitle/   ReleaseInfo(文件名解析) OssHash(指纹) SubtitleMatcher(多源聚合评分)
│               SubtitleCache(zip解包+GBK/UTF-8嗅探+LRU) SubtitleParsers(SRT/ASS)
│               net/ AssrtSource OpenSubtitlesSource FakeSubSource
├── ui/         MainActivity(浏览) PlayerActivity(播放+休眠恢复)
│               SettingsActivity(设置/自检) SubtitleView(自绘描边字幕)
│               SubtitleController SubtitlePanelController(菜单键搜字幕面板)
│               OsdController(控制条)
```

## 构建

### 环境要求

- Android SDK（cmdline-tools 或 Android Studio），`minSdk 22` / `targetSdk 28`
- JDK 8+（compileOptions 使用 Java 8）

### 配置

复制 `local.properties.example` 为 `local.properties`，填入实际值：

```bash
cp local.properties.example local.properties
```

`local.properties` 已被 `.gitignore` 排除，不会进入版本库。文件中所有配置项留空时 Gradle 仍可构建，对应功能以空值/默认值运行。

配置项分三类：

| 类别 | 配置项 | 说明 |
|---|---|---|
| SDK | `sdk.dir` | Android SDK 绝对路径 |
| 字幕 API | `ASSRT_TOKEN` `OPENSUB_KEY` | 编译时注入 `BuildConfig`，运行时由 `Prefs` 读取 |
| SMB 默认值 | `SMB_HOST` `SMB_SHARE` `SMB_USER` `SMB_PASS` | 编译时注入 `BuildConfig`，作为首次安装默认值；运行时可在设置页修改 |

### 编译与安装

```bash
# 编译 debug 包
./gradlew :app:assembleDebug

# 安装到电视（开发者模式→网络调试，默认 5555 端口）
adb connect <电视IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 可选：关闭 SMB

编辑 `gradle.properties`，设 `ENABLE_SMB=false`，可从 APK 中移除 SMB 浏览及 smbj/bouncycastle 依赖（省约 2~3MB）。

### 体积参考

| 包类型 | 大小 | 说明 |
|---|---|---|
| debug (含 SMB) | ~9.2MB | 不混淆，含调试信息 |
| release (含 SMB) | ~3.2MB | R8 全量混淆 + 资源收缩 |
| release (关 SMB) | ~2.5MB | 移除 smbj/bouncycastle |

> ijk so（armv7a）~3.1MB 为 APK 内存储值；maven 预编译已是 Release 版。
> 若需进一步裁剪，用 `scripts/build-ijk.sh` + Docker 自编译替换。

## 字幕源

三个字幕源按评分聚合排序，自动模式取 ≥45 分的第一名：

| 源 | 匹配方式 | 评分规则 | 配置 |
|---|---|---|---|
| **Assrt（射手）** | 文件名解析（片名+集数） | 40 + vote_score/10 + 中文(+15)/非中文(-25) + 集数命中(+20) + 片名命中(+15) | `ASSRT_TOKEN`（必需） |
| **OpenSubtitles** | 文件指纹哈希 | 指纹命中基础分 + 语言加成 | `OPENSUB_KEY`（必需），可选账号 `openSubUser`/`openSubPass` |
| **FakeSub** | 文件指纹哈希 | 指纹命中基础 55 + 语言加成 | 公共实例已失效，默认关闭；有自建实例在设置页填地址 |

Assrt 作为主源，中文人工字幕库命中率最高。OpenSubtitles 免费额度 100 次/日（IP 限制）。

> Assrt 字幕直链为 HTTP 明文，已通过 `network_security_config.xml` 仅对 `assrt.net` 域名放行。

## SMB 配置

SMB 连接参数通过 `local.properties` 在编译时注入 `BuildConfig`，作为首次安装或清除数据后的默认值：

```properties
SMB_HOST=192.168.1.100
SMB_SHARE=Download
SMB_USER=username
SMB_PASS=password
```

运行时可在**设置页**修改，修改后保存在应用 SharedPreferences 中。SMB 播放通过 `LocalHttpBridge` 将 SMB 文件转为 `127.0.0.1` HTTP Range 流，视频数据零落盘。

## 遥控器按键

| 按键 | 功能 |
|---|---|
| **OK** | 暂停/继续；**长按**切换倍速 1x→1.25x→1.5x→0.75x。图标显示当前状态（暂停=双竖线，播放=三角） |
| **← →** | 快退/快进 10s（持续按住加速至 60s）；先进入控制条（按 ↓）后改为在 后退/播放/前进 间移动焦点 |
| **↓** | 进入控制条（焦点落在播放/暂停上） |
| **↑** | 焦点从控件交还根布局，恢复左右键快退/快进 |
| **菜单键** | 打开字幕搜索面板；面板内 ↑↓ 选择、OK 应用，再按菜单键或返回关闭 |
| **返回** | 退出播放 |

## 休眠唤醒恢复

小米电视休眠期间 `onPause`/`onResume` 生命周期不触发（Activity 始终在前台，系统只挂起网络和 CPU），通过 `ACTION_SCREEN_ON` 广播检测唤醒：

- 唤醒后从 `cachedPosition` 断点自动重建播放管线
- 使用 `wakeEpoch` 机制防止 `onResume` 和 `SCREEN_ON` 广播重复触发恢复
- 休眠唤醒恢复**不计入** `MAX_AUTO_RECOVER`（2 次）限制，避免与后续网络重试冲突
- 播放存活看门狗监测"应当播放却无帧推进"的卡死，自动触发恢复

## 设置页

设置页提供以下可调项（电视遥控器操作，留空视为不修改）：

- **字幕源**：Assrt Token/接口地址、OpenSubtitles Key/账号/接口地址、FakeSub 接口地址
- **自动字幕**：开关
- **字幕显示**：高度百分比（4.5%~12%）、延迟毫秒
- **音频**：OpenSL ES 输出（电视无声时切换）
- **SMB**：服务器地址/共享名/用户名/密码
- **维护**：清除字幕缓存、设备自检（存储+SMB 诊断）、提示音自检（验证声音通路）、查看运行日志

## 自编译 ijkplayer

maven 预编译的 ijk so 缺少 AC3/DTS/MPEG-2 解码器（白名单编译），DTS 片源会无声。完整版需自编译：

```bash
# 使用 Docker 构建，确保编译环境一致
scripts/build-ijk.sh
```

编译产物放入 `app/src/main/jniLibs/armeabi-v7a/`，替换 maven 版本。版本必须与 `ijkplayer-java:0.8.8` 一致，否则 JNI 方法表对不上。

## 硬件策略

- **只打 armeabi-v7a**：Cortex-A17 不支持 arm64，单 ABI 砍掉一半 native 体积
- **硬解优先，失败降级**：Amlogic 系统解码器在 4K HEVC 高码率偶发 `onError(1)`，软解兜底 1080p 以下片源
- **2GB RAM**：不预加载、tick 200ms、字幕缓存默认 256MB LRU、不引入图片加载库
- **8GB eMMC**：SMB 走 HTTP Range 桥零落盘；缓存用 `cacheDir`（系统紧张时可自行回收）
- **minSdk 22 / targetSdk 28**：留在传统存储模型，避免分区存储兼容层

## 已知限制

- 内嵌软字幕（mkv 内 ASS 轨）暂不渲染（ijk 主线无 libass），外挂+在线已覆盖主要场景
- ISO/BDMV 原盘目录导航未做（列表可见，播放不支持章节跳转）
- 断点续播、DLNA/UPnP、多 SMB 服务器收藏——体积允许时按需加
