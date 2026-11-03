# 项目开发指南

## 1. 项目范围

本仓库包含两个工程：

- `Android/`：Android 音频播放器，应用名为「音频播放器」，包名为 `com.nanahoshi.audioplayer`，使用 Jetpack Compose。
- `DesktopDemo/`：Windows 桌面播放器，使用 WPF / .NET 8。

仓库地址：<https://github.com/nanahoshi114/AudioPlayer.git>。

本指南主要适用于 Android 工程。下文源码目录相对于 `Android/app/src/main/java/com/nanahoshi/audioplayer/`；构建命令在 `Android/` 下执行。

Android 支持本地音频、B 站音轨、YouTube 独立音轨、asmr.one 作品及用户在 DLsite 购买的内容。当前产品行为以代码为准，功能变更应同步更新 `Android/README.md` 及相关接口文档。本文件保留稳定的开发约束，不累积逐次构建结果或临时调试记录。

## 2. 开发与交付

### 2.1 构建与验证

```sh
cd Android
export GRADLE_USER_HOME="$HOME/.gradle"
./gradlew :app:compileDebugKotlin --no-configuration-cache
```

- Kotlin 2.2.10，AGP 9.4.1，compileSdk / targetSdk 37，minSdk 24，Java 11。
- 需要访问本机 Gradle 缓存或网络时，在沙箱外执行命令。定向单测使用 `--tests`，并保留 `--no-configuration-cache`。
- 单元测试不得调用 Android 的 `org.json`，解析测试使用适合 JVM 的实现。
- 当前环境存在 JDK 25 回退 Kotlin JVM 24、加密偏好 API 弃用及 `android.disallowKotlinSourceSets` 提示。除非任务涉及相关问题，否则无需为消除提示调整依赖或配置。
- 真机为 Realme RMX5010，沙箱内无法通过 adb 访问。安装应用须有用户明确授权；设备测试的编译通过与实际运行应分别说明。

### 2.2 版本控制与安全

- 未经用户明确要求，不执行提交或推送。提交说明使用简短中文，前缀采用 `feats:`、`fix:` 或 `docs:`。
- 正式 APK 由 Android Studio 签名，输出至 `Android/app/release/app-release.apk`。`Android/app/build/` 和 `Android/app/release/` 均不纳入版本控制。
- 口令、Cookie、SESSDATA、refresh token 等凭证不得写入日志、提交说明或文档。错误信息不得泄露凭证或完整临时媒体地址。

## 3. 文档与源码索引

### 3.1 文档

- `Android/README.md`：用户功能说明。
- `Android/docs/bilibili-api.md`：B 站搜索、投稿、播放、导入和下载。
- `Android/docs/youtube-api.md`：Android / DesktopDemo 的 YouTube 实现及验证限制。
- `Android/docs/asmr-one-api.md`、`Android/docs/dlsite-api.md`：作品接口、登录与文件读取。
- `Android/docs/whisper-api.md`：听写接口与本机模型。
- `Android/docs/update-api.md`：更新检查与发布约定。

### 3.2 源码

- `ui/components`：通用顶栏、多选栏、来源标签和空状态。
- `ui/library`、`ui/playlist`：音频库、作品页、文件预览和播放列表。
- `ui/player`、`playback`：播放界面、队列、播放服务及字幕读取。
- `ui/asmr`：在线搜索、作品详情、「我的」及已购买列表。
- `bilibili`、`ui/bilibili`：搜索、投稿、分 P 操作、播放地址、收藏夹、字幕及登录。
- `youtube`、`ui/youtube`：搜索、列表、音轨播放下载、签名脚本及 Cookie 管理。
- `asmr`、`dlsite`：作品元数据、远端文件和授权。
- `ui/importing`：剪贴板预览及确认导入。
- `ui/settings`、`ui/theme`：设置、外观、模型配置及更新检查。
- `ui/download`、`data`：任务管理、文件管理、凭证、听写和翻译。
- `data/db`：Room 实体、查询与迁移。
- `AppGraph.kt`：在 `Application.onCreate` 中按依赖顺序组装服务。

## 4. 数据与文件约束

### 4.1 数据库

- `AppDatabase` 当前版本为 **16**，`exportSchema = false`。新安装按实体建表，升级使用显式 `MIGRATION_*`；不支持数据库降级。
- 新增可空列时，迁移中的 `ALTER TABLE ... ADD COLUMN` 不设置 `DEFAULT`，实体字段不设置 `@ColumnInfo(defaultValue)`，确保新装与升级结构一致。现有 `endpoint`、`translatedAt`、`translateStatus`、`skipTranslate`、`model`、`biliImportPart` 等列遵循此规则。
- `skipTranslate` 为 `null` 或 `false` 的旧任务，听写完成后仍执行翻译。旧任务 `model` 为空时保留原有按地址匹配的兼容逻辑。
- 修改曲目、作品、下载、队列或播放列表时，应保留曲目身份及字幕关联；文件复用不得通过删除并重建曲目实现。

### 4.2 下载与删除

- 下载任务页使用「音频 / 字幕」分段选择，分别保留位置；音频包含完整包，字幕包含听写和翻译。
- 下载管理展示已落盘的在线音频、B 站缓存音轨、生成字幕及作品下载目录中的 `lrc/srt/vtt`，并显示文件大小；顶栏下载入口展示进行中的任务。
- 在线曲目优先播放有效的本地副本。删除已下载文件时，保留在线曲目，清理完成的下载记录及对应生成字幕。
- DLsite 完整原始包使用独立作品任务和原始目录清单；下载、解压和事务发布全部成功后才替换本地关联并删除压缩包，失败不影响已有副本、不自动降级为 Play 音轨。移除音频库内容不连带删除原始清单身份或磁盘下载。
- 原始文件按作品号和完整相对路径关联，保留匹配曲目编号、Play 身份及字幕关系；不得只按文件名前缀或去掉扩展名合并不同音轨。原包独有曲目不得伪造 Play 文件身份，缺少本地文件时仅可重新下载完整包。
- `localUri` 为 App 目录绝对路径时可删除文件；为识别关联的 `content:` 或 `file:` URI 时仅清除关联，保留用户文件及文件夹。LOCAL 曲目不适用此删除操作。
- 删除生成字幕时，清理生成文件及已完成的听写、翻译记录；保留进行中的听写、原有字幕文件和远端字幕。仅删除字幕不得连带删除音频。
- 未知文件通过 `FileProvider` 或可转授的 content URI 在外部打开；远端文件暂存于 `cacheDir/external-open`，不入库、不传递登录凭证。暂存目录在下载及当前预览页使用期间受保护，退出后参与缓存清理；失败和取消清理本次半成品。
- 「清理缓存」仅处理 `cacheDir`，不处理 `filesDir` 中的下载、字幕或听写暂存。默认上限 200 MB，自动清理按修改时间从早到晚执行，并保留 Coil 的 `journal*`。

## 5. 界面与播放

### 5.1 导航与通用行为

- 底栏包括音频库、播放列表、在线搜索、我的、设置。作品页和作品详情保留底栏及播放条，隐藏主页面顶栏；其他独立功能页隐藏主页面顶栏和底栏。
- 未入库内容使用 `PlayerClient.playQueueItem`；仅对 `track.id > 0` 的已入库曲目使用 `playTrack` 追加逻辑。
- 队列弹层使用 `sheetGesturesEnabled = false`，隐藏默认把手，通过关闭按钮、遮罩或返回键关闭；保留列表滚动及拖动排序。
- 作品和搜索卡片的标签使用 `BadgeLine`，RJ 号固定显示，超出宽度的标签收起为 `+N`；`LabelBadge` 保持单行。
- `BadgeLine` 使用 `BoxWithConstraints`，不得直接放入 Material3 `ListItem` 等会查询固有尺寸的内容槽；带此标签的列表卡片使用普通 `Row` / `Column` 布局。
- 主题与系统栏图标使用应用实际主题状态；需要判断深浅时使用 `LocalDarkTheme`。封面取色仅在选择该模式后执行，取色失败时保留已有颜色。

### 5.2 音频库与作品页

- 作品页路由为 `library/work/{folderId}`，使用独立的 `LibraryViewModel`，不复用音频库列表实例。
- 音频库作品页和在线详情共用 `WorkFileBrowser` 两层布局；在线详情保留图库、试听和原有操作，图库可打开缩放大图。
- 作品页使用两层滚动：向下先滚外层，外层到底后滚文件列表；向上先滚文件列表，到顶后滚外层。`WorkPageScrollConnection` 负责协调，文件标题、来源切换和路径栏位于文件 `LazyColumn` 外。进入子文件夹仅重置内层位置。
- 作品页的列表、播放队列及多选顺序固定为 `LibrarySort.NAME`。路径栏始终显示，以「根目录」为起点；返回操作逐层上移，到作品根后退出，不越过作品根。
- 同一作品含 DLsite 和 asmr.one 两套文件时，显示、播放和作品页缓存均以 `workFileSource` 为准。已有 Play 文件时直接允许来源切换；否则在后台确认购买状态，不阻塞页面打开。
- 缓存来源选择使用已有购买信息：已购买或已有 Play 文件，且 asmr.one 也有曲目时，先选择来源。无需选择时，作品页使用当前来源，作品页外优先使用 DLsite。选定 DLsite 后直接下载整个作品的原始压缩包并自动解压，不以已有 Play 文件或单曲本地副本代替完整包。
- asmr.one 缓存同目录同名前缀、仅扩展名不同的音频时，使用 `findFormatConflicts` 确认格式组合；无冲突文件正常处理。DLsite 完整包不做格式筛选，保留全部原始文件。普通文件夹维持整目录播放和下载行为。
- 作品内部的曲目、文件及子文件夹禁止单独从音频库移除；所有入口共用作品归属判断，仓储事务再次校验。整部作品或包含整部作品的普通父目录仍可移除，批量操作包含未被整作品覆盖的受限条目时整体拒绝。删除下载副本、播放列表及队列移除不受此限制。
- 多选中包含作品文件夹时，仅提供删除已下载文件和移除操作。播放列表从音频库多选添加曲目。
- 普通文件夹内隐藏来源筛选，显示和递归搜索不受根目录来源限制；范围为当前目录及后代，不返回范围外祖先、不触发在线 RJ 提示，返回根目录恢复筛选。导入菜单不提供新建文件夹，内部目录创建保留。
- 音频库搜索匹配文件夹名、作品标签、曲目名和文件名；纯数字同时匹配作品编号。作品内部结果按 `workRootOf` 归并至最近作品根，避免重复展示作品。

### 5.3 本地文件识别

- 普通本地文件夹识别为作品时，优先匹配已购买且可访问的 DLsite 文件，失败后匹配 asmr.one；应区分连接失败与内容不匹配。
- 本地目录可缺少作品文件，但不得包含作品文件树之外的内容。识别成功应保留曲目编号、本地路径和字幕关联。
- 已入库作品使用 `recognizeLocalTree`，按当前 `workFileSource` 匹配系统选择的目录，仅更新匹配曲目的 `localUri`，不新建曲目或删除原文件夹。
- 已下载至 App 目录的曲目保留下载副本；同一本地文件已关联其他曲目时跳过并说明，避免覆盖关联或违反唯一索引。

## 6. 在线来源与导入

### 6.1 在线搜索与 B 站

- 三个搜索来源提交新查询（包括相同条件）、改变筛选或排序时重置分页与列表位置，作品搜索同时展开折叠栏；追加分页、切来源和详情返回保留位置，旧响应不得覆盖新查询。
- 在线搜索来源顺序为「作品 / B站 / YouTube」，分别保留输入、结果及滚动位置。音频库「导入在线音频」仅切换至在线搜索并恢复状态；在线保存统一放入库根目录。
- B 站提供视频和 UP 主模式。纯数字在视频模式中为关键词，在 UP 主模式中为 UID；明确的 BV 或主页链接优先识别。
- 分页失败保留已加载内容和页码，中止后不自动续读；取消旧请求并阻止旧响应覆盖新查询。访问受限应显示错误，不作为空结果处理。
- 点视频只播放所选 P，不自动入库。所有 B站入库入口共用多 P 方式选择：分开导入按勾选保存，统一导入保存全部 P 为一个带「多P」标签的视频项。分 P 页面按 page 排序并支持多选，禁止部分移除；整组移除允许。
- 未入库 B 站队列以 `workno=bvid`、`fileKey=cid` 保存身份，使用稳定观看页链接恢复；播放时解析 DASH 音轨，不将观看页当作媒体。
- 搜索下载使用 `enqueueBiliImports`，持久化每 BV 的批次和入库请求；统一下载等待全部 P 有效后事务发布。`enqueueBili` 默认不入库，保留纯缓存语义。`biliImportPart` 兼容旧 BiliPart JSON 和含批次身份的元数据，不保存媒体临时地址。
- B站视频分组使用独立的 `folders.biliBvid` 唯一标识，不复用 asmr 字段；15→16 添加可空列（无 DEFAULT）及 `bili_import_intents`。转换先迁移全部既有曲目，再移除空分组，保留身份与关联。新的保存、转换或移除使旧批次失效，下载回调不得恢复过时分组。
- 已有下载任务可补充自动入库意图，完成时须重新读取任务信息。成功后在事务中复用或保存曲目、关联下载并登记完成；失败不新增曲目；只有用户确认组织方式转换时才调整既有曲目归属。
- B 站缓存仅保存音轨，视频字幕写入 `filesDir/bilibili/{bvid}/{cid}.json`。播放优先使用本地字幕，删除音轨时同时删除该缓存字幕。
- B 站字幕需要有效登录。`code = 0` 且字幕为空、`login_mid = 0` 时应提示重新登录，并保留生成字幕入口；列表或正文因限流为空时，刷新播放信息后最多重试一次。
- 从下载管理播放未入库 B 站音轨时，以队列的 `workno` 和 `fileKey` 获取身份，不依赖 `trackId`。

### 6.2 登录与授权

- B 站扫码登录将 Cookie 和 refresh token 存入加密偏好，约每 20 小时检查更新需求；刷新完成后确认旧 token 作废。
- 粘贴 SESSDATA 或 Cookie 不提供自动续期；保存内容改变时清除 refresh token。输入在保存前不参与请求，替换已有扫码身份前需用户确认。`bili_ticket` 和 buvid 不视为登录身份。
- DLsite 登录使用 WebView。登录后通过 `mergeDlsiteCookies` 过滤 `cf_`、`__cf`、`_cfuvid` 等条目后保存，退出时清理 WebView Cookie。
- DLsite 试听虽使用 `DLSITE` 来源，但 `fileKey` 为空；此类请求不携带 DLsite 登录凭证。
- DLsite 部分下载及听写前置下载先选择完整原始包、Play 部分文件或取消；试听除外。原始包使用独立的桌面浏览器标识、登录会话和受限重定向，避免进入手机文件列表；不能把 Play 媒体凭证发送到任意下载主机；临时地址不得落库或进入日志。原始文件音质以作者上传内容为准，不承诺必为无损编码。

### 6.3 YouTube

- 登录采用粘贴 Cookie，支持 LL/WL 私人列表，不提供网页登录或自动续期。账号变化后取消旧列表请求并清空结果。
- `TrackSource.YOUTUBE` 的身份保存在 `tracks.youtubeVideoId`，使用可空唯一索引；12→13 迁移不设置默认值。未入库队列使用 `workno=视频编号`、`fileKey=audio` 和稳定观看页地址，临时媒体地址不落库。
- 仅选择独立 `audio/mp4` 音轨，不使用带视频格式或 WebM/Opus 兜底，不读取 YouTube 原生字幕。当前不支持 PO Token；可用性受视频和网络环境影响。
- 签名组件复用桌面端 cjs，在隐藏 WebView 中适配。隔离 `globalThis.location`，禁止联网、文件访问和 Java 桥，不修改 DLsite 的全局 Cookie；媒体 CDN 请求不携带用户登录 Cookie。
- 音轨保存至 `filesDir/youtube/{videoId}/audio.m4a`。每个 HTTP 请求最多读取 1 MiB，校验 `Content-Range` 后拼接；断流最多重试当前段两次，保留完整段，不改为无界 Range 整段读取。
- 中止时仅清理本次 `.part`，不得删除重试任务正在使用的暂存。下载完成后在事务中自动入库并关联下载，同视频复用已有曲目。
- 队列、播放列表、下载管理和生成字幕均须保留视频身份；删除本地副本后仍可恢复在线播放。

### 6.4 剪贴板导入

- `MainActivity` 每次进入前台，仅在 RESUMED 且窗口有焦点后读取一次剪贴板。Activity 级 `ClipboardImportViewModel` 保留旋转期间的预览和导入状态。
- 仅识别 BV、RJ 和 YouTube 单视频链接或分享文字，明确视频链接优先于标题中的编号。只解析文本和 HTTP(S) URI，不打开 `content:` URI，不执行剪贴板 Intent，不记录原文，不清空用户剪贴板。
- `ClipboardImportController` 先读取标题和封面，确认后导入根目录，不自动播放或下载。B站多 P 进入共用入库选择，分开导入默认全选，RJ 使用 asmr.one，YouTube 预览使用 `videoInfo` 的官方 oEmbed，不解析媒体或执行脚本。
- 取消和新候选通过请求代号隔离旧响应，导入中禁止重复确认或替换条目。
- `clipboard_import` 偏好仅保存候选身份与复制时间的 SHA-256 摘要。API 26 以下仅按身份去重；同一份已处理内容不重复提示。

## 7. 听写、翻译与字幕

### 7.1 任务生命周期

- 听写结果保存至 `filesDir/subtitles/{sha256}.json`。文件内容、服务地址和模型相同，且生成文件仍存在时复用结果；地址或模型改变时重新生成。无有效模型信息的已完成记录不按地址直接复用。
- 「生成字幕」设置 `skipTranslate = true`；「生成字幕和翻译」才在听写后翻译。重试保留原设置。
- 手动翻译和润色由 `TranslateCenter` 独立协程执行；离开播放页仅取消界面等待。自动润色在译文落盘后执行，润色失败保留译文及翻译成功状态。
- 中止仅取消本地任务。Qwen3-ASR 无服务端取消接口；SenseVoiceSmall 在片段间响应取消，JNI 解码与 release 不得并发。听写等待下载时，取消听写不连带取消音频下载。
- 选择 DLsite 完整原始包的听写任务绑定指定下载任务，等待解压和目标文件关联完成；目标缺失时明确失败，不被其它压缩音轨下载提前唤醒。旧任务空依赖字段保留原兼容逻辑。
- `filesDir/whisper-src/` 暂存文件保持无后缀，上传时根据文件头设置格式。任务结束、取消或重试后仅清理无任务引用的暂存；重试优先复用原文件，其次从曲目本地文件复制。SenseVoiceSmall 直接读取本地文件，不使用上传暂存。

### 7.2 模型与网络

- 听写模型为 `qwen-audio-3.1-asr`、`Qwen3-ASR` 和 `SenseVoiceSmall`。地址及密钥按模型独立保存，运行中的任务使用其自身配置；地址为空时仍保存模型选择。
- `qwen-audio-3.1-asr` 固定使用阿里云接口和 `qwen-audio-3.1-asr-flash-filetrans` 模型，经临时上传后轮询结果，不上传超过 1 GB 的文件。
- Qwen3-ASR 要求非空地址，先读取模型名再请求 `/v1/audio/transcriptions`，语言为日语；保留服务返回的分段时间，只有整段文字时覆盖整首时长。保存地址不自动补 `/v1`。
- SenseVoiceSmall 使用 sherpa-onnx，在 arm64 设备离线运行，`endpoint` 为 `null`，双方为空时仍可去重。就绪状态使用 `SenseVoiceModelManager.isReady()`。
- SenseVoiceSmall 固定使用 `int8-2024-07-17` 多语种包及 `version.txt` 校验；`2025-09-09` 包为粤语模型，不可替代。使用 Silero VAD 将 16 kHz 单声道音频切成不超过 28 秒的片段，拼接时保留时间偏移。
- 已移除模型的偏好迁移至 `qwen-audio-3.1-asr`，不生成虚假密钥；旧模型的待执行任务明确失败。
- Android 17 下，Qwen3-ASR 访问局域网、本机或 `.local` 地址前申请 `ACCESS_LOCAL_NETWORK`。使用 `isLocalNetworkAddress` 判断，仅解析字面 IP，不额外发起 DNS 查询。
- 翻译平台限定千问 AI 平台和 DeepSeek，地址固定，密钥与模型名按平台独立保存。平台地址、默认模型及思考参数集中在 `data/TranslatePlatforms.kt`，修改时保留各平台的字段差异；思考内容 `reasoning_content` 不写入字幕。

### 7.3 字幕交互

- 字幕按钮仅在字幕就绪、无当前句子且结果不是「没有听出人声」时显示；句间空隙不触发按钮显示。
- 字幕全文列表点击句子后跳转至句子起始时间，保留列表页面；播放页当前句用于打开全文列表。

## 8. 关于与更新检查

- 「设置 → 关于」通过 PackageManager 读取实际安装包的版本名和构建号，不使用界面常量代替。
- `AppUpdateClient` 使用独立匿名请求读取固定仓库的 `/releases?per_page=100`，只接受 `tag_name` 以 `android-` 开头、非草稿/预发布且包含已上传 APK 的发布；桌面端发布不参与 Android 更新检查。版本按去掉 `android-` 前缀后的 tag 比较，无法解析时不推断版本关系，本地版本较高时不提示降级。
- Android 发布标签统一为 `android-X.Y`（例如 `android-1.3`），桌面端使用自己的标签；历史 `v1.0`～`v1.2` 不带 `android-` 前缀，不会被新版检查识别。发布新 APK 时同步递增 `versionCode` 和 `versionName`。
- 检查由用户手动触发；应用不自动检查、下载或安装更新。下载入口通过外部浏览器打开固定仓库的发布列表或 tag 页面，不使用附件下载地址、WebView 或下载管理服务。
- `browser.invalid` 仅用于 PackageManager 查询浏览器，不发起网络请求。接口及发布要求见 `Android/docs/update-api.md`。
