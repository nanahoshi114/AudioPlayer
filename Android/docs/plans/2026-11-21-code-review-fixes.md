# Android 审查问题修改计划

## Context

`Android/docs/code-review/2026-11-21-working-tree-review.md` 列出 12 项问题（1 项 P1、9 项 P2、2 项 P3）。本次逐条核对全部确认成立，并按已确定的产品与实现取舍修复：

- 第 2 项采用「公开 HTTPS + 私网拦截」，每次重定向逐跳校验。
- 第 9 项维持单入口下载，调整下载界面的「全部篇目」与文档，不实现多入口顺序下载。
- 第 4 项保留现有 `PlaybackService` 队列/下载变化时的替换循环，只补「本地文件被外部删除后无在线回退」的缺口并加回归测试。

第二轮核对后采纳六点补强：重定向在连接前逐跳校验（含 IP 字面值与代理）、恢复流程复用 `withRevalidatedQueue` 的现有加锁方式、区分篇目验证与媒体服务器验证、刷新时显式关联旧曲目与旧队列项、探测读取有上限前缀并在恢复时重建完整清单、以及按整次任务判断 HLS 是否发生传输。验收命令补充 `JaNetworkPolicyTest`、`:app:compileDebugAndroidTestKotlin` 与两条完整调用链的仪器测试。

数据库结构不变，无迁移。

## 逐条核对结论

| # | 结论 | 关键位置 |
| --- | --- | --- |
| 1 | 成立。失败弹窗确认后会删除曲目、级联队列/播放列表、失败任务；当前项还会清空整个播放器列表。 | `PlaybackService.kt:106`、`PlayerClient.kt:68`、`LibraryRepository.kt:807`、`PlayerChrome.kt:241` |
| 2 | 成立。播放、探测、HLS、直链下载共用的 OkHttp 默认跟随重定向且无主机/私网限制。 | `JapaneseAsmrClient.kt:198`、`BiliPlaybackDataSource.kt:193`、`JapaneseAsmrHlsDownload.kt:46`、`DownloadCenter.kt:117` |
| 3 | 成立。当前项被删除时 `clearMediaItems()` 清空播放器，但数据库只删受影响队列项。 | `PlayerClient.kt:75-79` |
| 4 | 部分成立。下载完成/应用内删除副本已有替换循环；缺口是外部删除本地文件或 content URI 失效时，冻结的 `file:/content:` 项无在线回退。 | `MediaItemFactory.kt:41`、`PlaybackService.kt:184-210` |
| 5 | 成立。清理只按 `trackId` 匹配队列；按媒体身份入队的失效项不会被清理，当前项也不受保护。 | `LibraryRepository.kt:755-787` |
| 6 | 成立。传输阶段遇验证抛 `JaWorkAddressExpired`，任务普通失败，不进入 VERIFY，也不记得失败文件。 | `JaWorkClient.kt:443-467`、`DownloadCenter.kt:385-412` |
| 7 | 成立。探测将 200 响应长度记为文件大小，无 Content-Type 校验，HTML 页面可能被发布。 | `JaWorkClient.kt:376-390`、`443-467` |
| 8 | 成立。JA `storedEntries` 与 DLsite `allEntries` 跳过非 AUDIO；JA 作品根又跳过目录扫描，字幕不会出现在下载管理。 | `StoredFiles.kt:153-171`、`218-220` |
| 9 | 成立。但按决定保持单入口；「全部篇目」只扩大候选后仍强制选一个入口，文案与文档需调整。 | `LibraryViewModel.kt:673-780`、`LibraryRows.kt:602` |
| 10 | 成立。任一分片复用即整项标记 REUSED，本次实际传输量与耗时被丢弃。 | `JapaneseAsmrHlsDownload.kt:72`、`TaskProgress.kt:256-268` |
| 11 | 成立。README 分别写第 17 版和第 21 版，实际为 22。 | `README.md:380,385`、`AppDatabase.kt:61,107` |
| 12 | 成立。排队中的 JA 整作任务重启后自动接手，文档笼统写成全部中断。 | `DownloadCenter.kt:630-695`、`README.md:250`、`AGENTS.md:100` |

## 实施步骤

### 1. 播放失败不再删除库内数据（第 1、3 项）

- `PlaybackService.onPlayerError` 按失败类型分流，仍保留终态错误不自动重试：
  - `MISSING` / `GONE` / `MEDIA_GONE` / `REOPEN`：播放页提示固定文案「音频播放失败，请检查网络后重试。如果仍无法播放，可到作品页点击「刷新JA篇目」。」，只提供「关闭」。
  - `VERIFY`（篇目网页验证）：同一文案，并增加「验证并继续」；仅当当前队列项带有数字 postId、可走现有 `PLAYBACK` 验证路由时显示。按钮先关闭提示，再调用 `onVerifyJapaneseAsmr(postUrl, queueId, fileKey)`。
  - `MEDIA_VERIFY`：保留现有「音频服务器要求验证，暂时无法在 App 内播放」。现有 `PLAYBACK` 路由只读取并验证原篇目，无法解除媒体服务器拦截，因此不显示「验证并继续」。
  - `WORK_FILE_MISSING`、`UNSAFE` 等已有明确原因：保留各自文案，不套用通用提示。
  - 断网、连接失败、服务端错误：自动重试后仍失败时，提示包含「请检查网络后重试；如果仍无法播放，可到作品页点击「刷新JA篇目」」，不进入删除流程。
- 新增播放失败提示状态（沿用或改造 `PlayerClient.invalidJa`，改为 `JaPlaybackFailure`）；全局提示与播放页对话框使用同一条普通失败文案，避免重复显示两种说法。
- 删除 `PlayerClient.clearInvalidJa` 的删除分支与 `LibraryRepository.clearInvalidJaTrack`；删除 `player_clear_invalid_title` / `player_clear` 等死字符串。
- 同步删除文档中的「播放失败可确认清除」承诺：`Android/README.md:303`、`Android/docs/japaneseasmr-api.md:287` 改为「播放失败只提示；库内清理由显式刷新处理」。
- 队列单项移除继续走 `PlayerClient.remove`（`removeMediaItem(index)`），不调用 `clearMediaItems()`；补充测试确认剩余顺序与续播。
- 不删除曲目、队列、播放列表、字幕、任务；显式刷新仍按原规则清理。

### 2. 限制 JA 原生媒体请求及重定向目标（第 2 项）

- 在 `japaneseasmr` 包新增统一 URL 策略，例如 `JaNetworkPolicy`：
  - 页面/清单：仅 `japaneseasmr.com` / `www.japaneseasmr.com` 的公开 HTTPS。
  - 媒体：公开 HTTPS（`https`、443、无用户名/密码）。
- 重定向必须在**发出下一跳请求之前**校验，不能只在已经建立连接后拦截：
  - 客户端设置 `followRedirects(false)`、`followSslRedirects(false)`，用应用拦截器或统一 helper 手动循环 3xx；每一跳先校验 URL，再 `chain.proceed`，并限制最大跳数。这样被拒绝的目标不会收到连接。
  - 设置 `proxy(Proxy.NO_PROXY)`（不沿用系统代理），避免代理解析并访问私网地址；数字形式的 IP 主机同样按字面值检查。
  - 自定义 `Dns` 过滤解析结果：拒绝回环、私网、链路本地、组播、保留地址（含 IPv4/IPv6、IPv4-mapped IPv6；含 `100.64.0.0/10` 等），只把通过的地址交给连接；关注 DNS rebinding。
  - `JaWorkClient` 已有自己的手动重定向与 Buzzheavier/MEGA 主机白名单，不能叠加新的自动重定向拦截器，否则会吞掉它依赖的 3xx/`HX-Redirect` 处理；给它单独的、同样带策略与 DNS 过滤的客户端。
- `JapaneseAsmrClient.newClient()` 拆分为站点、媒体、整作下载三种构建（构造函数默认 `pageClient = newSiteClient()`、`mediaClient = newMediaClient()`）：
  - 站点客户端限制 host 为 `japaneseasmr.com` / `www.japaneseasmr.com`；媒体客户端允许任意公开 HTTPS 主机。
- 新增 `JaFailure.UNSAFE` 与用户文案（例如「音频地址不在允许的公开 HTTPS 范围内，已停止访问」）；错误与日志不得包含完整临时地址。DNS 拒绝需能映射为该原因，而不是被吞成普通连接失败。
- 覆盖：`BiliPlaybackDataSource.openJaMedia`/候选探测、`JapaneseAsmrHls.kt` 重写后的分片与密钥地址、`JapaneseAsmrHlsDownload`、`DownloadCenter.jaProbeHttp/jaTransferHttp` 及直链下载。
- 违规地址抛出面向用户的通用 IO 错误，不记录完整临时地址。
- 验收：先让获准的第一跳返回跳转，再断言被拒绝的目标没有收到 TCP 连接（用记录 Dns/本地 mock 服务器断言），直接写私网 IP 的地址同样被拒绝。

### 3. 本地副本失效时回退在线（第 4 项）

- 保留 `MediaItemFactory` 现有行为：有效本地副本仍直接生成 `file:`/`content:` 媒体项；下载完成或应用内删除副本继续由 `PlaybackService` 的队列/下载替换循环更新（该路径已核对成立）。
- 新增 `JaLocalCopyLost`（包装本地打开失败的 IOException/SecurityException）：`BiliPlaybackDataSource.open` 的本地分支在 `fallback.open` 失败时抛出；`PlaybackService.onPlayerError` 识别当前项为 JA 后，调用 `PlayerClient` 的一次性恢复方法。
- 恢复方法**不要在 `queueMutex` 外层再持锁**调用 `withRevalidatedQueue`：该方法自己会在锁外探测、再取 `queueMutex` 复核，重复加锁会卡死。直接调用现有 `withRevalidatedQueue`，复用其「锁外快照 + 锁内重读比对」流程。
- 替换前记录失败项的 mediaId、当前位置和 `playWhenReady`；应用重建结果时确认失败项仍是当前项。若用户已切歌则不替换、不打断；以应用替换结果时的最新播放意图为准，探测期间用户暂停则保持暂停，仍请求播放才按原位置继续。一次恢复的标记不得因同一队列项更换媒体地址而清除，恢复失败后不循环重试。
- `playbackTarget` 会重新校验 `localUri`（文件存在且非空 / `content:` 可读），失效时自动生成在线身份项。
- 整作 `japaneseasmr://file?` 与身份项的每次打开解析逻辑保持不变；入队后新下载完成的本地副本仍由现有替换循环接管。
- HLS 本地副本是已封装的 M4A，重建时必须按在线身份选择 HLS MIME；恢复发生在媒体项重建阶段，避免把本地 M4A 当 HLS 解析。
- 回归测试覆盖：入队后下载完成优先本地；应用内删除副本后在线；外部删除本地文件后触发 `JaLocalCopyLost` 并重建为在线项；`content:` 权限被撤销时同样回退；恢复期间用户切歌或暂停不被打断；恢复失败只尝试一次。

### 4. 刷新识别按媒体身份入队的队列项（第 5 项）

- `LibraryRepository.refreshJapaneseAsmrPost` 清理阶段分两步，不能只依赖 `resolveQueuedJaMatch` 的「新篇目里还有没有这条音轨」：
  1. **旧曲目 ↔ 旧队列项关联。** 先按同篇目、同媒体键把 `japaneseasmr_media` 行关联到队列行；没有同键时，仅当该篇目下旧队列项的 `mediaPathHash`（或旧格式 `p:` 摘要）与媒体行唯一对应时才补匹配。资料不足、多个候选或摘要歧义时不建立关联，保留并保护。
  2. **确认消失的旧队列项。** 对 `trackId == null` 的 JA 队列项，独立按 `resolveQueuedJaMatch` 判定：只有 `cleanup && updated.complete`、匹配结果为 `Gone`、且不是当前播放项时才删除；`Ambiguous` / `InsufficientIdentity` 一律保留。该判定不受「库内音轨集合是否变化」限制。
- 当前项按媒体身份保护：既保护同一 `trackId` 的行，也保护与将被删除媒体行同键/同摘要的 `trackId == null` 行。
- 删除确认消失的队列行时，失败关联翻译任务，并把 id 加入返回的 `queueIds`，由 `PlayerClient.removeQueueMedia` 同步播放器；保留字幕与任务记录。
- 曲目清理仍遵循 P1 规则：仅当 `cleanup && updated.complete` && 媒体列表相对旧库发生变化、无有效本地副本、当前未播放时删除曲目及播放列表关联。
- `queueIds` 可改名为 `removedQueueIds`，同步调用点。
- 测试：
  - 扩展 `JapaneseAsmrAndroidTest.completePureRemoval...`：加入 `trackId == null` 的 JA 队列行，覆盖当前项保护、已消失项删除、歧义/资料不足保留。
  - 新增一例：库内现有音轨完全没变，只有一条从在线详情入队的旧音轨已消失，仍应删除该队列项并返回 `queueIds`（当前清理入口会跳过这个场景）。用相同数据额外验证普通同步保留该队列项。
  - 增加旧曲目 ↔ 旧队列项的关联单测：同媒体键优先、唯一路径摘要补匹配、多候选保留。

### 5. 传输阶段验证进入等待并可继续（第 6 项）

- `JaFileTransfer.Buzz` 增加 `sourceLink`（`buzz()` 里的入口链接）及探测时确认的文件托管页。浏览器捕获直链后 `buttonPage` 可能为空，托管页仍须保留。
- `JaWorkClient.transferBuzz` 增加 `onVerify`：遇挑战时抛出携带已确认的文件托管页与 `sourceLink` 的 `JaTransferVerification`；`download()` 在重试循环中先于普通 IOException 立即重抛，不按普通 IO 重试。
- `DownloadCenter.downloadJaWork` 捕获该异常，调用 `waitForJaWorkVerification`：`WaitingWorkVerification(entry, JaBuzzVerification(entry, manifest.sourcePage, listOf(页面), fileLink), fileLink)`，任务转为 `FAILED + JA_VERIFY_PHASE`。
- `JaWorkManifest` 增加可空 `sourcePage`（由 `manifest()` 的首个入口页填入），供 `completeJaVerification` 重新解析整份清单时使用；`JaBuzzVerification.sourcePage` 允许为空，为空时 `manifest(entry, null, confirmed)` 自行重新读取原入口页。
- `completeJaVerification` 保留原入口、任务已确认的入口/文件信息和 `verifiedBuzzPages`（同时按 `page.fileLink` 与 `visible.url` 记录），只更新失败文件的新地址，再重建完整清单。
- 确认是传输验证时直接进入等待。普通地址过期可重新读取一次原入口。用户完成网页验证时，按上一条保留此前确认的文件。
- 统一恢复语义：允许在缺少 `sourcePage` 时重新读取原入口；如果仍无法确认完整清单（入口页读不到、文件列表不完整），停止发布并明确失败，不能用手上的一张托管页拼出不完整清单。
- 进程内用 `jaWaitingEntries` 记住失败文件；进程重启后按任务保存的篇目与入口摘要重新定位原入口，并重新读取清单。失败文件页及已确认的地址只保留在当前进程内。
- 测试：`JaWorkDownloadTest` 增加传输阶段 403/challenge → 等待验证、记住文件、继续后从该文件恢复，并断言其他已确认文件没有丢失；`JaWorkFilesAndroidTest` 可模拟 `JaTransferVerification` 验证任务进入 `FAILED + VERIFY`。

### 6. Buzzheavier 探测与传输核验文件响应（第 7 项）

- 先识别网页，再接入等待验证流程：
  - 探测改为读取**有上限的响应前缀**（例如请求 `Range: bytes=0-4095`，或 200 时 `peekBody(4096)`），不再只取一个字节；`Content-Range` 总长度仍用于文件大小。
  - 拒绝 `text/html`、`application/xhtml+xml` 及 HTML 前缀（`<!doctype`、`<html`、`<head`、`<body`）；已知挑战特征须在 200/206 正文中也能识别。验证页转第 5 项的等待验证流程，普通网页报错并停止发布。
  - `application/octet-stream` 或缺失 Content-Type 时保留兼容，但通过前缀识别并拒绝网页，不得把网页长度记为文件大小。
  - 404/410 与验证页继续走现有过期/验证流程（第 5 项）。
- 首次下载和**续传**都要校验：`transferBuzz` 在 offset = 0 和 offset > 0（206）的响应都调用同一校验；`copy` 前再校验一次，避免探测后地址返回网页。
- 探测被拒绝时不写入 `JaRemoteFile.size`，避免后续按原文件名发布。
- 新增用户可读错误字符串（例如「下载地址返回了网页，暂时无法继续」），不得输出完整临时地址。
- 测试：探测收到 200 HTML、传输首次收到 HTML、续传 206 收到 HTML 均失败；正常 206/200、缺失 Content-Type 的二进制响应不受影响。

### 7. 下载管理列出整作附带字幕（第 8 项）

- `StoredFiles.list()`：
  - JA `jaDownloads().storedEntries()`：除 `AUDIO` 外，把 `isSubtitleFile(name)` 的 `FILE` 行交给 `rememberFile` 进入 `looseSubtitles`；其它 `FILE`/`FOLDER` 仍跳过。
  - DLsite `dlsiteOriginals().allEntries()`：同上，允许 `TEXT` 且扩展名为 lrc/srt/vtt 的条目。
- `subtitlesFor` 对 `jaWorkRoot` 目前返回空列表，依赖清单补充；实现后保留注释与行为一致。
- 测试：作品只剩字幕时仍列出、可删除；非字幕 FILE 不被当作音频。

### 8. 调整「全部篇目」为单入口语义（第 9 项）

- 复用 `CachePrompt.JapanesePosts.refresh` 区分刷新和下载：
  - 刷新流程保留「全部篇目」，逐篇刷新。
  - 下载流程去掉「全部篇目」选项，只列出各篇目；`cacheJapaneseWork` 始终只选一个去重入口。
- 更新 `strings_library.xml`：`library_ja_multiple_download_message` 改为「这部作品有多个篇目，请先选择要下载的一篇。」；下载提示不再出现 `library_ja_all`。
- 同步 `README.md:67`：「选择 Japanese ASMR 时，多个篇目先选择一篇」；`AGENTS.md:133`：「Japanese ASMR 多篇文章先选择一篇，再在其去重后的下载入口中选择一个」。
- 不改变 `jaWorkEntries` 的去重与 `enqueueJaWork` 的任务占用规则。

### 9. HLS 部分续传保留本次统计（第 10 项）

- 按**整次任务**判断是否发生过传输，而不是只看最后一次 `download()` 调用：`downloadJapaneseAsmr` 内部会因地址刷新重新进入 `jaHls.download`，同一个 `TaskReporter` 的累计字节必须参与判断。
- `JapaneseAsmrHlsDownload.download`：移出循环内的 `report.reused()`；仅当「本次所有分片均命中暂存」且「任务累计传输字节为 0」时调用一次 `report.reused()`。前一轮已下载过分片、最后一轮全部命中暂存时，仍保留整次任务的字节与耗时。
- 实现可放在 HLS 调用点（例如 `report` 提供累计字节查询）或 `TaskProgressTracker`（`reused` 注解在已有传输字节时不生效），二选一并补测试说明。
- 部分复用时按本次实际传输量与耗时结束；暂存分片仍通过 `report.units` 计入总体分片进度，实际传输字节单独统计。
- 测试：
  - 同一 reporter 跨两轮：第一轮下载分片 0 后在分片 1 失败，在两轮间用测试夹具补入完整分片 1，第二轮全部分片命中暂存；断言结果为 `COMPLETED`（非 `REUSED`）、`bytes > 0`、`elapsedMs != null`。
  - 用户手动重试使用新统计的既有场景保持通过。
  - 首次就全部分片复用的一轮才断言 `REUSED` 且统计为空。`TaskProgressTest` 现有断言保持不变。

### 10. 文档（第 11、12 项）

- `Android/README.md:380`：`data/db` 行改为「Room，当前第 22 版」。
- `Android/README.md:385`：改为第 22 版，并补充 21→22：JA 整作下载清单及压缩包版本表、任务入口摘要及确认选项、`japaneseasmr_media.pathHash`、`queue_items.mediaPathHash`。
- `Android/README.md:250` 与 `AGENTS.md:100`：写明重启恢复差异——仍有效且排队的 JA 整作任务下次进入应用自动接手；传输中被终止的任务标为中断可手动重试；`FAILED + VERIFY` 等待「验证并继续」。
- 视需要同步 `Android/docs/japaneseasmr-api.md` 的整作下载恢复说明。

## 验证

1. 编译：
   - `cd Android && export GRADLE_USER_HOME="$HOME/.gradle" && ./gradlew :app:compileDebugKotlin --no-configuration-cache`
   - `./gradlew :app:compileDebugAndroidTestKotlin --no-configuration-cache`（新增/修改仪器测试后必须）
2. JVM 单测：
   - 新增 `JaNetworkPolicyTest`：站点/媒体 URL 策略、重定向逐跳、IPv4/IPv6 私网与保留地址、IP 字面值。
   - 扩展 `JapaneseAsmrTest`、`JaWorkDownloadTest`：探测/传输遇 HTML 与验证页、传输阶段记住失败文件、HLS 重写地址仍受限。
   - 扩展 `StoredFilesTest`：字幕文件识别与去重（如可离线构造）。
   - 扩展 `TaskProgressTest`：部分续传不标 REUSED，保留本次字节与耗时。
   - 命令：`./gradlew :app:testDebugUnitTest --tests '*JaNetworkPolicyTest' --tests '*JapaneseAsmrTest' --tests '*JaWorkDownloadTest' --tests '*StoredFilesTest' --tests '*TaskProgressTest' --tests '*MixedQueueTest' --no-configuration-cache`
3. 模拟器非 UI 仪器测试（逐类运行）：
   - `JapaneseAsmrAndroidTest`：身份入队清理/保护、旧曲目↔旧队列项关联、HLS 部分续传统计（含同一 reporter 跨两轮）。
   - `JaWorkFilesAndroidTest`：作品字幕发布并在下载管理列出、验证状态转换。
   - `MediaItemFactoryAndroidTest`：本地副本失效后的媒体项与在线身份。
   - **播放服务完整链路**：用确定性的本地测试数据（临时文件/内存数据库）绑定 `PlaybackService`，播放一个本地文件后删除文件并触发重新打开，验证 `JaLocalCopyLost` → 重建当前项 → 在线播放；同时覆盖恢复期间切歌/暂停与只尝试一次。
   - **下载验证完整链路**：用本地 mock HTTP/确定性 `JaWorkClient` 驱动 `DownloadCenter`，分别验证探测或传输遇普通网页时失败且不发布，以及遇验证页时进入 `FAILED + JA_VERIFY_PHASE`、验证后重建完整清单并继续下载；必要时为 `DownloadCenter` 增加 internal 构造参数或用例入口注入测试客户端。
   - `StoredCatalogOverlayAndroidTest` 或新增作品字幕列表用例：作品只剩字幕时仍可查看/删除。
   - 命令见 `AGENTS.md` 的 `connectedDebugAndroidTest` 说明；`class` 参数只支持单个类。
4. 手工/联网检查（需用户授权与真实站点）：
   - 播放失败不删除库内数据；当前项从队列移除后其余曲目继续播放。
   - 篇目验证失败显示「验证并继续」；媒体服务器验证只提示原因，不显示无法恢复的按钮。
   - 下载完成后同一队列项改播本地文件；删除副本后恢复在线。
   - 刷新保护从在线详情入队的当前项并清理已消失的失效项；库内音轨不变、仅旧队列项消失时也能清理。
   - Buzzheavier 传输遇验证可「验证并继续」并从失败文件继续；探测、首次传输、续传收到 200/206 HTML 均被拒绝。
   - 重定向到私网/本机地址时不产生目标连接；直接写私网 IP 同样被拒绝。
   - 「全部篇目」在下载流程不再暗示多入口同时下载。

## 实施与验证记录

- 已落实 12 项修复。播放失败只提供恢复提示，显式刷新分别处理库内曲目与仅按媒体身份入队的项目。音频队列可在移除当前失败项后继续播放。
- 原生网络请求在连接前逐跳校验，整部作品下载沿用托管站点的主机限制。Buzzheavier 普通网页响应会停止发布，验证页走等待验证流程，在当前进程内从失败文件页继续。
- 下载管理直接从 Japanese ASMR 与 DLsite 的已发布清单列出附带字幕。整部作品下载明确选择一个篇目的一个入口。分片下载保留同一任务跨重试的实际传输统计。
- Debug 主代码与仪器测试代码编译通过，应用和测试 APK 构建通过，468 项单元测试通过。Android 16 隔离模拟器逐类执行 7 组仪器测试，共 43 项通过。
- 模拟器覆盖失效本地文件的在线回退、恢复期间暂停或切歌、回退失败只尝试一次、队列续播与篇目清理，以及下载验证、普通网页拒绝发布、作品字幕的列出与删除。网站响应使用测试数据，真实站点和真机验证待执行。
