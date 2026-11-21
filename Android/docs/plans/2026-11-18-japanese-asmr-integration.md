# Japanese ASMR 接入 Android 实施计划

## 目标与交付阶段

在现有「作品」搜索中合并 Japanese ASMR 结果。同一 RJ 号显示一张作品卡片，在线详情和库内作品页按该作品可用的资源提供「DLsite Play」「asmr.one」「Japanese ASMR」来源切换。

- **第一阶段**完成搜索、分页、详情、链接导入、在线播放、章节跳转和入库。直接音轨接入现有下载管理和生成字幕。下载完成并关联到曲目后，听写直接读取本地文件。用户也可在发起听写时确认前置下载。
- **第二阶段**完成 HLS 整段下载。将支持的分段流保存为单个本地音频，接入相同的下载管理、离线播放和生成字幕流程。

站点的作品数据接口拒绝匿名访问。Japanese ASMR 客户端读取公开搜索和详情网页，从主播放器提取媒体候选和章节。依据见 [Japanese ASMR 接口调查](../japaneseasmr-api.md)。实施时不重新访问站点验证接口；解析以调查文档记录的页面结构为准，真机可用性在发布前单独确认。

## 作品全集与资源来源

- 在本次接入的 RJ 作品范围内，DLsite 商品目录是全集，asmr.one 和 Japanese ASMR 分别是它的子集。后两个站点的作品都有对应的 DLsite RJ 号。两站独立收录，每部作品可能两站都收录、只有一站收录，或两站都未收录。
- RJ 号标识作品。搜索、导航和音频库按同一 RJ 合并；实际请求、显示和保存保留作品号字符串及前导零。Japanese ASMR 的 `postId` 标识同一作品在该站的文章，`postId + mediaKey` 标识一条资源。同 RJ 的多篇文章归入同一作品，保留各自音轨。
- DLsite 商品目录用于确认作品和查找商品资料。该用户已经购买作品时，才能从 DLsite Play 加载在线文件。另两站各自是否收录独立判定，与用户是否购买分开。作品可以只有 DLsite 商品资料而没有可用音轨，此时仍显示作品详情。
- 页面读不到可确认的 RJ 时，记录为该条目身份无法确认。搜索时跳过该条目，打开明确文章链接时报告原因。文章编号只用于查文章及其资源。现有其他编号的兼容路径和库内历史数据按原规则保留。

## 已确认的产品决策

1. **音频库筛选**：根目录「asmr.one」筛选改名为「在线作品」，覆盖以现有作品根标识保存的文件夹，包括仅从 Japanese ASMR 入库的 RJ 作品。沿用同一个作品筛选入口。
2. **多篇文章目录**：同一作品只入库一篇 JA 文章时曲目平铺在来源根下；入库第二篇时按文章建子文件夹（文章标题命名），并把第一篇已有曲目移入其子文件夹。移动只改 `folderId`，曲目编号、本地文件、下载与字幕关联不变。
3. **请求节流**：列表/目录类页面请求（搜索分页、首页）串行且间隔 ≥10 秒；用户主动打开详情、播放前解析地址即时发出，但仍经同一串行闸门，不并发抓取页面。媒体主机（音频文件、HLS 分片、图片）不受 10 秒限制。
4. **章节 UI**：播放页功能区显示「章节：当前章名」入口（类似 B 站合集入口），点击打开底部弹层列出全部章节，点击跳转到章节起点并保留弹层，交互与字幕全文列表一致。
5. **mediaKey 对账**：刷新后键不一致时接受「旧曲目保留 + 新曲目插入」，UI 提示新增/保留计数；不做模糊匹配，不删除旧曲目及其下载、字幕关联。
6. **JA-only 保存**：仅 JA 收录且用户未打开过文章时，保存按页面顺序解析候选文章，首篇确认有可播放媒体即填充该篇并建根；全部无媒体则拒绝建根，提示「没有有效来源」。
7. **章节归属**：页面恰好一个媒体元素时才挂接章节表；多曲目文章不显示章节入口，避免页面级章节表错挂到各曲目。发布前真机复查多曲目章节结构后可再放宽。
8. **下载行地址**：JA 下载行 `remoteUrl` 留 NULL（与 ASMR/DLSITE 一致），文章稳定地址只存 `japaneseasmr_posts.url`；「打开文章」读 posts 表。队列行按两类写入：已入库行仅 `trackId`（身份经关联表回查），未入库行才保留文章地址与音轨键；听写/字幕身份用 `japaneseasmr://audio?post={postId}&media={mediaKey}`。

以下为计划补充的默认决策，实施前可再调整：

- 「加入音频库」是作品级操作。程序复用详情已有的来源结果，核对三个来源中仍未知的项，确认至少一个来源有可用资源后保存作品。JA 的收录和文章候选按 RJ 一并确认。未选择的文章可在切换来源或下载时自动补齐，用户无须逐条入库。
- 剪贴板 RJ 候选的预览查询沿用「库 → asmr.one → DLsite」。明确 JA 链接在预览中定位对应篇目。确认入库时，两个入口共用作品级来源核对和保存流程。
- 默认热门页（无关键词）不查询 JA；JA 仅在纯关键词/RJ 搜索时参与（见搜索节）。
- 仅 JA 来源的搜索结果卡片带「Japanese ASMR」标签；合并卡片维持现有外观，不另标来源。
- JA 来源不参与「关联为作品 / 关联本地文件夹」的本地目录匹配（该功能仍只对照 DLsite 与 asmr.one）。
- JA 文件区第一阶段展示文章信息和音轨。统一作品页的图库与试听继续由现有 DLsite 商品资料提供。
- JA-only 搜索卡片保留 markListed 打上的「asmr.one 未收录」徽章，与「Japanese ASMR」标签并列（现有 DLsite-only 卡片同款行为）。
- 候选探测（Range 0-1023）使用媒体客户端派生的短超时客户端（如 10s 读取），避免死候选卡满 60s 才换下一个。
- 播放前解析等待串行锁支持取消，等待超过 30 秒按连接类错误提示重试，不无限阻塞播放加载线程。

## 音频来源与下载产物

- **直接音轨**指站点为曲目提供一个可直接获取完整音频文件的地址，例如 MP3 或 M4A。App 选用一个可用的格式候选，将该文件原样保存。调查中的 `/58186/` 页面给同一曲目提供两个格式候选，其中 MP3 文件头已验证可读取。
- **HLS 整段下载**指 App 从选定的 HLS 播放清单（`.m3u8`）找到并下载整条流的所有音频分片，再按原时间轴封装成一个 M4A 文件。范围以所选的一条流为准。调查中的 `/135575/` 页面有一条流、391 个传输分片和 5 个章节标记。按此方案下载后会得到一份包含全部五章的音频，章节保留为跳转点。

两类下载完成后都向现有播放和听写流程提供一个关联到曲目的本地音频文件。M4A 可以是站点直接提供的文件，也可以是 App 封装 HLS 分片后的产物。实现时按站点提供媒体的方式区分两种下载（`japaneseasmr_media.kind = DIRECT / HLS`）。

音频类型以当篇播放器实际提供的资源为准。相同 RJ 的 `/123908/` 与 `/135575/` 分别展示了直接音轨与 HLS，两篇的发布日期不参与判断。章节取自页面的章节表，直接音轨和 HLS 都按实际解析到的章节展示。本计划覆盖播放器中的音频。页面另外提供的 `/dlc.php` 下载入口尚未验证，留待单独评估。

## 站点客户端与解析

新增包 `japaneseasmr/`（与 `asmr/`、`dlsite/` 并列）：

- `JapaneseAsmrClient.kt`：页面与媒体两个独立 OkHttpClient（20s 连接 / 60s 读取、HTTP_1_1、跟随重定向、**无 CookieJar、不携带任何来源凭证**）。页面请求头 `User-Agent: Mozilla/5.0`、`Accept: */*`；媒体请求头 `User-Agent: Mozilla/5.0`、`Referer: https://japaneseasmr.com/`，读文件头时加 `Accept-Encoding: identity`。常量 `BASE`、`USER_AGENT`、`REFERER` 集中定义。下载传输客户端由 `mediaClient.newBuilder()` 派生并挂 `ProgressNetworkEvents`（参照 `DownloadCenter.transferHttp` 先例）。
- **节流闸门**：客户端内 `Mutex` + `lastListRequestAt`。列表类请求（关键词、RJ 收录查询、分页、首页）距上次列表请求 ≥10 秒，锁外等待，取得锁后复核间隔。详情和播放前地址解析共用串行锁，等待正在执行的页面请求结束即可，无须等待列表冷却。媒体文件与分片请求不经此闸门。
- **解析缓存**：文章解析结果按 `postId` 内存缓存（格式候选、清单地址、章节、时长），TTL 约 5 分钟，失效或 403/404 时重解析。**不写库、不记录完整临时媒体地址**。`DataSource.open` 每次 seek 都会调用，必须命中缓存，不得每次抓取页面。
- `JapaneseAsmrParse.kt`：HTML 解析使用 **jsoup**（新增依赖 `org.jsoup:jsoup:1.18.1`，纯 JVM，单测可用；现有来源的正则解析不沿用，本站结构复杂）。选择器按调查文档：
  - 搜索/列表：`/search/{关键词}/`（关键词作为单个路径段 URL 编码，不拼接用户输入中的 `/`、`?`、`#`）。条目限定 `.entry-preview`；标题与链接取 `.entry-title a`；封面优先 `img[data-src]`（`src` 是占位）；日期取 `time[datetime]`，**是文章发布日期，不是发售日**；RJ 从卡片文本提取，卡片内恰好一个不同 RJ 才视为可确认（多个不同 RJ 的合集类卡片跳过），只保留可确认 RJ 的条目。下一页读 `nav.navigation.pagination a.next.page-numbers` 的实际 `href`，无链接即结束；不假设每页条数，不推算总数。
  - 详情：`/{postId}/`。标题 `h1.page-title`；声优 `#voice_actors`；RJ 优先从 `.entry-content` 中以 `RJ Code:` 开头的段落提取，缺少该段落时可使用正文主标题中的唯一 RJ。封面按页面角色区分 `.entry-content img[data-src]`。RJ 缺失、存在冲突或与当前作品不匹配时报告「无法确认文章对应的 RJ 作品号」，该篇文章不参与播放或入库。
  - 媒体：只读 `.cleanPlayer` 内每个 `video`/`audio` 元素；同一元素下多个 `<source>` 是**同一曲目的格式候选**，不拆成多首；`.audio_main` 的重复地址不再计入。`title`/`descr` 属性参与媒体键。旧式 `#basic-playlist li a[data-url]` 不在第一阶段范围，遇到仅该结构的文章报「页面结构暂不支持」。
  - 章节：只取 `#plyr-chapter-playlist`（忽略 `#basic-chapter-playlist` 重复表）；行内 `td.chapter_list.start_time` / `td.chapter_list.chapter_title`，跳转秒数取链接 `data-value`；合并同一行重复链接、跳过表头、按起点排序去重。章节是跳转点，不是独立文件，也不是字幕。页面含多个媒体元素时不挂接章节（已确认决策 7），章节仅归属单媒体页面。
  - 地址原样解析并处理相对路径，不按 RJ 猜文件名、不手工替换域名（注意 `weeab0o` 与 `weeabo0` 的 0/o 差异）。
- **媒体键 `mediaKey`**：优先 `t:` + SHA-256 前 8 字节 hex（归一化标题与描述用空字符拼接；归一化 = trim + 连续空白折叠，保留大小写与日文原文），要求标题、描述均非空且组合在同篇内唯一；重复或缺失时回退 `p:` + SHA-256 前 8 字节 hex（媒体路径，**去掉查询参数与 fragment**）。键一旦入库不随地址刷新重算；刷新后按原键对账（见入库节）。
- **时长**：HTML 不提供时长。HLS 媒体清单按 `EXTINF` 求和得到整段时长，解析时写入；直接音轨入库时 `durationMs = 0`，下载完成后用 `MediaMetadataRetriever` 回写，在线播放取得真实时长后由播放服务回写（仅原值为 0 时）。主清单（多变体）时长未知时同样保持 0 待回写。
- **错误分类**（客户端异常携带类型，UI 与下载按类型映射文案）：
  - HTTP 403 且响应为 Cloudflare 验证页（`cf-mitigated: challenge` 或验证页特征）→「站点验证，暂时无法读取」。**不得解析成空结果，不得循环重试**。
  - 网络 IOException / 超时 →「连接失败，请检查网络」。
  - HTTP 404 → 文章不存在或已删除（详情）/ 候选不可用（媒体，尝试同曲目其他候选）。
  - HTTP 401（wp-json）→ 不在链路中使用该接口。
  - HTTP 200 但详情页找不到 `.cleanPlayer` 媒体 →「页面结构或内容不可用」，不等同作品不存在。
  - HTTP 5xx →「站点服务错误」。
  - 错误文案沿用现有脱敏规则（过滤含 `://` 的消息），不泄露完整临时媒体地址。用户可见错误的出口统一过滤整条 cause 链：`PlaybackService.playbackErrorMessage` 逐层取消息时同时排除含 `://` 的条目（与 DownloadCenter/TranslateCenter 的 `takeUnless { "://" in it }` 一致，全部被过滤时回退通用文案），避免底层异常把临时媒体地址带到界面；JA 抛出点仍使用脱敏文案作为双保险。
- **图片加载**：`AppGraph` 的 Coil 拦截器按 host 路由请求头，新增 JA 分支：`japaneseasmr.com` 及页面解析出的图片主机（`pic.weeabo0.xyz`、`img.weeabo0.xyz` 为已知种子，解析到的新主机动态登记）使用 JA UA + Referer。未登记主机维持现有兜底（asmr.one UA/Referer），不向其他来源请求附加 JA 头。动态登记仅驻内存，进程重启即丢失；启动时从 `japaneseasmr_posts.coverUri` 及 JA 曲目/作品根封面扫描登记主机，避免已保存封面在重启后带兜底头请求 403。

## 数据模型与迁移

数据库升级至 **21**（当前 20），提供显式 `MIGRATION_20_21`，新装按实体建表；不支持降级。

### 新增来源与列

- `TrackSource` 增加 `JAPANESE_ASMR`。所有按 source 穷举的 `when`（`MediaItemFactory.toPlayableItem`、`SubtitleSource.cuesFor` 等）随之补齐；编译错误即检查清单。
- `queue_items` 增加可空 `mediaKind TEXT`，实体与 `QueuedTrack`、`QueueItemEntity` 同步；`RemotePlayable` 增加可空 `mediaKind`。用于未入库 JA 队列项在重启恢复时确定 HLS（`MimeTypes.APPLICATION_M3U8`）与渐进式媒体源。`QueueDao.snapshot()` 与 `observe()` 是两份相同投影，须同时补列。入库 JA 队列行沿用 Saved 模式（仅 trackId，remoteUrl 为 NULL），否则 `QueuedTrack.toMediaItem` 的 `trackId != null && remoteUrl.isNullOrBlank()` 路由会把它当远程项；文章地址只写入未入库的 Remote 行。
- 媒体类型常量：`DIRECT` / `HLS`，只写入 `japaneseasmr_media.kind` 与 `queue_items.mediaKind`。

### 新表（风格沿用 `dlsite_original_*`：不设外键，移除作品后仍可管理下载与文章身份）

```sql
ALTER TABLE queue_items ADD COLUMN mediaKind TEXT;
CREATE TABLE japaneseasmr_posts (postId TEXT NOT NULL PRIMARY KEY, rootFolderId INTEGER, articleFolderId INTEGER, rj TEXT NOT NULL, title TEXT NOT NULL, coverUri TEXT, publishedAt TEXT, url TEXT NOT NULL, refreshedAt INTEGER);
CREATE INDEX index_japaneseasmr_posts_rootFolderId ON japaneseasmr_posts(rootFolderId);
CREATE TABLE japaneseasmr_media (postId TEXT NOT NULL, mediaKey TEXT NOT NULL, trackId INTEGER, kind TEXT NOT NULL, title TEXT NOT NULL, durationMs INTEGER NOT NULL, chapters TEXT, PRIMARY KEY(postId, mediaKey));
CREATE INDEX index_japaneseasmr_media_trackId ON japaneseasmr_media(trackId);
```

- `japaneseasmr_posts`：`postId` 为站内文章编号（TEXT，保留原字符串）；`rj` 必填，保存确认后的页面编号及前导零；`publishedAt` 为文章发布日期原字符串（**不是发售日**）；`url` 保存稳定详情地址；`refreshedAt` 记录上次对账时间；`rootFolderId` / `articleFolderId` 可悬空（无外键）。文章候选可以先关联作品根。已有有效曲目关联时，该篇才计为已填充，参与多篇文章的库内目录布局。
- `japaneseasmr_media`：主键 `(postId, mediaKey)`；`trackId` 可空、可悬空；`chapters` 为 kotlinx.serialization JSON 数组 `[{"startMs":0,"title":"…"}]`（已有依赖）；`durationMs` 写入时必填（未知为 0）。
- 新增 `JapaneseAsmrDao`（并入 `Daos.kt` 或独立文件）：`post(postId)`、`postsForRoot(rootFolderId)`、`media(postId, mediaKey)`、`mediaForPost(postId)`、`mediaForTrack(trackId)`、`mediaForTracks`、`upsertPost`、`upsertMedia`、`setMediaTrack`、`clearRootLinks(folderId)` 等。

### 身份与生命周期规则

- **曲目身份** = `(postId, mediaKey)`；`tracks` 不新增身份列，JA 曲目 `asmrWorkId`、`fileHash` 保持 null，关联只经 `japaneseasmr_media`。
- **作品根判定**：按 `library.folderBySource(rj)` 在锁内匹配。现有 `canonicalWorkno` 仅用于判断同一作品。首次入库时以原始 RJ 设置 `asmrSourceId`，使用已有商品资料填充 `workMeta`，资料缺失时用文章标题和声优填充可确认字段。`asmrWorkId` 复用现有共享作品根的内部辅助编号规则。三个来源追加时复用此根，并分别判断该来源的文件是否已入库；JA 文章通过 `japaneseasmr_posts.rootFolderId` 关联。
- **多篇文章布局**：文章行 `articleFolderId` 指向该篇子文件夹；仅一篇时为空、曲目直接挂在根。入库第二篇时同一事务内为两篇各建子文件夹（`contentSource = JAPANESE_ASMR`，必要时继承 `asmrWorkId`），把第一篇曲目的 `folderId` 迁入其子文件夹；曲目 id、`localUri`、下载与字幕关联不变。删除某篇文章不在第一阶段范围。
- **移除与重新导入**：移除作品时现有 `deleteItems` 级联删除曲目并中止受影响下载；`japaneseasmr_posts` / `japaneseasmr_media` 行保留（与原始清单一致），`rootFolderId` / `trackId` 变为悬空。重新导入同篇时重建目录、回链 `trackId`，按 `(postId, mediaKey)` 把 `downloads` 中 `source = JAPANESE_ASMR`、`workKey = postId`、`fileKey = mediaKey` 的已完成记录重新挂到新曲目的 `localUri`；生成字幕按内容指纹自动复用，无需重新生成。
- **入库事务**：`LibraryRepository.importJapaneseAsmrPost(article, parentId = null)`：`workImportLock` → 锁后重新判定同篇是否已入库（已入库则走对账/复用，不报错）→ 单个 `db.withTransaction`：确定根 → upsert 文章行 → 建/复用文章子文件夹 → upsert 曲目（同键复用 id 并更新标题、时长、章节；新键插入；页面未出现的旧键保留并计数）→ 回链已完成下载 → 提交。撞唯一索引按现有 `hasConstraint()` 规则转成「这部作品已经在音频库」。
- **对账/刷新**：`refreshJapaneseAsmrPost(postId)` 重新解析文章页，同键更新、新键新增、缺失键保留，返回新增/保留计数供 UI 提示「页面资源已变化」。
- **既有查询扩展**：`TrackDao.onlineWithLocal()` 的 `source IN ('ASMR','DLSITE')` 加入 `'JAPANESE_ASMR'`；`StoredCatalog` 扫描根加入 `filesDir/japaneseasmr`，`inside()`/`downloadWorkDirectory()` 目录白名单同步加入；下载管理与下载任务页的播放项保留下载记录中的文章编号和媒体键：`DownloadScreen.play`（任务页）与 `StoredFilesScreen.play`（下载管理页）都加 JA 分支。后者现对非 B 站/YouTube 成品直接按 LOCAL 播放（StoredFilesScreen.kt:99-119），作品移除后会丢身份；`StoredItem`/`StoredCatalog` 须为 JA 下载行带出 source=JAPANESE_ASMR、workKey=postId、fileKey=mediaKey，播放、生成字幕计数与删除均按这组身份匹配。下载、听写和字幕统一按「听写与字幕」节的曲目身份匹配。
- **UI 刷新与检查清单**：`observeWorkTreeChanges` 的 invalidation 表清单加入 `japaneseasmr_posts`/`japaneseasmr_media`，否则入库/对账后库内 UI 不刷新。新增枚举的编译错误只覆盖穷举 when；非穷举的来源条件须另行 grep 审计：`trackSupporting` 的 downloaded 布尔式（OnlineScreens.kt）、`refreshDownloads`、`canSave`、`cacheWork` 默认来源、`EntryNames.libraryFileSource`（else→ASMR，JA fileHash 恒 null 不受影响）。
- **AppGraph**：`japaneseAsmr` 客户端在 `library` 之前构造，注入 library、downloads 与 Coil 拦截器。播放数据源 Factory 实际在 `PlaybackService.onCreate` 组装（传入 graph.japaneseAsmr）；`StoredCatalog` 在 `StoredFilesScreen` 的 ViewModel 构造，改其扫描逻辑即可，无需 AppGraph 注入。

## 搜索、详情与入库

### 搜索结果合并

- `OnlineHit` 增加 `jaPosts: List<JaPostRef>`（`postId`、`rj`、`title`、`publishedAt`、`url`）与 `jaOnly: Boolean`。所有 JA 命中以原始 RJ 为 `sourceId`，沿用 `dedupeKey` 的现有比较规则。`jaOnly` 只表示本次搜索的来源命中情况，用于卡片标签和初始查看来源。卡片仍进入对应 RJ 的作品详情。
- `OnlineSearchViewModel.load()` 增加第三个查询任务（JA）与独立 `SourcePaging`。复用 revision 和取消机制，成功的来源独立提交，避免 JA 的限速等待影响其他来源出结果。保持 asmr.one 和 DLsite 的现有合并顺序，只有 JA 命中的结果按站点顺序追加。**合并规则耦合**：现有规则 `existing == null || !existing.fromAsmr` 会让 JA（fromAsmr=false）覆盖 DLsite 命中——JA 循环必须改为「已有卡片只合并 jaPosts，绝不替换，`jaOnly` 保持原值」（已有卡片本身是 JA-only 时，同 RJ 第二篇文章的合并仍只有 JA 命中）；仅在 asmr.one 或 DLsite 的搜索结果实际命中并合入卡片时把 `jaOnly` 置 false；markListed 的资料替换保留 `jaPosts` 与搜索命中标记（`jaOnly` 不变）；末尾 dedupeKey 去重保留首条。reset 块、各来源 `!paging.hasMore && !reset` 跳过条件、错误拼接 listOfNotNull 同步加第三来源。同一 RJ 的 `jaPosts` 按 `postId` 去重，跨页累积。后续页补齐其他来源时复用原卡片与文章列表，更新命中标签。
- **参与条件**：仅当 `!usePopular()` 且关键词非空（含 RJ）、无标签/声优/社团结构片段、未开「有字幕」、未开「全年龄」时查询 JA。被跳过时在结果区显示一行「Japanese ASMR 仅支持关键词搜索」；参与但存在排序/筛选时显示「Japanese ASMR 按站点顺序追加，不参与排序与筛选」。默认热门页不请求 JA。
- `markListed(hits)` 按 RJ 应用现有 asmr.one 收录检查，更新资料时保留 `jaPosts` 和搜索命中标记。未在当前搜索命中某来源时保留未确认状态。播放来源以详情中各自检查的结果为准。卡片中的发售日来自已有作品资料，JA 文章发布日期只在文章条目上展示。
- 失败处理：`catchPage` 包装为独立 `Result`，错误消息前缀「Japanese ASMR：」并入现有换行拼接；验证页 →「站点验证，暂时无法读取」；网络 →「连接失败，请检查网络」；200 空列表 → 正常空结果。`loadMore()` 在两来源任一 hasMore 或 JA 仍有下一页时允许；JA 下一页请求在客户端闸门内等待 ≥10 秒，不阻塞其他来源。`pageHasMore` 不适用 JA（条数未知），`SourcePaging.succeed(page, hasNextLink)`。
- 卡片：`BadgeLine` 追加「Japanese ASMR」标签（`jaOnly` 卡片）；带此标签的卡片使用普通 `Row`/`Column`，不放入 `Material3 ListItem`。

### 路由与在线详情

- 三个来源的搜索卡片统一使用 `Routes.OnlineWork`，必填 RJ，可选 `ja`（逗号分隔的候选 `postId`）与 `jaPost`（从明确文章入口打开时预选的一篇），并可传入初始来源。明确 JA 文章链接先解析 RJ，再进入同一路由并定位该篇。
- `OnlineWorkViewModel` 增加 `WorkFileSource.JAPANESE_ASMR`，分别维护 DLsite 已购状态、asmr.one 收录状态和 JA 收录状态。JA 合并导航候选与库内 `postsForRoot(folderId)`，按文章编号去重；无已知候选时，在打开此作品后后台用原始 RJ 搜索。收录查询只接受作品号精确匹配的结果。找到后即可展示 JA 来源；无匹配且已读完分页时才确认未收录。网络失败或验证页保持待确认并支持重试。任一来源加载失败或仍在查询时，照常展示已有资料及可用文件。
- 用户打开 JA 来源时按需加载文章详情，再验证页内 RJ 与当前作品一致。用 `JaEntry` 树（`title`、`children`、`media: JaMediaRef?`）展示资料，单篇曲目平铺，多篇按文章分组，显示标题与文章发布日期。复用 `WorkFileBrowser`、目录栈、滚动恢复和 `sourceRevision` 隔离；迟到结果可以补充来源，但不替用户切换已选来源。
- 未指定初始来源时，沿用已有 Play/原始包 → asmr.one → JA 的顺序。仅有一项可用时直接展示该来源。两项或以上可用时显示切换。所有在线文件来源均不可用时显示 DLsite 商品资料及现有试听入口。
- **canSave 门槛与详情回退**：现有 `canSave = facts != null && !asmrMissing || purchased`（OnlineScreens.kt:1453）会把 JA-only 未购作品的保存按钮禁用，详情页还会显示「asmr.one 未收录」EmptyHint。canSave 与 EmptyHint 加入「JA 收录已确认且存在文章候选」分支——**以可信候选为准，不以已确认媒体为准**；媒体核验放在点击保存之后（已确认决策 6），确认有资源才建根，避免入口与核验互相等待。另：现有 load 在 asmr.one 与 DLsite 都拿不到资料时 `nextFacts == null` 直接以「没有找到这部作品」退出（OnlineScreens.kt:677-684）。facts 回退链须加入 JA 候选文章的标题、封面与 RJ（`loaded?.facts() ?: pageFacts ?: localFacts ?: jaFacts`），四者皆无时才报错退出；JA-only 作品据此展示详情与来源切换。
- `save()` 沿用整作品入库。程序按 RJ 确认 DLsite Play 已购资源、asmr.one 与 JA 收录情况，至少有一个来源具有可用的文件目录才创建新根。仅 JA 可用时按已确认决策 6 顺序解析候选文章至首篇有可播放媒体，填充该篇；全部无媒体则拒绝建根。首个有效来源不会终止其他来源的核对。查询失败的来源保持待确认，已有有效来源时可以保存作品，之后切换或下载时重试。JA 已确认的文章候选保存到同一作品根。保存当前所选来源的可用文件目录，当前来源不可用时按默认顺序选可用来源。保存 JA 文件时按明确带入或用户选中的文章填充。已有根时复用该根和已存在的曲目。
- 文件区曲目点击：**未入库**条目构造 `RemotePlayable(source = JAPANESE_ASMR, workno = postId, fileKey = mediaKey, remoteUrl = 文章稳定地址, mediaKind, referer = JA Referer, durationMs)` 走 `replaceAndPlayRemote`；**已入库**曲目走 Saved 模式经 `trackId` 回查身份。在已入库作品的库内详情或与库关联的详情中，可对支持下载的 JA 资源直接发起下载。应用在准备任务时填充所选文章的曲目，再绑定目标曲目入队。

## 播放与章节

- **队列身份**：入库曲目由关联表补齐 `postId`、`mediaKey` 和 `kind`，未入库曲目直接使用远程条目的对应值。`MediaItemFactory` 纯组装阶段不做 DB 查询，JA 身份与本地文件判定须在 suspend 阶段（仿 `biliLocalCache`）查 `mediaForTrack` 后传入。在线播放使用 `japaneseasmr://audio?post={postId}&media={mediaKey}`。`queue_items` 按两类写入：**已入库行**沿用 Saved 模式（仅 `trackId`，`remoteUrl` 为 NULL，恢复时经 `mediaForTrack(trackId)` 回查身份）；**未入库行**才保留 `source = JAPANESE_ASMR`、`workno = postId`、`fileKey = mediaKey`、`mediaKind`、`remoteUrl = 文章稳定地址`、`referer = JA Referer`。`mediaKind` 记录站点媒体类型，`mediaId` 仍为 `queueItemId`。临时媒体地址只用于本次网络读取。
- **创建播放项时选择本地或在线**：以曲目身份查找有效的本地文件。命中时创建普通文件播放项，包含从 HLS 封装得到的 M4A。需要在线播放时才根据 `mediaKind` 选择渐进式音频或 HLS，只有在线 HLS 播放项设置 `MimeTypes.APPLICATION_M3U8`。这一步在 `DefaultMediaSourceFactory` 选择组件前完成。
- **数据源分派**：`BiliPlaybackDataSource.open()` 增加 `japaneseasmr` 与 `jamedia` 两个 scheme，`PlaybackService.onPlayerError` 的一次性重试集合加入 `japaneseasmr`。
  - `openJapaneseAsmr` 负责已选为在线播放的条目，按 `(postId, mediaKey)` 从客户端缓存或站点页面解析媒体。在线 HLS 的清单读取始终交付清单文本，本地音频由普通文件数据源读取。
  - **直接音轨**：按页面顺序尝试格式候选；用 `BrowserHttpDataSource`（`httpClient`、JA UA、`Referer: https://japaneseasmr.com/`）打开，404 换下一候选，403/404 时使缓存失效并重解析一次；仍失败报「媒体地址不可用」。Range、206 与服务器忽略 Range 的跳过由该 DataSource 现有逻辑处理；其 open 内 B 站专用的 `%2C→,` 还原须对 JA/jamedia 地址绕过，避免改写查询参数中的编码逗号。
  - **HLS**：经媒体客户端取清单文本，把清单内**所有 URI**（分片、子清单、`EXT-X-KEY` 密钥、`EXT-X-MAP` 初始化段）改写为自有 `jamedia://{host}{path}?{query}` 形式，交给 `ByteArrayDataSource`。`HlsMediaSource` 解析后请求分片时命中 `jamedia` 分支：还原为 `https://` 并附 JA 请求头。这样不修改全局 fallback 头，也不向其他来源请求附加 JA Referer；主清单、媒体清单、AES-128 加密由 HLS 播放器原生处理。状态保持无全局登记表。**子清单递归改写**：`jamedia` 分支取回的响应若以 `#EXTM3U` 开头（按内容嗅探，不靠 Content-Type——实测 TS 的 MIME 错误），必须同样改写后再交付；否则两级清单结构中媒体清单内的绝对 https 分片地址会落进 fallback（默认 B 站头）被 403。相对 URI 先按真实清单 URL 解析为绝对地址再改写。
  - **解析缓存**：按 `(postId, mediaKey)` 缓存候选列表、清单重写结果与章节，TTL 约 5 分钟；`DataSource.open` 在 seek、缓冲重启时重复调用，必须命中缓存。缓存只驻内存，进程结束即丢弃。
- **HLS 依赖**：新增 `androidx.media3:media3-exoplayer-hls:1.10.1`（与现有 Media3 版本一致；当前依赖仅有 exoplayer、dash、session）。MediaItem 的 M3U8 mime 使 `DefaultMediaSourceFactory` 选择 `HlsMediaSource`，与 B 站 MPD 的现成机制同构。
- **副本变更与队列恢复**：发布或删除本地副本后，按曲目身份重建受影响的播放项，复用现有队列同步机制及队列编号，保留当前进度与播放或暂停状态。已删除副本的在线条目回到按身份解析的播放路径。从下载管理播放时按同一身份读取本地副本。重启时 `QueueDao.snapshot()` 投影 `mediaKind`，按最新文件关联重选本地或在线播放项，用 `playback_state` 恢复 `queueItemId + positionMs`。
- **时长回写**：JA 曲目 `durationMs == 0` 时，播放器报告真实时长后回写 `tracks.durationMs`（仅 0 值，不改已有值）；下载完成后同样用 `MediaMetadataRetriever` 回写。
- **章节 UI**：`PlayerChrome` 功能区按当前曲目懒加载章节（复用 `PlayingBiliCollection` 的「按当前条目挂接来源 UI」模式）：入库曲目经 `mediaForTrack`，未入库队列项经 `(postId, mediaKey)` 或解析缓存。解析到章节时显示「章节：当前章名」，点击打开底部弹层（`sheetGesturesEnabled = false`、隐藏默认把手，与队列弹层一致）。高亮当前章，点击 `client.seekTo(startMs)` 并保留弹层。线上与离线共用同一曲目的章节时间轴。

## 下载与字幕

### 直接音轨（第一阶段）

- `DownloadCenter.enqueueTrack` 增加 JA 映射：`source = JAPANESE_ASMR`、`workKey = postId`、`fileKey = mediaKey`；`remoteUrl` 留 NULL（已确认决策 8；现有 remoteUrl 写入开关只对 YOUTUBE 生效，JA 不扩展它），文章地址读 `japaneseasmr_posts.url`。入队、开工、重试、发布沿用 `libraryTarget` / `bindLibraryTarget` / `requireRunningTarget`。`libraryTarget` 的 JA 分支按 `(postId, mediaKey)` 经 `japaneseasmr_media` 回查**当前**曲目（不只看任务里的旧 trackId），**不走通用 workKey↔作品根 RJ 交叉校验**（postId≠RJ）；移除作品后重新入库、曲目编号变化时，重试与开工经现有 `retry`/`downloadOne` 流程中的 `bindLibraryTarget` 把任务重新绑定到回查到的曲目，再由 `requireRunningTarget` 严格校验绑定后的编号与作品根。现有下载查找按这组身份匹配（savedRemotePath/localCopy 天然兼容），跳过按 URL 查任务的分支。
- `transfer()` 增加 `source == JAPANESE_ASMR` 分派，直接音轨走新的 `downloadJapaneseAsmr`：
  - 解析当前候选（命中缓存或重新抓页面），按页面顺序用 `Range: bytes=0-1023` + `Accept-Encoding: identity` 逐个探测（短超时派生客户端），首个 200/206 即选用；404 换下一候选；403 验证页按站点验证失败处理，不循环重试。
  - 落盘 `filesDir/japaneseasmr/{postId}/japaneseasmr-{trackId}/{safeTitle}.{ext}`，扩展名优先取候选地址，未知时按 MIME 兜底（`audio/mpeg` → mp3、`audio/mp4` → m4a），仍未知用 `.m4a`。暂存 `.{jobId}.part`，64 KiB 缓冲、256 KiB 节流写进度、完整性校验、`rename` 后在同一事务 `finishTrackDownload`（更新 `localUri`、写 `localPath`、完成）。
  - 传输中 403/404：使解析缓存失效并重解析一次（沿用通用分支的一次性刷新规则）；仍失败按现有错误文案映射，不泄露地址。
  - 与现有规则一致：入队即校验曲目仍在库；移除作品后任务中止；启动时清理本目录 `*.part` 并登记中断（启动清理的目录扫描列表加入 japaneseasmr 根）；`deleteFailed` 回收本任务暂存。
- **作品级下载**：`prepareWorkSources` 根据已确认可用的来源提供选择，JA 候选可参与选择，即使还未填充曲目。多篇文章通过 `CachePickDialog` 选篇（含「全部文章」）。用户确认下载后，在该操作的准备阶段复用已有曲目，或在现有 RJ 根下事务性填充所选文章，再用 `postId + mediaKey` 找到目标曲目并入队。入队前仍校验作品根和曲目关联存在。第一阶段只入队 DIRECT 曲目，跳过的 HLS 曲目提示「n 条 HLS 曲目仅支持在线播放」；第二阶段一并入队。JA 曲目的同媒体多格式沿用候选选择规则。

### HLS 整段下载（第二阶段）

- 任务 `kind = "JAPANESE_ASMR_HLS"`（由 `japaneseasmr_media.kind` 决定），计入普通下载并发位，不参与 DLsite 整包的互斥。任务行阶段沿用现有 `ProgressPhase`：解析清单 → 下载分片（显示「分片 n/N」与字节统计）→ 封装 → 关联；摘要记录分片数、封装结果与复用情况，不新增逐秒写库。
- **范围校验**：只接受每个分片为独立文件的媒体清单（含 `EXTINF` 与 `EXT-X-ENDLIST`），排除 `EXT-X-STREAM-INF`、`EXT-X-KEY`、`EXT-X-MAP`、`EXT-X-DISCONTINUITY`、`EXT-X-BYTERANGE`。分片解析为绝对 http(s) 地址。遇到超范围结构时提示「暂不支持下载该清单，仍可在线播放」。
- **暂存与续传**：`filesDir/japaneseasmr/{postId}/hls-{shortKeyHash(mediaKey)}/` 保存清单摘要和 `seg-{index}.ts`。摘要包含媒体序号起点，以及按顺序规范化的分片绝对地址（含查询参数）和时长，使用 SHA-256，磁盘只存摘要。重试时先重新取得并验证当前清单，摘要一致才复用已完整校验的分片。摘要缺失或变化时放弃旧分片，按新清单重新下载。逐片校验 188 字节间隔的 TS 同步字节，有 `Content-Range` 总量时校验长度。单片断流最多重试 2 次，用户中止清理当前 `.part` 并保留已完成分片。启动时将残留 RUNNING 任务登记为中断，回收半成品和没有任务行引用的暂存。FAILED 任务的暂存保留供重试，`deleteFailed` 时回收。
- **封装**：用有序文件拼接 `DataSource` 顺序读出全部分片，交给 Media3 `TsExtractor` 提取 AAC 样本与 PTS，再由 `media3-muxer` 的 `Mp4Muxer` 写出单音轨 M4A（新增依赖 `androidx.media3:media3-muxer:1.10.1`，`@UnstableApi`）。时间戳以流内 PTS 为准、首片 PTS 归零，保留整段时间轴。**编码范围**：唯一实测分片为 AAC（PMT stream_type=0x0f）；封装前从提取器 Format 校验编码，MP3 等其他编码暂不支持时标 FAILED 并提示「该流编码暂不支持下载，仍可在线播放」，不让 Muxer 抛原始异常。输出 `.{jobId}.part`，用 `MediaMetadataRetriever` 验证存在音频轨且时长与 `EXTINF` 之和在容差内（如 ±2 秒），并校验样本时间单调（兜住不合规的逐片 PTS 重置），通过后原子改名并走 `finishTrackDownload` 发布；失败保留分片暂存，标 FAILED 可重试。
- 成功后删除暂存目录；章节继续由 `japaneseasmr_media.chapters` 提供，不写入 MP4 元数据。

### 听写与字幕

- 直接音轨：`requestGenerate` 走现有「须先下载」确认。已入库作品的 JA 曲目尚未填充时，与用户主动下载走同一个准备入口，先补齐目标，再由 `startAfterDownload` 绑定对应的 `awaitDownloadId`。`waitingPath` 只接受该任务完成后关联的目标文件，目标缺失时按现有规则失败。
- 第一阶段 HLS：无本地文件且不支持下载，返回 `Unavailable`「HLS 曲目暂不支持下载，无法生成字幕」；第二阶段随 HLS 下载自动可用。
- **文件匹配**：`WhisperCenter.existingFile`、`DownloadCenter.libraryTrack`、`savedRemotePath` 和字幕的本地文件查询统一使用 `JAPANESE_ASMR + postId + mediaKey`。可从已验证的 `trackId` 回查这组身份。JA 查询在通用 `latestForUrl` 分支之前处理，无精确匹配时按缺少可用文件返回。`whisperRemoteIdentity` 加 JA 分支生成 `japaneseasmr://audio?post&media`（现状非 BILIBILI 原样返回 remoteUrl，会把队列行的文章地址当听写身份）。下载行 remoteUrl 留 NULL 后，latestForUrl/activeForUrl 不会命中 JA 行；仍须核对 existingFile、relatedDownload、downloadGaveUp、storedAudioPath、covers、latestDoneForUrl/deleteDoneForUrl 及翻译覆盖 `"url:$remoteUrl"` 键在 JA 路径下均使用曲目级身份。删除下载记录时同样只处理目标曲目的关联。
- **任务与字幕身份**：听写、独立翻译和润色记录中的远程身份字段复用按上述曲目生成的稳定内部标识 `japaneseasmr://audio?post={postId}&media={mediaKey}`。「打开文章」读 `japaneseasmr_posts.url`——下载行不保存文章地址（`remoteUrl` 为 NULL），只有未入库队列行保留文章地址。生成字幕查找、翻译覆盖、任务重试及清理使用曲目级标识，重新入库后也能定位到同一条资源。生成文件继续按音频内容指纹复用，沿用删除副本时保留字幕的选项。
- 字幕来源：JA 的可用字幕来自现有生成流程。`SubtitleSource.cuesFor` 的 JA 原生字幕分支返回空结果，生成字幕和译文继续按上述身份加载。

## 剪贴板导入

- `ClipboardSource` 增加 `JAPANESE_ASMR("Japanese ASMR")`；`clipboardImportTarget` 的 URL 解析循环增加 `japaneseasmr.com` / `www.japaneseasmr.com` 的 `/{数字}/` 文章路径（忽略查询与 fragment），`id = postId`。优先级：明确视频链接（BV/YouTube）> JA 文章链接 > RJ 文本 > 独立 BV > 纯数字，与现有「明确链接优先于文本编号」一致；JA 链接是明确候选，不走「查找作品」确认。
- `ClipboardImportPreview` 增加 `jaArticle` 载荷（`JaPostRef` + 是否有可播放媒体 + `alreadySaved`）。先按文章编号查库，并核对文章及曲目仍在对应 RJ 根下；否则读取文章页，确认 RJ、标题、封面和媒体元素是否存在。预览阶段不发媒体请求。已入库状态针对该篇文章。
- `ClipboardImportDialog` 的来源显示「Japanese ASMR」。确认导入时按 RJ 查找作品根，通过 `importJapaneseAsmrPost` 新建或追加这篇文章。已有 RJ 且无可播放音频时提供「查看详情页」，进入预选该篇的统一作品页。缺失或无法确认 RJ 时提示「无法确认文章对应的 RJ 作品号」，可打开文章原网页。站点验证、网络及页面结构错误按客户端的分类反馈。
- 请求代号、`last_handled` 摘要、前台读取时机、关闭即标记已处理等机制不改；fingerprint 自动包含新来源。
- 需要同步修改的点（按现有代码列全）：`ClipboardImportInput`（枚举、URL 分支、fingerprint）、`ClipboardImportController`（预览载荷、`canImport`、错误文案 when 加 JA 分支——否则落「视频读取失败」措辞）、「无法确认 RJ」新弹窗状态（提示 + 打开原网页动作，现有 detailsOnly 语义是 DLsite 专用）、`ClipboardImportViewModel`（`preview`/`save` 分支与库内查重）、`ClipboardImportDialog`（来源标签与按钮文案）、`MainActivity`（详情页跳转路由）、`AppGraph`（客户端注入）、仓储入库函数、`ClipboardImportTest` 增加解析优先级、前导零、摘要与已入库用例。
- **RJ 入口统一是重构**：现有 RJ 剪贴板保存走 `ensureAsmrWork`（不持 workImportLock、不做多来源核对）。统一到作品级保存须重构该路径并持锁，同步更新其测试；不要继承 ensureAsmrWork 的无锁竞态。
- 顺带修复现存测试失配：`ClipboardImportAndroidTest` 断言「选择入库方式」，现行 Dialog 文案为「选择导入方式」。

## 音频库筛选与作品归属

- 「asmr.one」筛选文案改为「**在线作品**」（枚举 `LibrarySourceFilter.ASMR` 名称可保留）。以 `asmrSourceId` 识别在线作品根，含仅从 JA 入库的 RJ 作品。作品移除边界和播放列表封面归并复用现有 `workRootOf` 与 `LibraryRemovalPolicy`。
- 作品页 `LibraryWorkScreen`：
  - 「下载到本地」根据可下载的当前来源和已入库曲目显示。「关联本地文件夹」只对可用的 DLsite 或 asmr.one 文件来源显示。
  - 来源切换分段按钮按可用来源动态显示 2～3 项，文案「DLsite Play / asmr.one / Japanese ASMR」。已填充的 JA 文件使用库内树浏览（文章子文件夹 + 曲目），播放时解析地址。切换到已确认收录的 JA 时，按所选文章自动填充；来源仍待确认的作品可在同一 RJ 的在线详情重试。目录、滚动位置和迟到响应隔离复用现有机制。
  - 打开作品默认来源顺序：DLsite Play（或原始包）→ asmr.one → JA；仅一个来源时不显示切换。
- 搜索归并：`searchListing` 的作品内结果归并到 `workRootOf` 得到的根。纯数字搜索按现有规则匹配 RJ，JA 文章及曲目名参加现有名称匹配。
- `LibraryViewModel.applyWorkSources` / `showWorkSources`：`prepareWorkSources` 结果增加 `hasJapaneseAsmr`；有 Play/`purchased`/JA 任一且来源数 ≥2 时显示切换；后台已购检查逻辑不变。

## 验收

### 解析与搜索

- JVM 单测使用固定 HTML fixture（地址全部替换），覆盖日文和 RJ 搜索、分页、空结果、验证页、5xx、网络失败、多来源部分失败、旧响应隔离与 `jaPosts` 跨页累积去重。仅 JA 命中的 RJ 卡片仍打开统一作品页，后续合并其他来源时保留文章候选。节流用假时钟验证列表间隔和详情跳过列表冷却。
- 样本覆盖同 RJ 多篇（`/135575/`、`/123908/` 结构）、无法提取 RJ、文章与当前 RJ 不同、前导零（`RJ01258546` 全程保留）、同一媒体多格式、多个曲目、重复章节表及行链接、格式候选失效、`data-src` 封面、文章日期与发售日区分。
- `mediaKey` 单测：标题+描述优先、重复/缺失回退路径摘要、查询参数与 fragment 不参与、刷新后同页重算稳定。

### 入库与迁移

- v20→21 升级与新装：用 `PRAGMA table_info` / `sqlite_master` 对比结构与索引一致；旧数据（作品、曲目、下载、队列、字幕关联）不受影响。
- 验证 JA、asmr.one 和 DLsite Play 以任意顺序加入同一 RJ 根。仅从 JA 入库的 RJ 作品出现在「在线作品」筛选。RJ 无法确认时不建根。同篇重复导入复用曲目编号、`localUri`、下载与字幕关联。同 RJ 多篇可分别播放和下载，移入文章目录保持曲目和关联，整作品移除后重新导入可回链已完成下载；移除重导后 FAILED 任务重试会按 `(postId, mediaKey)` 重新绑定当前曲目编号继续下载。
- 覆盖 asmr.one 与 JA 都收录、仅 asmr.one、仅 JA、两站均未收录四种组合，并分别验证已购与未购时的 DLsite Play 选项。JA-only 未购作品在存在可信候选时 canSave 为真，点击保存核验媒体后建根；asmr.one 与 DLsite 均无资料时仍可用 JA 标题/封面/RJ 展示详情；后续页合并与 markListed 替换不丢 jaPosts，`jaOnly` 仅在 asmr.one/DLsite 实际命中时清除。未购且两站均未收录时仍显示商品详情，无法创建无资源的作品根。首次保存时验证首个来源有效后仍核对其他来源；JA 查询失败保持待确认，可保存已有可用来源。覆盖链接预选文章、切换后恢复列表位置与迟到结果不抢切换。

### 第一阶段端到端

- 用一篇直接音轨和一篇 HLS 作品验证完整播放、拖动、后台播放、进程重启续播、删除副本后恢复在线；队列中未入库 JA 项（含 `mediaKind`）重启后仍可播放。HLS 用本地 fixture 覆盖两级清单（主清单→媒体清单）与绝对/相对分片地址，验证子清单递归改写后分片仍带 JA 头。
- 直接音轨下载完成后在下载管理可见、断网可播、可提交生成字幕；失败重试、取消清理、移除作品后任务中止；候选失效时换下一候选。移除作品后从「下载管理」（StoredFilesScreen）与「下载任务」播放 JA 成品仍保留曲目身份，字幕匹配与删除正常；播放错误界面（媒体 403/404、验证页）不出现完整媒体地址。
- 同篇文章提供两条不同内容的音轨时，仅下载第一条，再从第二条发起听写，应请求第二条的前置下载。两条都生成字幕后，各自只加载、重试或删除自己的结果（下载行 remoteUrl 为 NULL，验证 latestForUrl 类查询不跨媒体串号）。覆盖从下载管理播放和整作品移除后重新导入。验证单媒体页面的带章节直接音轨显示章节入口，多曲目文章不显示章节，未提供章节的 HLS 仍可播放。
- 从 asmr.one 保存作品后，直接选择 JA 下载，确认后自动填充所选文章并绑定正确曲目。验证多篇选篇、重复下载和准备期间移除作品的场景。第一阶段跳过 HLS 并给出提示。
- 剪贴板：文章确认 RJ 后可预览、导入或定位到统一作品页；已有同 RJ 根时可追加新文章；无法确认 RJ 时只提示或打开原网页。验证站点受限、网络错误和摘要去重。

### 第二阶段端到端

- 测试资源用预先生成的短 AAC/TS 分片与 `.m3u8`，由本地 HTTP 服务提供。验证分片下载、中止与续传、单片重试、封装和发布，并用现有 `AudioPcmReader` 解码成品确认可读。验证相同清单续传会跳过完整分片，替换清单但保留同样分片序号时重新下载。
- 验证同一个 HLS 队列项在线播放 → 下载完成 → 断网播放 → 删除副本后恢复在线播放。重启能按现有副本状态选对播放组件，保留队列编号、进度与章节跳转。从成品发起生成字幕。
- 损坏分片、缺少分片和超范围清单明确失败。验证暂存清理、孤儿回收与 `deleteFailed` 回收。
- 编译使用 `./gradlew :app:compileDebugKotlin --no-configuration-cache`，定向单测用 `--tests`；非 UI 仪器测试在本机 `audiotest_api36` 模拟器逐类运行（`class` 参数不支持逗号多类）。真实站点与真机验证按仓库约定取得授权后进行，编译结果与实际运行结果分别说明；不在本轮实施中访问站点验证接口。

## 实施顺序与文档同步

按以下顺序实施。第 1～7 步对应第一阶段，第 8 步对应第二阶段。每阶段分别验收并同步文档。

1. `japaneseasmr/` 客户端、模型、解析、节流与缓存（含 jsoup 依赖）＋ JVM 解析测试；
2. 数据库 v21（列、表、索引、`MIGRATION_20_21`、DAO）＋ 迁移测试；
3. 仓储入库/对账、音频库筛选与作品归属扩展（含 invalidation 表清单、StoredCatalog 白名单）；
4. 搜索合并、路由与在线详情/文章详情 UI、入库按钮（含 canSave/EmptyHint 门槛、合并携带 jaPosts）；
5. 播放（直接音轨 + HLS 在线、`mediaKind`、章节弹层、时长回写）；
6. 作品级来源核对和下载前自动填充、直接音轨下载、下载管理、曲目级听写与字幕关联；
7. 剪贴板导入；
8. HLS 清单校验、分片续传、整段封装与离线播放；
9. 文档与回归。

文档同步：

- `Android/README.md`：筛选文案「asmr.one」→「在线作品」；在线搜索与作品详情增加 JA 来源；音频库作品页三来源切换；播放页章节入口；下载管理 JA 音频与 HLS 支持范围；剪贴板 JA 链接；项目结构中的数据库版本（当前仍写 17，一并更正为 21）与 AppGraph 组装顺序。
- `AGENTS.md`：§1 来源列表、§3.1 文档索引加入 `japaneseasmr-api.md`、§4.1 版本改为 21 并记录 20→21 内容、§5 筛选名称和 RJ 作品根规则、§6 记录 DLsite 商品全集与三种文件来源的关系及剪贴板新链接类型、§7 听写 HLS 限制。
- `Android/docs/japaneseasmr-api.md`：追加实施附录（客户端、节流、媒体键、HLS 范围与封装、错误分类），保留原有调查记录与验证限制。
- 实施完成后在本计划末尾补「实施与验收结果」，分别记录编译、单测、模拟器与真机结果。

## 审查修订记录（2026-11-18）

对照代码逐项审查后修订：新增已确认决策 5～8（mediaKey 接受重复、JA-only 顺序解析保存、章节仅单媒体页面、下载行 remoteUrl 留 NULL）；补齐 canSave/EmptyHint 门槛、搜索合并覆盖规则、HLS 子清单递归改写、libraryTarget JA 校验分支、invalidation 表清单、snapshot/observe 双投影、非穷举来源条件审计、whisperRemoteIdentity、MediaItemFactory 两阶段约束、%2C 绕过、播放错误脱敏、Coil 主机重启登记、剪贴板错误分支与 RJ 保存重构等耦合点；第二阶段增加编码范围校验与 PTS 单调校验。另发现现存 ClipboardImportAndroidTest 文案失配，随本次修复。

二轮收口（同次审查补充）：JA-only 保存入口以可信候选为准，媒体核验放在点击之后，且详情 facts 回退链接入 JA 的标题/封面/RJ（现有 `nextFacts == null` 提前退出会挡住 JA-only 详情）；旧下载重试按 `(postId, mediaKey)` 重新绑定新曲目编号（复用现有 retry/downloadOne + bindLibraryTarget）；下载管理页（StoredFilesScreen）与任务页两个播放入口都保留曲目身份；`jaOnly` 仅由 asmr.one/DLsite 实际搜索命中清除；队列与下载行的文章地址写入统一为「已入库/下载行留空，仅未入库队列行保留」；播放错误出口过滤整条 cause 链。


## 实施与验收结果（2026-11-18）

- 第一阶段已接入关键词搜索、RJ 归并、三来源详情、剪贴板导入、直接音轨与 HLS 在线播放、章节、入库、直接音轨下载及生成字幕。作品来源按实际目录归属判定，以兼容 asmr.one 内部编号与 RJ 数字部分不同的记录。
- 第二阶段已接入独立 AAC/TS 分片下载、清单摘要续传、M4A 封装以及成品发布。在线播放改写清单地址时可逆保留原 HTTP(S) 协议及编码后的查询参数。
- Android Kotlin 编译与调试仪器测试包构建通过。全量 JVM 单元测试通过 426 项，涵盖固定页面解析、节流、错误分类、跨页归并、清单改写及剪贴板优先级。
- 本机 SQLite 校验中，20→21 迁移的列、索引、外键与新装一致，旧队列记录保留。两段合成 AAC/TS 测试资源已在本机核验可解码。
- 本机 `audiotest_api36`（Android 16）仪器测试通过 45 项。Japanese ASMR 专项 4 项覆盖 Room 实际升级、文章目录重排与下载回链、两级在线播放清单以及分片续传和封装成品解码。其余 41 项覆盖旧版迁移、播放队列、下载、字幕及剪贴板解析。
- 剪贴板解析用例原先随界面测试初始化 Espresso，触发模拟器的 `InputManager.getInstance()` 兼容问题。移入独立测试类后重跑通过，同一用例验证了从 Android 剪贴板 URI 识别 Japanese ASMR 文章链接。Compose 界面用例留待兼容的设备环境运行。
- 真实站点和真机按计划留到发布前验收。
