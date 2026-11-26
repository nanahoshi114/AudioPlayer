# 项目开发指南

## 1. 工程范围

- `Android/`：Android 音频播放器（应用名「音频播放器」，包名 `com.nanahoshi.audioplayer`，Jetpack Compose），源码基准 `Android/app/src/main/java/com/nanahoshi/audioplayer/`。
- `DesktopDemo/`：Windows 桌面播放器（WPF / .NET 8）。
- 仓库：<https://github.com/nanahoshi114/AudioPlayer.git>。
- 产品行为以代码为准，功能变更须同步更新 [`Android/README.md`](Android/README.md) 及相关文档；本文件只保留稳定约束，不累积调试日志。
- 新增约束前，先判断能否通过自动化工具（如 Lint、单元测试、编译检查）覆盖；仅无法自动化的项目特有硬性约束才写入本文件。

## 2. 高频验证命令

- 工具链：Kotlin `2.2.10`、AGP `9.4.1`、Gradle `9.6.0`、`compileSdk`/`targetSdk 37`、`minSdk 24`、Java `11`、仅 `arm64-v8a` ABI。
- 风格检查：代码风格为 `kotlin.code.style=official`（无独立 ktlint / detekt / CI），使用 `:app:lintDebug` 静态检查。

```sh
cd Android
export GRADLE_USER_HOME="$HOME/.gradle"
./gradlew :app:compileDebugKotlin --no-configuration-cache
./gradlew :app:lintDebug --no-configuration-cache
./gradlew :app:testDebugUnitTest --no-configuration-cache
# Debug APK：./gradlew :app:assembleDebug --no-configuration-cache
# 定向单测：./gradlew :app:testDebugUnitTest --no-configuration-cache --tests 'com.nanahoshi.audioplayer.<ClassOrMethod>'
# 模拟器仪器测试：见 Android/docs/testing.md
```

- 需访问本机 Gradle 缓存或网络时在沙箱外执行；所有 Gradle 命令保留 `--no-configuration-cache`。
- JVM 单元测试禁调 Android `org.json`，解析测试使用适合 JVM 的实现。
- 勿为消除 JDK 25 回退 Kotlin JVM 24、加密偏好 API 弃用或 `android.disallowKotlinSourceSets` 提示而随意改依赖或配置。
- 真机（Realme RMX5010）与模拟器安装应用均须用户明确授权；编译通过、模拟器运行与真机系统交互验证须分别说明。

## 3. 项目硬性红线

### 3.1 UI、版本与安全

- 修改任何 UI、主题、组件、布局、图标、文案视觉或动效前，必须先完整阅读并严格遵守 [`DESIGN.md`](DESIGN.md)。
- 禁止在 Kotlin 中硬编码用户可见中文 UI 文案与无障碍标签，统一写入 `strings*.xml`。
- 未经用户明确要求，禁止执行 `git commit` 或 `git push`。
- 提交说明使用简短中文，前缀采用 `feats:`、`fix:` 或 `docs:`。
- 正式 APK 由 Android Studio 签名输出至 `Android/app/release/app-release.apk`。
- `Android/app/build/` 与 `Android/app/release/` 均不纳入版本控制。
- 口令、Cookie、SESSDATA、refresh token 及完整临时媒体地址严禁写入日志、报错、提交说明或文档。
- DLsite 试听请求（`fileKey` 为空）与 YouTube 媒体 CDN 请求严禁携带用户登录凭证。

### 3.2 数据库与身份

- `AppDatabase` 当前版本 **24**，`exportSchema = false`；新装按实体建表，升级用显式 `MIGRATION_*`，不支持数据库降级。
- 新增可空列时，迁移 `ALTER TABLE ... ADD COLUMN` 禁设 SQL `DEFAULT`，实体字段禁设 `@ColumnInfo(defaultValue)`。
- 修改曲目、作品、下载、队列或播放列表时保留曲目身份与字幕关联，禁止通过删除并重建曲目实现文件复用。
- 作品填充与导入须串行执行、取锁后重判查重，并在同一事务提交目录与曲目，撞唯一索引不留残缺目录。
- RJ 号按字符串保留前导零（如 `RJ01234567`），禁止转为整数或去零后重建编号。

### 3.3 存储、播放、剪贴板与网络

- 区分「下载」（`filesDir`）与「缓存」（`cacheDir`）。
- 「清理缓存」仅清理 `cacheDir`，绝不触碰 `filesDir` 中的下载、字幕或听写暂存。
- 外部 `content:` 或 `file:` 的 `localUri` 仅解除关联，禁止删除用户原文件及文件夹。
- 禁止从音频库单独移除作品内部的曲目、文件或子文件夹（整部作品及含整部作品的普通父目录除外）。
- 未入库内容使用 `PlayerClient.playQueueItem`；仅对 `track.id > 0` 的已入库曲目使用 `playTrack` 追加逻辑。
- 剪贴板仅在前台 `RESUMED` 且窗口有焦点后读取文本或 HTTP(S) URI，禁读 `content:`、禁执行 Intent。
- 剪贴板仅存去重摘要，严禁记录剪贴板原文或清空用户剪贴板。
- 局域网/本机/`.local` 访问 `Qwen3-ASR` 前按字面 IP 判定申请 `ACCESS_LOCAL_NETWORK`，不额外发起 DNS 查询。

## 4. 按需文档索引

修改对应区域前先读取对应文档：

| 变更区域 / 任务 | 先读文档 |
| --- | --- |
| UI / 主题 / 组件 / 布局 / 图标 / 动效 | [`DESIGN.md`](DESIGN.md) |
| 项目结构、环境配置与构建总览 | [`Android/README.md`](Android/README.md) |
| 模拟器 AVD 与仪器测试 | [`Android/docs/testing.md`](Android/docs/testing.md) |
| 数据库迁移、下载队列、任务隐藏、删除与缓存 | [`Android/docs/data-and-downloads.md`](Android/docs/data-and-downloads.md) |
| 导航、播放列表、作品页、本地识别、在线搜索与剪贴板 | [`Android/docs/ui-and-library.md`](Android/docs/ui-and-library.md) |
| asmr.one 搜索、作品与音轨 | [`Android/docs/asmr-one-api.md`](Android/docs/asmr-one-api.md) |
| DLsite 登录、已购 Play 与完整原始包 | [`Android/docs/dlsite-api.md`](Android/docs/dlsite-api.md) |
| Japanese ASMR 网页验证、HLS 与整作下载 | [`Android/docs/japaneseasmr-api.md`](Android/docs/japaneseasmr-api.md) |
| B 站搜索、多 P 导入、登录续期、合集与字幕 | [`Android/docs/bilibili-api.md`](Android/docs/bilibili-api.md) |
| YouTube 独立音轨、WebView 签名与分段下载 | [`Android/docs/youtube-api.md`](Android/docs/youtube-api.md) |
| 听写模型、翻译平台、校对合并与字幕绑定 | [`Android/docs/whisper-api.md`](Android/docs/whisper-api.md) |
| 关于页、版本号比较与发布标签约定 | [`Android/docs/update-api.md`](Android/docs/update-api.md) |
