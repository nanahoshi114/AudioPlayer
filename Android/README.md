# 音频播放器（Android）

应用名「音频播放器」，包名 `com.nanahoshi.audioplayer`，面向 Android 的多来源音频播放器；支持本机文件、asmr.one、DLsite 已购内容、Japanese ASMR、B 站及 YouTube 独立音轨，内置离/在线日文听写、中文翻译与离线缓存。

预编译 APK 见 [GitHub Releases](https://github.com/nanahoshi114/AudioPlayer/releases)（Android 发布标签以 `android-` 开头）。

## 前置环境与技术栈

| 项目 | 要求 / 版本 |
| --- | --- |
| 应用版本 | `versionName = "1.5"` / `versionCode = 6`（Release APK 由 Android Studio 签名输出至 `app/release/app-release.apk`） |
| Android SDK | `compileSdk 37` / `targetSdk 37` / `minSdk 24`（Android 7.0+） |
| 目标 ABI | 仅 `arm64-v8a`（`app/build.gradle.kts` 配置 `ndk.abiFilters += "arm64-v8a"`，内含 `sherpa-onnx` 与 `onnxruntime`） |
| 构建工具 | Gradle `9.6.0`、AGP `9.4.1`、Kotlin `2.2.10`、Gradle Daemon JDK `25`（编译目标 Java 11） |
| 核心依赖 | Jetpack Compose（BOM `2026.02.01`）、Room `2.8.5`（Schema v24）、Media3 `1.10.1`、OkHttp `4.12.0`、Coil `2.7.0` |
| 依赖镜像 | `settings.gradle.kts` 默认优先使用阿里云 Maven 镜像 |

## Quick Start

使用 Android Studio 打开 `Android/` 目录即可直接运行到 `arm64-v8a` 设备或模拟器；命令行请在 `Android/` 目录下执行（工程默认开启 configuration cache，命令行需附 `--no-configuration-cache`）：

```sh
cd Android
export GRADLE_USER_HOME="$HOME/.gradle"

# 1. 编译 Kotlin（快速校验）
./gradlew :app:compileDebugKotlin --no-configuration-cache

# 2. 构建 Debug APK（输出至 app/build/outputs/apk/debug/）
./gradlew :app:assembleDebug --no-configuration-cache

# 3. 运行 JVM 单元测试（98 个测试类）
./gradlew :app:testDebugUnitTest --no-configuration-cache
```

从仓库根目录执行可用 `Android/gradlew -p Android <task> --no-configuration-cache`。

### 必填配置

- **构建期**：仅需配置标准 Android SDK 路径（`ANDROID_HOME` 或 `Android/local.properties` 中的 `sdk.dir`），无自定义构建期环境变量或硬编码密钥。
- **运行期**：无强制项；各平台登录凭证与听写/翻译 API Key 均在 App 内按需填写并加密存储。

## 核心使用示例

以「在线作品入库 → 离线下载 → 离线听写与翻译」主链路为例：

1. **导入内容**：在「音频库」右下角导入本机文件/目录，或在「在线搜索」将作品/视频加入音频库根目录；也可在外部复制 `RJ` 编号、`BV` / YouTube / Japanese ASMR 链接后切回前台，通过剪贴板预览一键入库。
2. **离线下载**：在线内容须先入库，再从音频库单条「更多」、多选「下载所选」、库内作品页或 B 站分 P 页发起后台下载（在线曲目存在有效本地副本时优先读取本地）。
3. **生成与绑定字幕**：在「设置 → 听写和翻译」选择听写模型（如本机离线 `SenseVoiceSmall` / `Parakeet`）并保存；播放页可一键「生成字幕 / 生成字幕和翻译」，或在曲目/作品页批量关联 `.lrc/.srt/.vtt`。

## 支持来源与文档索引

| 来源 / 模块 | 能力摘要 | 登录 / 凭证要求 | 详细文档 |
| --- | --- | --- | --- |
| 本地音频/目录 | SAF 导入保留目录结构；支持将本地目录关联或识别为作品 | 无需登录 | — |
| asmr.one | 作品搜索/筛选/排序、文件树、剪贴板 RJ、下载格式冲突确认 | 无需登录 | [docs/asmr-one-api.md](docs/asmr-one-api.md) |
| DLsite | 商店搜索、商品页与试听、已购 Play 文件与完整原始包（ZIP/RAR/分卷）下载解压 | WebView 登录 / 粘贴 Cookie（试听免登） | [docs/dlsite-api.md](docs/dlsite-api.md) |
| Japanese ASMR | 按 RJ 核对收录、篇目音轨/HLS/章节、应用内验证与整作下载（Buzzheavier/MEGA） | 无需登录（按需应用内网页验证） | [docs/japaneseasmr-api.md](docs/japaneseasmr-api.md) |
| B 站 | 视频/UP 主搜索、分 P 分开/统一导入、收藏夹、合集/系列在线列表、自带字幕缓存 | 扫码（自动续期）/ Cookie（字幕需登录） | [docs/bilibili-api.md](docs/bilibili-api.md) |
| YouTube | 搜索与播放列表、独立 `audio/mp4` 音轨（WebView 签名）、分段断点下载 | 粘贴 Cookie（私人列表 LL/WL，无 PO Token） | [docs/youtube-api.md](docs/youtube-api.md) |
| 听写与翻译 | 4 种 ASR 模型（云端/自部署/本机离线）+ 2 个翻译平台、后台队列、校对/合并/润色 | 本机模型免密钥；在线模型/翻译填 API Key | [docs/whisper-api.md](docs/whisper-api.md) |
| 关于与检查更新 | 手动查询 `android-*` GitHub Releases 并交由外部浏览器下载 APK | 匿名请求 | [docs/update-api.md](docs/update-api.md) |

## 核心目录与架构

源码位于 `Android/app/src/main/java/com/nanahoshi/audioplayer/`：

- **根级入口**（`AppGraph.kt`、`AppNavHost.kt`、`AppRoutes.kt`、`MainShell.kt`）：`Application.onCreate` 手动依赖装配、五底栏（音频库/播放列表/在线搜索/我的/设置）路由与全局弹层。
- **`ui/`**：Jetpack Compose 页面与组件（音频库/作品页、播放列表、在线搜索、播放页、下载与任务、设置、剪贴板导入与主题）。
- **`playback/`**：Media3 `PlaybackService`（`mediaPlayback` 前台服务）、队列、在线流解析及字幕加载与桌面字幕。
- **`data/`**（含 `data/db/`）：仓储、加密凭证、后台任务服务（音频下载、字幕任务、模型下载 3 个 `dataSync` 前台服务）及 Room v24 本地库（21 张表，显式迁移）。
- **`asmr/`、`dlsite/`、`japaneseasmr/`、`bilibili/`、`youtube/`**：各在线来源的网络客户端、解析器、签名脚本与鉴权逻辑。

## 进阶配置、测试与限制

<details>
<summary>听写模型与翻译平台配置速查</summary>

| 类别 | 名称 | 地址 / 模型 | 必填项与约束 |
| --- | --- | --- | --- |
| 听写（云端） | `qwen-audio-3.1-asr` | 固定 `https://dashscope.aliyuncs.com`（`qwen-audio-3.1-asr-flash-filetrans`） | 仅填 API Key；文件上限 1 GB，轮询上限 30 分钟 |
| 听写（自部署） | `Qwen3-ASR` | 用户填写基址（保存时不自动补 `/v1`，请求时补） | 必填地址与 API Key；局域网/本机/`.local` 在 Android 17 需授予 `ACCESS_LOCAL_NETWORK` |
| 听写（本机） | `SenseVoiceSmall` | 本机离线 INT8（`int8-2024-07-17`） | 免密钥；GitHub 约 155 MiB / HF 约 229 MiB（`arm64-v8a`） |
| 听写（本机） | `Parakeet 0.6B 日语（CTC）` | 本机离线 INT8（`35000-int8`） | 免密钥；GitHub 约 467 MiB / HF 约 625 MiB（`arm64-v8a`） |
| 翻译 | 千问 AI 平台 | 固定 `https://maas.qianwenaiapi.com/compatible-mode/v1`（默认 `qwen3.8-flash`） | 必填 API Key；模型名与深度思考（低/中/高）按需配置 |
| 翻译 | DeepSeek | 固定 `https://api.deepseek.com`（默认 `deepseek-flash`） | 必填 API Key；模型名与深度思考（高/最高）按需配置 |

日文校对/语义合并软限制、自动润色及任务重试机制详见 [docs/whisper-api.md](docs/whisper-api.md)。

</details>

<details>
<summary>运行期进阶设置与本地文件管理要点</summary>

- **存储与并发**：「设置 → 存储」提供「下载管理」（音频 / 字幕分段）、「同时下载任务数」（1–5，默认 3）与「清理缓存」（仅清理 `cacheDir`，默认上限 200 MB，不删除 `filesDir` 中的已下载音频与字幕）。
- **删除副本与字幕保留**：所有来源删除本地音频副本时均保留在线曲目；若音频带有生成字幕会询问是否一并删除（B 站视频自带字幕随音轨删除）。仅删除生成字幕不影响手动字幕绑定。
- **字幕优先级与悬浮窗**：加载优先级为「手动关联（含 ±0.5s 微调） > 本机/作品同源及跨来源借用（默认开启） > 远端或 B 站字幕」；桌面字幕需授予 `SYSTEM_ALERT_WINDOW`。
- **本地目录关联**：普通目录关联为作品时，优先匹配已购 DLsite，其次匹配 asmr.one；本地目录可少于作品文件，但不可多出作品外文件。

</details>

<details>
<summary>构建提示、模拟器测试与已知限制</summary>

- **构建告警**：本机若使用 JDK 25，Kotlin 会提示回退到 JVM 24 目标，属正常提示而非错误。
- **模拟器仪器测试**：本机可配合 headless `arm64-v8a` 模拟器运行 Room、迁移等 46 个非 UI 仪器测试类（`class` 参数一次仅支持单个测试类；Compose UI 测试因旧版 Espresso 反射调用 `InputManager.getInstance()` 限制不可用）：

```sh
cd Android
export GRADLE_USER_HOME="$HOME/.gradle"
./gradlew :app:connectedDebugAndroidTest --no-configuration-cache \
  -Pandroid.testInstrumentationRunnerArguments.class=com.nanahoshi.audioplayer.data.StoredCatalogOverlayAndroidTest
```

- **外观与存储边界**：
  - 「设置 → 外观」支持主题模式（跟随系统 / 浅色 / 深色）与主题色（系统取色 / 封面取色 / 预设色）。
  - 顶栏下载入口打开的「下载与任务」区分「音频 / 字幕任务」分段；进程被系统终止后，排队中的 Japanese ASMR 整作下载与未开工的听写/自动翻译会在下次打开应用时接手。
- **已知平台限制**：
  - 下载入口统一在音频库及库内详情（仅已入库的 Japanese ASMR 篇目在在线详情行额外提供快捷下载）。
  - YouTube 仅选用独立 `audio/mp4` 音轨，不支持带视频兜底、WebM/Opus 或 PO Token。
  - DLsite 完整原始包支持 ZIP/RAR/分卷（不执行自解压程序），RAR 字典上限 64 MiB，解压需同时预留压缩包与解压结果的双重空间。

</details>
