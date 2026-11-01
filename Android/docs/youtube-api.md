# YouTube 接口说明

Android 已接入 YouTube 搜索、播放列表、独立音轨在线播放和下载；Windows `DesktopDemo` 已实现查找和下载。本文记录两端当前调用方式，以及 2026-10-27 的历史只读调查。代码已经接入不代表所有视频、网络或账号都能通过 YouTube 验证。

## Android 当前实现（2026-11-01）

### 入口与调用链

「在线搜索」增加「作品 / YouTube」切换，YouTube 支持关键词、11 位视频编号、视频链接、`search_query` 链接和播放列表。输入解析与桌面端优先级相同：搜索参数优先；常见观看链接同时含视频和列表时优先单个视频；其它列表输入读列表。

- [`YouTubeClient.kt`](../app/src/main/java/com/nanahoshi/audioplayer/youtube/YouTubeClient.kt)：`lookup`、`playlist`、`accountStatus`、`resolveAudio`、`downloadAudio`。
- [`YouTubeModels.kt`](../app/src/main/java/com/nanahoshi/audioplayer/youtube/YouTubeModels.kt)：输入识别、返回字段、分页节点解析、音轨选择、地址参数和授权头处理。
- [`YouTubeSolver.kt`](../app/src/main/java/com/nanahoshi/audioplayer/youtube/YouTubeSolver.kt)：系统 WebView 适配。
- [`YouTubeScreens.kt`](../app/src/main/java/com/nanahoshi/audioplayer/ui/youtube/YouTubeScreens.kt)：搜索、列表、播放、保存、多选下载和登录卡片。

搜索最多 3 页、40 条，列表最多 5 页、200 条；按视频编号去重，返回标题、作者、封面和毫秒时长。搜索读取 `videoRenderer`，列表读取 `playlistVideoRenderer`，下一页读取 `continuationItemRenderer` 内的 token。列表后续页失败时返回已有结果并提示不完整，搜索失败则报错。搜索和列表请求可取消，账号切换会取消旧请求并清空原结果。

「我的」里的「喜欢的视频」「稍后观看」使用 `LL` / `WL`，请求 `browseId=VLLL` / `VLWL`，没有另一套私有列表接口。登录方式是粘贴整段 Cookie，保存在现有加密偏好中，不使用 Google OAuth 或 WebView 网页登录，不自动续期。保存先落盘再请求首页确认状态，确认失败仍保留已保存内容。匿名首页配置不带 Cookie，带登录的接口使用与桌面端相同的 `SAPISIDHASH` 规则。

### 播放地址与脚本

首页配置和三个客户端的请求字段见下文桌面端说明。Android 同样依次尝试 `VISIONOS → ANDROID → WEB`；最后一个结果要求登录或不可播放且有 Cookie 时，才带登录再尝试一轮。这里 `ANDROID` 是请求中声明的 YouTube 客户端身份，不是指本应用的 targetSdk。

只从 `adaptiveFormats` / `formats` 选 `audio/mp4`，优先 MIME 描述含 `mp4a` 的条目，同档按码率降序。不会采用桌面端的带视频 MP4 兜底，也不取 WebM/Opus。没有独立音轨时提示「这个视频没有可用的独立音轨」。

签名组件复用桌面端打包的 `yt-solver.cjs`，随 APK 放在 `assets/youtube/`。Android 不启动 Node.js：由 `WebViewYouTubeSolver` 提供 `node:fs` 输入、`process.stdout` 输出的适配，并在系统 WebView 中执行。

- WebView 不显示页面，只加载 `about:blank`，禁止网络、文件和 content 访问，不暴露 Java 对象，不传入 Cookie。
- 桌面脚本会设置 `globalThis.location`。Android 使用隔离的全局对象接收赋值，避免它触发 WebView 跳转；不修改 DLsite 登录用的全局 Cookie 设置。
- 每次调用独立创建和销毁 WebView，调用串行处理，执行最长等待 30 秒；取消、超时、渲染进程异常都会结束本次调用。
- 处理 `s` 和 `n`，没有实现 PO Token。脚本结构变化或旧 WebView 不支持所需语法时会失败并提示。

媒体地址仅保存在当前解析结果里，不写入数据库。每次打开在线播放或开始下载都按稳定视频编号解析地址；媒体 401 / 403 / 404 时丢弃配置并重新解析，最多重试一次。媒体请求只使用选中客户端标识、观看页 Referer 和 YouTube Origin，不向 `googlevideo.com` 转发用户的 `youtube.com` Cookie 或 Authorization。解析后的媒体地址只接受 HTTPS 的 `googlevideo.com` 及其子域。

### 入库、队列和本地文件

`TrackSource.YOUTUBE` 标识来源；`tracks.youtubeVideoId` 是可空、唯一索引的视频编号列，数据库从 12 升到 13，只新增这一列和索引，不设置默认值。作品编号、B 站 bvid/cid 不复用于 YouTube 曲目。

- 点查找结果只播放这一首，不自动入库。已保存曲目使用 trackId；未入库内容走远端队列，`workno` 携带视频编号，`fileKey=audio`，`remoteUrl` 是稳定观看页地址而非临时媒体地址。
- 队列与播放列表查询包含视频编号，冷启动恢复时重新取媒体地址；已下载时优先读取本地文件。在线播放使用现有 Media3 服务，支持后台播放和 Range 跳转。
- 「保存到音频库」只保存在线曲目；「下载」完成后自动入库，已存在的同一编号复用原曲目，不移动原文件夹。按视频编号去重下载任务。
- 下载文件为 `filesDir/youtube/{videoId}/audio.m4a`。每次运行先写独立的 `.part`，取消关闭 HTTP 并删除本次暂存，重试不会被旧请求的清理误删。启动时清理上次中断留下的 YouTube `.part`。
- 正常写完、大小与声明长度一致（长度未知时除外）、至少 1024 字节且存在 MP4 `ftyp` 文件头后，才改成正式文件并在事务中关联曲目与完成记录。没有额外转码。
- 下载页支持 YouTube 排队/进行中任务中止及失败重试。中止只作用于这次任务；已经保存的在线曲目保留。
- 下载管理以音频标题加 `.m4a` 显示文件，带大小，支持播放、导出、删除。删除音频会清掉本地路径、完成下载记录及对应生成字幕，在线曲目保留，不影响进行中的听写。
- 不读取 YouTube 原生字幕；可先下载音轨再用现有听写、翻译和润色流程。临时缓存清理仍只处理 `cacheDir`。

### 验证记录与限制

2026-11-01 Kotlin 编译及 109 项单元测试通过，离线签名/`n` 包装脚本样例通过；主机 SQLite 核对了 12→13 升级后的列结构和旧行保留。Android WebView、Room 迁移与下载完成入库的设备测试已补充并编译通过，尚未运行，需要在设备上执行后才能确认平台行为。

同日主机侧匿名搜索 `me at the zoo` 返回 14 条视频；对公开视频 `jNQXAC9IVRw` 的播放解析被要求登录或验证，没有取得可用媒体地址，因此没有完成实际媒体下载验证。这个结果不应记录为“下载成功”。本次未安装到真机，系统 WebView 的真实执行、在线播放拖动进度、后台播放和离线播放仍需设备验收。

## 桌面端的调用方式（2026-11-01 源码核对）

本节依据仓库代码整理，标记为「桌面端源码」，没有在本次更新中调用 YouTube。入口及实现：

- [`DesktopDemo/CrawlWindow.xaml.cs`](../../DesktopDemo/CrawlWindow.xaml.cs)：切换 YouTube 来源、查找、选择视频和批量下载；「喜欢的视频」「稍后观看」入口分别传 `LL`、`WL`。
- [`DesktopDemo/Crawl/YouTubeClient.cs`](../../DesktopDemo/Crawl/YouTubeClient.cs)：编号解析、登录状态、搜索、播放列表、播放地址解析和媒体下载。
- [`DesktopDemo/Crawl/yt-solver.mjs`](../../DesktopDemo/Crawl/yt-solver.mjs)：签名处理的可读源码；运行时使用打包后的 [`yt-solver.cjs`](../../DesktopDemo/Crawl/yt-solver.cjs)。
- [`DesktopDemo/Services/ServiceSettings.cs`](../../DesktopDemo/Services/ServiceSettings.cs)、[`SettingsWindow.xaml.cs`](../../DesktopDemo/SettingsWindow.xaml.cs)：Cookie 保存、清除和登录状态检查。
- [`DesktopDemo/PlayerDemo.csproj`](../../DesktopDemo/PlayerDemo.csproj)：将 `yt-solver.cjs` 复制到输出目录，并作为内嵌资源打包。

### 输入和查找顺序

桌面端源码。`LookupAsync` 按下面的顺序处理输入：

1. 有 `search_query` 参数时，解码参数（`+` 按空格处理），作为关键词搜索。
2. 解析视频编号和播放列表编号。视频编号支持 `v=`、`/shorts/`、`/embed/`、`/live/`、`/v/`、`youtu.be/` 后的 11 位字符，或单独的 11 位编号；字符限字母、数字、`_`、`-`。
3. 同时有视频和列表编号时，含 `watch`、`youtu.be` 或 `shorts` 的输入优先作为单个视频；否则优先读列表。
4. 列表支持 `list=` 后的 2～80 位编号，或单独的 `LL`、`WL`、以 `PL` 开头的 13～80 位编号。
5. 其余输入作为关键词搜索。以上是正则解析规则，并没有先校验输入的网址主机。

单视频查找直接调用播放接口解析媒体地址，失败就报错，不退回 oEmbed。搜索和列表只生成候选项，直到下载选中的视频时才读取播放地址。候选项的 `SourceId` 为 `YT{videoId}`，`Origin` 为 `youtube`；下载成功后由 `Finished` 事件把本地文件加入桌面播放器。

### 首页配置与请求约定

桌面端源码。`EnsureVisitorAsync` 先匿名请求 `GET https://www.youtube.com/`，使用桌面 Chrome 标识和 `Accept-Language: zh-CN,zh;q=0.9,en;q=0.8`，从页面提取：

| 页面字段 | 用途 |
| --- | --- |
| `VISITOR_DATA` | 写入后续 `context.client.visitorData` |
| `INNERTUBE_CLIENT_VERSION` | WEB 客户端版本 |
| `jsUrl` | 需要签名处理时下载的播放器脚本地址 |
| `STS` | 写入 `playbackContext.contentPlaybackContext.signatureTimestamp` |

这些请求没有读取或传入 `INNERTUBE_API_KEY`，地址只有 `?prettyPrint=false`。不要把旧调查中带 `key=` 的形式当成桌面端当前的必需参数。

获取到非空访客数据后，配置缓存在进程的静态字段中，没有过期时间或失败后主动刷新的逻辑；页面请求失败则退回空配置，WEB 版本使用代码中的 `2.20261029.11.00`。播放器脚本也按地址缓存在内存中。这里的版本号是当前源码值，不是固定的接口规范。

普通接口超时 20 秒，媒体请求超时 10 分钟；两个 HTTP 客户端都允许重定向和自动解压，禁用自动 Cookie 管理，使用 HTTP/1.1 或更低版本。Cookie 仅由代码显式附加，不会自动收集响应的 `Set-Cookie`。

### 播放接口与客户端回退

桌面端源码。`PlayerAsync` 依次尝试 `VISIONOS`、`ANDROID`、`WEB`，第一个能解析出候选媒体地址的结果立即返回；此时还没有请求媒体文件，不保证地址可下载。

```http
POST https://www.youtube.com/youtubei/v1/player?prettyPrint=false
Content-Type: application/json; charset=utf-8
Origin: https://www.youtube.com
Referer: https://www.youtube.com/
X-YouTube-Client-Name: {客户端编号}
X-YouTube-Client-Version: {客户端版本}
User-Agent: {对应客户端标识}
```

| `clientName` | 编号 | 版本 | 额外的 `context.client` 字段 |
| --- | --- | --- | --- |
| `VISIONOS` | `101` | `1.02` | `deviceMake=Apple`、`deviceModel=RealityDevice17,1`、`osName=visionOS`、`osVersion=26.5.23O471` |
| `ANDROID` | `3` | `21.26.364` | `osName=Android`、`osVersion=11`、`androidSdkVersion=30` |
| `WEB` | `1` | 首页读到的版本，缺失时用代码中的后备值 | 无设备字段 |

VISIONOS 使用代码中的 Safari 标识，ANDROID 使用 `com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip`，WEB 使用桌面 Chrome 标识。三个客户端都传 `hl=zh-CN`、`gl=US`，有访客数据时传 `visitorData`。正文还包括 `videoId`、`contentCheckOk=true`、`racyCheckOk=true`，读到正数 `STS` 时才加 `playbackContext`。

先将三个客户端匿名尝试一轮。只有最后一个结果没有地址、状态是 `LOGIN_REQUIRED` / `UNPLAYABLE` / `AGE_CHECK_REQUIRED`，且已保存 Cookie 时，`VideoAsync` 才带 Cookie 再尝试整轮。不是每次失败都会带登录重试；普通无地址、连接错误或签名错误不一定满足这一条件。

HTTP 非成功或正文不是 JSON 时记为「连不上 YouTube」。连接失败和接口超时可继续下一个客户端，用户主动取消则停止。只有 `playabilityStatus.status` 为空或 `OK` 时才解析 `streamingData`。最终无地址时用最后一个结果的原因报错，不会合并各客户端的错误。

标题和作者取 `videoDetails.title` / `author`；封面取 `thumbnail.thumbnails` 中最后一个非空地址，缺失时用 `https://i.ytimg.com/vi/{videoId}/hqdefault.jpg`。标题缺失时用视频编号。

### 格式选择、签名和 `n` 参数

桌面端源码。读取 `streamingData.adaptiveFormats` 和 `formats`，每条只提取 `mimeType`、数值型 `bitrate`、`url`、`signatureCipher`（为空时取 `cipher`）。排序优先级如下，同一档按码率降序：

1. `audio/mp4` 且类型描述包含 `mp4a`。
2. 其余 `audio/mp4`。
3. `video/mp4` 且类型描述包含 `mp4a`，作为最后的候选。

其它格式跳过，包括 WebM/Opus。当前没有按 `itag`、语言或 `audioQuality` 选轨，也没有读取 `averageBitrate`。第三档可能含视频；保存时不会提取音轨或转码，只是把下载内容命名为 `.m4a`，因此不能把所有下载结果都描述成纯音频。

地址处理分两类：

- 直接 `url`：读取查询参数 `n`，有对应处理结果时替换；没有结果、结果为空或与原值相同时保留原值。
- `signatureCipher` / `cipher`：按表单参数解码出 `url`、`s`、`sp`，用播放器脚本处理 `s`，将结果追加到 `sp` 指定的参数，`sp` 缺失时使用 `sig`；同时处理地址中的 `n`。缺少地址、`s` 或签名结果的候选无法使用。

`MaterializeAsync` 会收集所有候选的 `s` 和 `n` 并去重，一次交给 `SolveAsync`。没有这两类参数时直接使用地址，不需要 Node.js；需要处理时才下载首页引用的播放器脚本，脚本非成功或不足 100,000 字符时报「播放器脚本下不来」。

签名处理的执行方式：

1. 在 Windows 的 Program Files、Program Files (x86)、用户 LocalApplicationData 下的 `Programs`，以及 `PATH` 中查找 `nodejs/node.exe` 或 `node.exe`。找不到时报「解开 YouTube 签名需要本机安装 Node.js」。
2. 查找输出目录中的 `yt-solver.cjs` 或 `Crawl/yt-solver.cjs`；找不到时把内嵌资源提取到临时目录 `PlayerDemo/yt-solver.cjs`。
3. 通过标准输入发送 JSON：`player` 是播放器脚本正文，`requests` 是 `type=sig` / `type=n` 加 `challenges` 数组。脚本通过标准输出返回 `responses`，每项为 `type=result`、`data={原值:处理结果}`；C# 按请求顺序读取。
4. 子进程最长等待 30 秒；超时或主动取消会终止整个进程树。非零退出码、空输出、响应缺项或错误项都会记为签名处理失败。

可读源码 `yt-solver.mjs` 使用 `meriyah` 解析播放器脚本、用 `astring` 生成代码，按含 `alr` / `yes` 调用的结构寻找相关函数，补齐少量浏览器全局对象后执行。实际运行的 `.cjs` 已打包这两个依赖，不在运行时安装 npm 包。该方式依赖播放器脚本结构，结构变化可能导致失败；它处理的是 `s` 和 `n`，没有生成 PO Token。

所有候选共用一次签名处理，处理整体失败时会放弃本客户端；成功后按格式排序返回第一个可构造的地址，没有逐个下载探测。

### 搜索和播放列表分页

桌面端源码。两个接口共用 WEB 配置，`context.client` 含 `clientName=WEB`、首页版本、`hl=zh-CN`、`gl=US` 和非空时的 `visitorData`。请求头带 WEB 标识、客户端编号 `1`、版本、`Origin`、对应页面的 `Referer`，并在保存过 Cookie 时附加登录信息。

```http
POST https://www.youtube.com/youtubei/v1/search?prettyPrint=false
POST https://www.youtube.com/youtubei/v1/browse?prettyPrint=false
```

| 项目 | 搜索 | 播放列表 |
| --- | --- | --- |
| 首次正文 | `query` + `context` | `browseId=VL{playlistId}` + `context` |
| 后续正文 | `continuation` + `context` | `continuation` + `context` |
| 提取节点 | `videoRenderer` | `playlistVideoRenderer` |
| 上限 | 最多 3 页、40 条 | 最多 5 页、200 条 |
| `Referer` | `https://www.youtube.com/results?search_query={编码后的关键词}` | `https://www.youtube.com/playlist?list={playlistId}` |

`LL`、`WL` 分别构造 `browseId=VLLL`、`VLWL`，需要用户登录信息。它们走的仍是 `browse`，没有另一套专用收藏接口。

代码递归查找节点，按 11 位 `videoId` 去重；标题支持 `simpleText` 或拼接 `runs[].text`。作者依次取 `shortBylineText`、`longBylineText`、`ownerText`，并把 `lengthText` 拼在显示文字后，不转换成数值时长。候选封面固定用 `hqdefault.jpg`。没有解析搜索中的频道、播放列表卡片，也没有展开 Shorts 专用节点。

下一页取第一个 `continuationItemRenderer` 内递归找到的非空 `token`。本页没有新增视频、没有下一页标记或达到上限时停止，不保证取完整列表。播放列表在已读到部分结果后遇到异常，会返回已有结果（用户主动取消除外）；搜索后续页失败则直接报错，不返回部分结果。

### Cookie 与登录状态

桌面端源码。设置页接受浏览器里 `youtube.com` 的整段 Cookie，去掉换行和首尾分号后保存；这里只检查非空且包含 `=`，不是完整的有效性校验。先保存，再请求首页确认状态，确认失败也不会撤销保存。

`AccountStatusAsync` 带 Cookie 请求首页，出现 `"LOGGED_IN":true` 或 `loggedIn":true` 时显示「已登录」，明确为 false 时显示「保存了，但 YouTube 没有确认登录」；否则按 HTTP 状态显示「已保存」或「保存了，但连不上 YouTube」。这不是 Google OAuth 登录，也没有自动刷新 Cookie。

`StampCookie` 在 Cookie 非空时加 `Cookie` 和 `X-Goog-AuthUser: 0`。存在 `SAPISID`、否则存在 `__Secure-3PAPISID` 时，还生成 `Authorization: SAPISIDHASH {时间戳}_{摘要}`；摘要由当前秒级时间戳、Cookie 中该字段和 `https://www.youtube.com` 计算 SHA-1。代码没有使用 `__Secure-1PAPISID` 作为后备。

搜索、列表、带登录重试的播放接口，以及媒体下载都会调用 `StampCookie`；不能把当前行为概括为「Cookie 只用于喜欢和稍后观看」。媒体下载使用已保存的 Cookie，即使地址由匿名播放请求解析得到。

Cookie 通过 Windows 当前用户的数据保护功能加密，写入 `%APPDATA%/PlayerDemo/services.json` 和 `session.json`；读取时会用 session 文件覆盖对应登录字段。清除后保存空值。文档和日志不要记录真实 Cookie、访客数据、授权头或含临时凭证的完整媒体地址。

### 文件下载、取消与边界

桌面端源码。下载时，搜索或列表候选没有 `MediaUrl` 就重新调用 `VideoAsync`；单视频查找已经有地址时直接复用，没有判断其过期时间。

- 保存到桌面音频库 `YT{videoId}/` 下（库根目录为系统音乐目录下的 `PlayerDemo`），写入 `work.json` 的 `title` / `circle`，媒体文件名为清理非法字符、截到 80 字符的标题加 `.m4a`。
- 同名文件存在且长度大于 0 时直接跳过，不重新核验媒体内容。正常下载先写 `.part`，完成后不足 1024 字节报「音频下不来」，否则改为正式文件名。
- 媒体 GET 使用解析出地址的客户端标识、观看页 `Referer`、YouTube `Origin`、已保存 Cookie 和 `Range: bytes=0-`。这是从头下载，不是按已有 `.part` 大小断点续传；没有分片并发下载。
- 403 提示「这个音频地址被 YouTube 拒绝了。当前没有稳定的公开音频接口，校验没通过时下不了。」；其它非成功状态提示「音频下不来」。当前不会因媒体 403 自动刷新地址、换客户端或重新签名。
- 失败或取消删除音频 `.part`；已经完成的正式文件保留。封面另存为 `cover.jpg`，普通封面错误不影响已下载音频，主动取消则继续向外传递。
- 批量下载按所选视频逐个执行，单个失败后继续下一个；下载期间可中止。界面的搜索和列表读取传 `CancellationToken.None`，没有接上下载中止按钮。

当前 YouTube 抓取链路没有下载字幕、解析 DASH/HLS 清单、处理直播分片或转码。也没有 PO Token 支持。Android 的脚本执行和格式选择差异见前面的「Android 当前实现」，没有照搬 Windows 的 `node.exe` 查找与进程启动方式。应分别判断「搜索到了」「播放接口返回了地址」「媒体文件实际下载成功」，不能用前一步成功代替后一步。

## 2026-10-27 只读调查

以下保留当日请求记录。这里的「客户端」指当时对照的 yt-dlp 源码；「桌面端源码」专指上一节的本仓库实现。历史响应和版本号不代表当前 YouTube 的行为。

对照来源：

- 公开客户端 [yt-dlp](https://github.com/yt-dlp/yt-dlp) 当天 `master` 上的 `yt_dlp/extractor/youtube/_base.py`、`yt_dlp/extractor/youtube/_video.py`
- 2026-10-27 对公开视频 `jNQXAC9IVRw`（《Me at the zoo》）的只读请求
- 官方说明 [Videos: list](https://developers.google.com/youtube/v3/docs/videos/list)，页面写着更新于 2026-10-15

下面凡是这次请求里看见的，标「实测」。只在客户端源码里看见、这次没亲自打到返回正文的，标「客户端」。没见过的取值不编。

### 视频编号

实测，这条视频的编号是 `jNQXAC9IVRw`，11 个字符。

| 地址 | 实测 |
| --- | --- |
| `https://www.youtube.com/watch?v=jNQXAC9IVRw` | 200，观看页 |
| `https://youtu.be/jNQXAC9IVRw` | 303，跳到 `https://www.youtube.com/watch?v=jNQXAC9IVRw&feature=youtu.be` |
| `https://www.youtube.com/shorts/jNQXAC9IVRw` | 303，跳到 `https://www.youtube.com/watch?v=jNQXAC9IVRw` |
| `https://www.youtube.com/embed/jNQXAC9IVRw` | 200，没有跳转 |
| `https://music.youtube.com/watch?v=jNQXAC9IVRw` | 200，没有跳转 |

### 观看页

实测。桌面浏览器标识请求 `GET https://www.youtube.com/watch?v=jNQXAC9IVRw&hl=en`，HTTP 200。

页面脚本里有这些配置名：

| 名字 | 这次看见的 |
| --- | --- |
| `INNERTUBE_API_KEY` | 有，39 个字符，用在后面的 `/youtubei/v1/` |
| `INNERTUBE_CLIENT_VERSION` | `2.20261026.01.00` |
| `INNERTUBE_CLIENT_NAME` | `WEB` |
| `VISITOR_DATA` | 有。它跟这次访问绑在一起，不要写进日志或文档 |

同一页里的 `ytInitialPlayerResponse` 这次不是可播放的数据。`playabilityStatus.status` 为 `LOGIN_REQUIRED`，`reason` 为 `Sign in to confirm you’re not a bot`。没有 `videoDetails`，也没有 `streamingData`。`<title>` 只有 ` - YouTube`，没有 `og:title`。

客户端把同时存在 `LOGIN_INFO` 和 `SAPISID`（或 `__Secure-1PAPISID`、`__Secure-3PAPISID`）当成已登录。这次没带这些 cookie。

`INNERTUBE_API_KEY` 和 `INNERTUBE_CLIENT_VERSION` 写在观看页里，会变。调用时从当次页面读，不要把某一次的值写死。yt-dlp 源码里的网页客户端版本还是 `2.20260708.00.00`，比这次页面上的旧。

### oEmbed

实测，不需要密钥，HTTP 200，正文是 JSON。

```http
GET https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=jNQXAC9IVRw&format=json
```

这次有的字段：`title`、`author_name`、`author_url`、`type`、`provider_name`、`provider_url`、`thumbnail_url`、`thumbnail_width`、`thumbnail_height`、`width`、`height`、`version`、`html`。

`title` 是 `Me at the zoo`，`author_name` 是 `jawed`，`type` 是 `video`。`thumbnail_url` 指向 `i.ytimg.com`。`html` 是一段嵌入用的 iframe，不是音轨地址。

### 播放

实测。各客户端都是：

```http
POST https://www.youtube.com/youtubei/v1/player?prettyPrint=false&key={INNERTUBE_API_KEY}
Content-Type: application/json
Origin: https://www.youtube.com
```

正文至少包括：

```json
{
  "videoId": "jNQXAC9IVRw",
  "context": {
    "client": {
      "clientName": "WEB",
      "clientVersion": "2.20261026.01.00",
      "hl": "en",
      "gl": "US"
    }
  },
  "contentCheckOk": true,
  "racyCheckOk": true
}
```

`WEB` 那次还带了观看页里的 `visitorData`。`contentCheckOk` 和 `racyCheckOk` 是客户端在请求播放时带上的两个布尔值。全部 HTTP 200，但都没有 `streamingData`。

| `clientName` | `clientVersion` 来源 | `playabilityStatus.status` | `reason` | 有没有 `videoDetails` |
| --- | --- | --- | --- | --- |
| `WEB` | 当天观看页 | `LOGIN_REQUIRED` | Sign in to confirm you’re not a bot | 无 |
| `MWEB` | 当天观看页 | `LOGIN_REQUIRED` | 同上 | 无 |
| `WEB_EMBEDDED_PLAYER` | 当天观看页 | `ERROR` | This video is unavailable | 无 |
| `TVHTML5` | yt-dlp 源码里的 `7.20260707.07.00` | `LOGIN_REQUIRED` | Sign in to confirm you’re not a bot | 无 |
| `TVHTML5_SIMPLY` | yt-dlp 源码里的 `1.0` | `UNPLAYABLE` | Sign in to confirm you’re not a bot | 有 |
| `ANDROID` | yt-dlp 源码里的 `21.26.364` | `LOGIN_REQUIRED` | Sign in to confirm you’re not a bot | 无 |
| `ANDROID_VR` | yt-dlp 源码里的 `1.65.10` | `LOGIN_REQUIRED` | Sign in to confirm you’re not a bot | 无 |
| `IOS` | yt-dlp 源码里的 `21.26.4` | `LOGIN_REQUIRED` | Sign in to confirm you’re not a bot | 无 |

`WEB` 被拒绝时，顶层有 `playabilityStatus`、`responseContext`、`trackingParams`、`adBreakHeartbeatParams`、`frameworkUpdates`。`playabilityStatus` 里有 `status`、`reason`、`contextParams`、`errorScreen`、`skip`。`errorScreen` 只有 `playerErrorMessageRenderer`，里面有 `reason`、`subreason`、`thumbnail`、`icon`、`proceedButton`。

`TVHTML5_SIMPLY` 虽然不能播，但 `videoDetails` 这次有这些字段：`videoId`、`title`、`author`、`channelId`、`lengthSeconds`、`viewCount`、`shortDescription`、`thumbnail`、`keywords`、`allowRatings`、`isLiveContent`、`isPrivate`、`isOwnerViewing`、`isCrawlable`、`isTvfilmVideo`、`isUnpluggedCorpus`。

这次的值：`title` 为 `Me at the zoo`，`author` 为 `jawed`，`channelId` 为 `UC4QobU6STFB0P71PMvOGN5A`，`lengthSeconds` 为 `19`，`viewCount` 为 `446749333`，`isLiveContent` 为 false，`isPrivate` 为 false。`thumbnail.thumbnails` 是一组图片，主机名都是 `i.ytimg.com`，看见的尺寸有 120×90、320×180、444×250、480×360。

#### 音轨字段

客户端在拿到 `streamingData` 时，从 `formats` 和 `adaptiveFormats` 里读每一条。它使用的字段名有 `itag`、`mimeType`、`bitrate`、`averageBitrate`、`audioQuality`、`audioSampleRate`、`url`、`signatureCipher`、`contentLength`、`initRange`、`indexRange`。音频是 `mimeType` 以 `audio/` 开头的那些条。`signatureCipher` 出现时，地址不在 `url` 里，还要观看页引用的播放器脚本才能还原。

这次所有播放响应都没有 `streamingData`，所以上面这些字段没有核对到实例。yt-dlp 还写明，不少客户端即使返回了 `url`，取文件时仍可能要求那个播放器令牌；没有它，文件请求会 403。本文不写这个令牌怎么生成。

### 搜索

实测，HTTP 200。

```http
POST https://www.youtube.com/youtubei/v1/search?prettyPrint=false&key={INNERTUBE_API_KEY}
Content-Type: application/json
```

```json
{
  "context": {
    "client": {
      "clientName": "WEB",
      "clientVersion": "2.20261026.01.00",
      "hl": "en",
      "gl": "US"
    }
  },
  "query": "me at the zoo"
}
```

顶层有 `responseContext`、`estimatedResults`、`contents`、`trackingParams`、`topbar`、`targetId`。关键词 `me at the zoo` 的结果里有 8 处 `videoRenderer`，没有 `playlistRenderer`，也没有 `channelRenderer`。

第一条 `videoRenderer` 的 `videoId` 是 `jNQXAC9IVRw`。这条上能读到文字的字段：

| 字段 | 这次的文字 |
| --- | --- |
| `title` | Me at the zoo |
| `ownerText` | jawed |
| `lengthText` | 0:19 |
| `viewCountText` | 446,749,333 views |
| `publishedTimeText` | 21 years ago |

文字在 `simpleText`，或者在 `runs` 数组里逐段的 `text`。同一条还有 `longBylineText`、`shortBylineText`、`shortViewCountText`、`thumbnail`、`navigationEndpoint`、`publishedTimeText`。`lengthText` 是给人看的时长，不是秒数。

### 视频页补充数据

实测。`POST https://www.youtube.com/youtubei/v1/next`，正文是 `context` 加 `videoId`。HTTP 200。

顶层有 `contents`、`currentVideoEndpoint`、`engagementPanels`、`frameworkUpdates`、`microformat`、`onResponseReceivedEndpoints`、`pageVisualEffects`、`playerOverlays`、`responseContext`、`topbar`、`trackingParams`。正文里能找到 `videoPrimaryInfoRenderer`、`videoSecondaryInfoRenderer`，以及标题 `Me at the zoo`。这次没有继续拆这些渲染块里的字段。

### 缩略图

实测，不需要密钥。

```http
GET https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg
```

HTTP 200，`Content-Type` 为 `image/jpeg`。oEmbed 的 `thumbnail_url` 和 `videoDetails.thumbnail` 也在 `i.ytimg.com`。

### 字幕

实测，下面两条都是 HTTP 200，`Content-Type` 为 `text/html`，正文长度为 0，没有字幕列表，也没有一句台词：

```http
GET https://www.youtube.com/api/timedtext?type=list&v=jNQXAC9IVRw
GET https://www.youtube.com/api/timedtext?v=jNQXAC9IVRw&lang=en&fmt=json3
```

客户端在播放响应里读取 `captions.playerCaptionsTracklistRenderer.captionTracks`。每条有 `baseUrl`、语言代码，自动生成的字幕带 `kind`。它还从 `translationLanguages` 读可翻译的语言。这次的播放响应里没有 `captions`，所以这些字段没有核对到实例。

### 官方 Data API

这是另一套接口，主机是 `www.googleapis.com`，不使用上面的 `INNERTUBE_API_KEY`。

实测，不带任何密钥：

```http
GET https://www.googleapis.com/youtube/v3/videos?part=snippet&id=jNQXAC9IVRw
```

HTTP 403，正文是 JSON。`error.code` 为 403，`error.message` 为 `Method doesn't allow unregistered callers (callers without established identity). Please use API Key or other form of API consumer identity to call this API.`

官方页面写的成功调用是：

```http
GET https://www.googleapis.com/youtube/v3/videos?part={part}&id={视频编号}&key={自己的密钥}
```

`part` 可以包含 `snippet`、`contentDetails`、`statistics`、`status`、`player` 等。`player` 对应的是嵌入播放器，参数说明里把它放在 `player.embedHtml`。这次没有密钥，没有看到成功正文，也就没有看见文件地址。官方页面列出的返回结构是 `youtube#videoListResponse`，里面是 `items` 数组，没有音轨文件字段。
