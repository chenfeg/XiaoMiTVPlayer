# TVPlayer — 小米电视万能播放器

针对 **小米电视（55" 4K / Cortex-A17 / Mali-760MP4 / 2GB DDR3 / 8GB eMMC / Android 5.1）**
优化的本地+网络视频播放器，带**自动中文字幕**。

## 三个需求的落点

| 需求 | 方案 |
|---|---|
| 尽量多格式 | ijkplayer（FFmpeg 内核）。H.264/HEVC/MPEG2 走 MediaCodec 硬解；RMVB/DivX/WMV/VC-1 等老格式自动降级 FFmpeg 软解；AC3/EAC3/DTS 音轨由 FFmpeg 解码为 PCM（系统盲区） |
| 非中文自动配中字 | 文件名解析（ReleaseInfo）+ OpenSubtitles 文件指纹双通道，聚合 AssSub（射手）与 FakeSub 两源打分排序；无中文外挂字幕时自动下载最优并渲染 |
| 省空间 | 单 ABI、R8 全量混淆、资源收缩、字幕缓存 LRU 上限、SMB 可开关（见下） |

## 模块

```
app/src/main/java/com/tvplayer/universal/
├── browse/    LocalSource(本机+USB探测) SmbSource(smbj SMB2/3) FileSource 抽象
├── player/    PlayerEngine(ijk 封装+硬解降级) LocalHttpBridge(SMB→127.0.0.1 Range流)
├── subtitle/  ReleaseInfo OssHash(指纹) SubtitleMatcher(多源聚合评分)
│              SubtitleCache(zip解包+GBK/UTF8嗅探+LRU) SubtitleParsers(SRT/ASS)
├── ui/        MainActivity(浏览) PlayerActivity(播放+菜单键搜字幕)
│              SettingsActivity(设置/自检) SubtitleView(自绘描边字幕)
```

## 构建

```bash
# 1. 安装 Android Studio（或 cmdline-tools），SDK 路径写入 local.properties
# 2. 编译
./gradlew :app:assembleDebug
# 3. 装到电视（开发者模式→网络调试，tv 默认 5555 端口 adb）
adb connect <电视IP>:5555 && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

体积实测：**debug 9.2MB / release(R8+收缩) 3.2MB**（含 SMB 浏览；未做签名）。构成（release）：
- ijk 三件套 so（armv7a，maven 预编译）≈ 3.1MB（APK 内存储值）
- dex 合计 1.8MB（debug 时为 10.8MB，R8 砍掉 80%+）
- 关闭 ENABLE_SMB 后预计 ≈ 2.5MB

> 注：maven 预编译的 ijk so 已是 Release 版，比早期估的 14MB 小得多——
> 那数字是含全部 ABI 的 aar 集合。单 armv7a 只此一份。
> 若需进一步压缩 so，用 `scripts/build-ijk.sh` 裁剪白名单自编译替换。

## 字幕源配置（重要）

- **AssSub**：<https://secure.assrt.net/api/doc> 的 v1 接口，**只用一个 token 鉴权**（没有 DeveloperID 这一项）。
  token 编译时从 `local.properties`（已 gitignore）注入 `BuildConfig.ASSRT_TOKEN`，装完即用；设置页里 Token / 接口地址两项留空即不修改。
  中文人工字幕库，命中率和质量最高，作为主源。
  接口实测（2026-09）：`GET /sub/search?token&q&cnt` 结果在 `sub.subs[]`，字段 `id/videoname/native_name/subtype/vote_score/lang.langlist/release_site`；
  `GET /sub/detail?token&id` 除整包 `url`（常是 **.rar**，客户端解不了）外还给 `filelist[]`（`f`/`s`/`url`）——
  服务端已替我们解包并逐条给出**单集直链**，所以只下需要的那一集，几十 KB 而已。
  这些直链是 **http 明文**，故加了 `res/xml/network_security_config.xml` 仅对 assrt.net 放行明文。
  注意：token 从 `local.properties` 注入 `BuildConfig`，编译进 APK 后反编译仍可读到，仅限自用包；对外分发前在 `local.properties` 里换成自己的即可。
- **FakeSub**：开源文件指纹匹配，公共实例域名已失效，默认关闭（地址留空即不启用）；有自建实例时在设置页填入地址即可。
  不需要 key，用于"改过名/无干净片名"的兜底。
- 两者结果按分数合并：assrt = 40 + vote_score/10 + 中文(+15)/非中文(-25) + 集数命中(+20) + 片名命中(+15)；
  fakesub 指纹命中基础 55 + 语言加成。
  自动模式取 ≥45 分的第一名（非中文基本不可能自动上）；菜单键可人工挑选。
  assrt 的搜索关键词一律带片名——实测单查 `S01E01` 会返回一堆别的剧。

## 遥控器按键

| 按键 | 功能 |
|---|---|
| OK | 暂停/继续；**长按**切换倍速 1x/1.25x/1.5x/0.75x。中间那个按钮的图标显示**当前状态**：暂停中是两条竖线，播放中是三角 |
| ← → | 快退/快进 10s（持续按住 60s）；**只有先按过 ↓ 主动进过控制条**，这两个键才改为在 后退/播放/前进 之间移动焦点，按 ↑ 出来后恢复快退快进 |
| ↓ | 进控制条（焦点落在中间的播放/暂停上） |
| ↑ | 把焦点从控件上交还给根布局（根布局自己也 focusable、不画焦点框）—— 所以平时左右键始终是快退/快进，也不会有按钮留着高亮 |
| 菜单键 | 在线搜索中文字幕（结果列表可人工选）；面板开着时 ↑↓ 走候选、OK 换字幕，再按菜单键或返回只关面板 |
| 菜单键弹出面板(因遥控器无其他按键) | 显示/隐藏字幕 |
| 音轨键 | 多音轨切换 |
| 返回 | 退出播放（播放进度不保存，后续可加断点） |

## 硬件策略说明（为什么这么设计）

- **只打 armeabi-v7a**：A17 不支持 arm64 之外的新指令集路径，x86 无关，单 ABI 直接砍掉一半 native 体积。
- **硬解优先、失败即整机关闭硬解重建**：Amlogic 系统解码器在 4K HEVC 高码率上偶发 `onError(1)`，软解兜底可救 1080p 以下片源；4K 超码率片源在 A17 上没有第二条路，提示降级而非闪退。
- **2GB RAM**：不预加载、tick 200ms、字幕缓存默认 256MB 上限 + LRU、不引入 Coil/Glide（图片解码 OOM 是低端 TV 首号崩溃源，本片列表无图）。
- **8GB eMMC**：SMB 播放走 127.0.0.1 HTTP Range 桥，零落盘；缓存目录用 cacheDir（系统紧张时可自行回收）。
- **minSdk 22 / targetSdk 28**：target 不超过 28 可留在传统存储模型，避免分区存储兼容层带来的代码量。

## 已知边界（路线图）

- 内嵌软字幕（mkv 内 ASS 轨）暂不渲染（ijk 主线无 libass），外挂+在线已覆盖主要场景；后续可评估 ijk 分支的 subtitles 滤镜。
- ISO/BDMV 原盘目录导航未做（列表可见，播放不支持章节跳转）。
- 断点续播、DLNA/UPnP、多 SMB 服务器收藏 —— 体积允许时按需加。
- 字幕字体：系统自带思源黑体含全 CJK，未打包字体；若电视 ROM 缺字体会回退到打包子集字体方案。
