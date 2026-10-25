# 交接

Android 音频播放器，应用名「音频播放器」，包名 `com.nanahoshi.audioplayer`。听本机文件、B 站音轨、asmr.one，以及用户自己在 DLsite 买过的作品。界面是 Jetpack Compose。仓库 `https://github.com/nanahoshi114/AudioPlayer.git`。

产品说明以当前代码为准。`README.md` 和 `docs/` 落后于最近几次功能，不要为了交接去改它们，除非用户明确要求。

## 编译

```sh
export GRADLE_USER_HOME="$HOME/.gradle"
./gradlew :app:compileDebugKotlin --no-configuration-cache
```

需要本机 Gradle 目录和网络时，命令在沙箱外跑。单测同样加 `--tests`。单元测试不要调用 Android 的 `org.json`。

Kotlin 2.2.10，AGP 9.4.1，compileSdk / targetSdk 37，minSdk 24，Java 11。本机 JDK 25 时，Kotlin 会退回 JVM 24，这是原有提示。`EncryptedSharedPreferences` / `MasterKey` 的弃用警告、`android.disallowKotlinSourceSets` 也是原有提示。不要为了清掉这些去改依赖或配置。

真机是 Realme RMX5010。adb 在沙箱里看不到设备。用户没要求时不要安装。

正式包由 Android Studio 签到 `app/release/app-release.apk`。`app/build` 和 `app/release` 都不要提交。

## 代码地图


| 位置            | 做什么                                                                            |                   |
| ------------- | ------------------------------------------------------------------------------ | ----------------- |
| `ui/library`  | 在线搜索、作品详情、我的、已购买                                                               | 音频库、收藏夹导入、图片和文本预览 |
| `ui/playlist` | 播放列表                                                                           |                   |
| `ui/player`   | 正在播放、底部小条、字幕按钮                                                                 |                   |
| `ui/asmr`     |                                                                                |                   |
| `ui/download` | 进行中的下载、听写、翻译；已落盘的文件在「我的」的下载管理                                                  |                   |
| `playback`    | 播放服务、在线音频、字幕读取                                                                 |                   |
| `bilibili`    | 播放地址、收藏夹、字幕、扫码登录                                                               |                   |
| `asmr`        | asmr.one                                                                       |                   |
| `dlsite`      | 商店、登录、已购 Play                                                                  |                   |
| `data`        | 库、下载、凭证、听写、翻译                                                                  |                   |
| `data/db`     | Room，当前第 12 版                                                                  |                   |
| `AppGraph.kt` | 在 `Application.onCreate` 里组装。顺序是 downloads、subtitles、translator、whisper、player |                   |


底栏四页：音频库、播放列表、在线搜索、我的。作品详情、已购买、收藏夹、正在播放、下载、下载管理、文件预览是单独一页。

## 不要改坏

- 还没保存进库的队列项用 `PlayerClient.playQueueItem`。`playTrack` 只在 `track.id > 0` 时追加。
- DLsite 试听行的 source 是 `DLSITE`，但 `fileKey` 为空。空 `fileKey` 不要带上 DLsite 登录。
- 字幕按钮只在字幕已就绪、当前没有句子、且不是「没有听出人声」时出现。句子之间的空隙不要把按钮露出来。
- 字幕全文列表点一行跳到该句起始，列表留在原页。正在播放页上的当前句仍用来打开全文列表。当前句颜色：深色 `0xFF90CAF9`，浅色 `0xFF1565C0`。
- 听写结果写在 `filesDir/subtitles/{sha256}.json`。同一文件内容、同一听写地址、同一模型、字幕文件还在时，不重新上传。换地址或换模型会再跑并覆盖。旧记录没有模型时，仍按地址对应的原接口匹配，不强迫重跑。
- 「生成字幕」设 `skipTranslate = true`，不请求翻译。「生成字幕和翻译」才在听写完成后翻译。重试沿用原来的 `skipTranslate`。
- 手动「生成翻译」跑在 `TranslateCenter` 自己的协程里。离开正在播放页只取消等待，不取消这次翻译。
- 删除生成字幕时，删生成文件、已完成的听写记录和翻译记录。不要删进行中的听写，不要删同目录里原有的 lrc/srt，也不要删远端字幕。
- 「我的」里的下载管理列出已经在手机上的 asmr.one / DLsite 音频、这些音频的生成字幕，以及该作品下载目录里的 lrc/srt/vtt。`filesDir/subtitles` 里其余生成字幕也会列出来。生成字幕的列表名是音频名加上 `.json`。顶栏下载图标仍是进行中的任务。删音频时清掉本地文件和完成的下载记录，作品留在库里；同时删这首的生成字幕。不删进行中的听写，也不删下载目录里原有的 lrc/srt。只删字幕行时不删音频。
- 音频库里还不是作品的本地文件夹，可以用「识别为作品」对照 asmr.one 的文件树。本地可以少文件或文件夹，不能多出作品里没有的。对上以后曲目 id 和本地路径保持不变，已经生成的字幕还在。
- 中止只停手机上的这次 HTTP。本机听写服务没有取消接口。听写还在等下载时，不要把音频下载一起取消。错误文案恰好是「已中止」时，下载列表不要显示「查看返回」。
- 听写按保存的模型选接口。每个模型各自保存地址和密钥；切换下拉只显示该模型已保存的内容，保存和清除也只作用于当前模型。进行中的任务继续用它自己的模型密钥。`whisper-1` 和 `qwen-audio-3.1-asr` 是线上模型：界面不显示地址，只保存 API Key；请求分别固定走 `https://api.openai.com` 和 `https://dashscope.aliyuncs.com`，不使用地址栏里留下的内容。`whisper（本地部署）` 是另一项，必须填写地址和口令，请求与 `whisper-1` 相同（没有 `/v1` 时补上，模型名仍发送 `whisper-1`，日语，`verbose_json`，分段时间），超过 25MB 不询问。没保存过模型时，地址路径去掉末尾斜杠后以 `/v1` 结尾且域名是 `api.openai.com` 视为 `whisper-1`，其他以 `/v1` 结尾的地址视为 `whisper（本地部署）`，否则视为本机任务接口。`Qwen3-ASR` 地址不能空：先读取服务上的模型名，再请求 `/v1/audio/transcriptions`，日语。返回分段时间则按段写字幕；只有整段文字时写成覆盖整首时长的一条。本机任务接口地址也不能空。保存时地址为空仍保存所选模型。`whisper-1` 读超时 10 分钟，文件超过 25MB 时先询问，确认后仍上传，取消则记为「已中止」。`qwen-audio-3.1-asr` 在 5 分钟内走官方 SDK，更长的先临时上传再轮询。本机任务接口仍是 `POST /jobs`。保存听写地址时不要自动补 `/v1`。翻译地址缺少 `/v1` 时保存后补上。
- 本地复制进 `filesDir/whisper-src/` 的文件名没有后缀。上传时按文件头补 `mp3` / `flac` / `m4a` / `aac` / `wav` / `ogg`，不要改磁盘上的暂存文件名。
- targetSdk 37。听写或翻译地址是局域网、本机或 `.local` 时，Android 17 要在请求前申请 `ACCESS_LOCAL_NETWORK`。公网地址不申请。判断用 `isLocalNetworkAddress`，字面 IP 才解析，不要为了判断去做 DNS。
- 文案不要用「家里电脑」。翻译说明只写通用大模型，不要点名某个专用翻译模型。
- 不要把口令、Cookie、SESSDATA、refresh token 写进日志、提交说明或文档。



## B 站登录

扫码登录把 Cookie 和 refresh token 存进加密偏好。大约每 20 小时检查一次，B 站要求更新时换新 Cookie，并确认旧 token 作废。粘贴 SESSDATA 或整段 Cookie 不会自动更新；若内容和已保存的不同，会清掉 refresh token。

文字框在「SESSDATA登录」弹窗里，没点保存之前不参与请求。已经扫码登录又改了文字再保存，必须先让用户确认。`bili_ticket` 和 buvid 会单独更新，它们不是登录身份。

## 数据库

`AppDatabase` 版本是 12，`exportSchema = false`。新安装用实体建表，升级只走 `MIGRATION_*`。Room 不能降级：手机上已是 12 时，装回更低版本会在启动时崩溃。

新增可空列：`ALTER TABLE ... ADD COLUMN` 不要写 `DEFAULT`，Kotlin 字段不要加 `@ColumnInfo(defaultValue)`。`endpoint`、`translatedAt`、`translateStatus`、`skipTranslate`、`model` 都是这样。`skipTranslate` 为 `null` 或 `false` 时，旧任务听写完成后仍翻译。`model` 为空的旧任务仍按地址匹配，不强迫重跑。

## 提交

用户没明确要求时不要提交，也不要推远端。说明用简短中文，前缀用 `feats:`、`fix:`、`docs:`。