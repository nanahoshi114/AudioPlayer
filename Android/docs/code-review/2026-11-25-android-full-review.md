# Android 端全量代码与测试审查报告

- **审查日期**：2026-11-25（第 2 组边界审查于 2026-11-26 续跑补全）
- **基线与范围**：`HEAD 8950095` 及当前工作区未提交改动（全程只读审查，未改动业务代码，未执行构建、真机/模拟器运行或线上接口请求；第 2 组额外在内存中执行迁移 SQL 核对数据库表结构）。
- **代码体量**：
  - 主源码：188 个 `.kt` 文件 / 52,486 行（`com.nanahoshi.audioplayer` 182 文件 50,747 行 + `com.k2fsa.sherpa.onnx` 6 文件 1,739 行）。
  - 测试源码：127 个 `.kt` 文件 / 17,734 行（`src/test` 86 文件 10,346 行 + `src/androidTest` 41 文件 7,388 行）。
- **审查分工与覆盖说明**：
  - **5 路全量代码与测试调研**（并行只读调研）：覆盖 UI 核心与基建、次级功能界面、数据层/数据库/播放链路、在线客户端与核心算法、完整单元测试与仪器测试套件。
  - **复杂逻辑边界审查（`gpt-6.2-astra` / `codex-max`，只读模式）**：
    - **第 1 组（已完成，全部 18 项已对照源码核实）**：下载、解压、存储、库事务、并发与恢复（会话 ID `01a21350-75f0-7893-90a0-212e9358cce4`）。
    - **第 2 组（已于 2026-11-26 续跑完成，全部 23 项已对照源码核实）**：听写、翻译、字幕、播放、认证与数据库迁移（会话 ID `01a21350-c621-79b3-b3f4-15951698304a`；另在内存中执行 1→12→24 迁移 SQL 核对全部 21 张业务表结构通过）。

## 执行摘要

| 审查维度 | 核心结论与量化发现 | 建议优先动作 |
| --- | --- | --- |
| 复杂逻辑边界（第 1 组 + 第 2 组完整结果） | 确认共 **41 项**边界缺陷（**11 项 P1 + 29 项 P2 + 1 项 P3**），集中于跨代次/跨来源文件误删与原包冲突改写、HTTP 206 截断、`..` 路径逃逸、听写重试沿用旧指纹、Whisper/Translate 密钥跨端点/跨平台泄漏、B 站续期覆盖登出、字幕发布恢复 CursorWindow 溢出及播放队列冷启动竞态；1→12→24 迁移 SQL 与 21 张业务表结构核对通过 | 先止血修补全部 11 项 P1（删除范围校对、原包冲突身份隔离、HTTP 状态与前缀校验、听写指纹重算、凭证快照绑定、B 站会话代号保护、大发布载荷私有文件化），随后按批次清理 P2/P3 |
| 代码优雅度与运行期隐患 | `YouTubeClient.request` 吞没自身 3 类精确异常；113+ 处裸 `error()`/`check()` 被 `UserFacingError` 机制丢弃；`AudioPcmReader` 忽略 `INFO_OUTPUT_FORMAT_CHANGED`；UI 重组期间存在主线程磁盘 I/O、未 `remember` 的 Room `Flow` 与 `O(N×M)` 分配；`Daos.kt` 有 23 个彻底死查询；`sherpa-onnx` 含 1,292 行上游示例死代码与 R8 混淆隐患 | 修复异常吞没与主线程 I/O；启用 `kotlinx.serialization` 与 `jsoup` 替代手写 JSON 与脆弱 HTML 正则；清理死代码与 110+ 无用导入 |
| 基建复用与重复抽取 | 全工程存在 17+ 独立 `OkHttpClient` 根实例；5 处绕过 `resolveLibraryFileSource` 导致 JA 附件误判；11 处原始 `AlertDialog` 绕过 `ConfirmActionDialog`；3 个前台服务、6 处流式下载、5 处 Redirect 循环、4 处 `Content-Range`、10 处 SHA-256、9 处目录树遍历、设置表单与任务行高度重复 | 在 `AppGraph` 统一根 `OkHttpClient`、抽取 `DataSyncForegroundService` / `HttpSupport` / `WorkIds` / `SettingsComponents` / `ModalSheetHeader` / `SecretTextField` 等高性价比基建 |
| 测试套件质量与缺口 | 含 2 个模板测试、1 个 100% 重复文件、1 个非自动化调试脚本、多处同义反复测试；11 个 Compose UI 仪器测试因 `espresso-core:3.5.1` 在 API 36/37 失灵且其中 4 个已因文案漂移静默失效；51/182 个主文件（24.7% 代码）零测试引用；2 个仪器测试未隔离 `SharedPreferences` 会污染真实 `player_prefs` | 删去冗余测试，升级 Espresso 并用资源 ID 修复文案漂移；补齐 `TestDb`/`TestFixtures`（隔离偏好），优先补 `MIGRATION_1_2~11_12`、`WhisperCenter`、`TranslateCenter`、`BatchSubtitleMatch`、`DlsiteArchiveExtractor` 安全用例 |

---

## 一、复杂逻辑边界问题（`gpt-6.2-astra` 深度审查 + 源码核实）

> 以下条目已对照 [AGENTS.md](../../../AGENTS.md) 第 4–7 节与 [已知未修复问题清单第六节](./2026-11-10-open-issues.md#L61-L67) 过滤已决策搁置项，并逐条对照当前源码核实。

### 1.1 第 1 组完整发现：下载、存储、解压、库事务与并发恢复（18 项：6 P1 + 12 P2）

#### P1（高危边界缺陷，6 项）

1. **按父目录名误删 DLsite 原包中同目录同名 `.json` 附件**
   - 位置：[StoredFiles.kt:413](../../app/src/main/java/com/nanahoshi/audioplayer/data/StoredFiles.kt#L413)（`StoredCatalog.deleteAudio`）· [BiliCache.kt:70-74](../../app/src/main/java/com/nanahoshi/audioplayer/data/BiliCache.kt#L70-L74)（`deleteBiliSubtitleBeside`）
   - 问题：`deleteAudio` 对任意来源的已删音频都会调用 `deleteBiliSubtitleBeside`，而该函数仅检查 `audio.parentFile?.parentFile?.name == "bilibili"`，即直接删除同目录下同名的 `.json` 文件。
   - 触发边界：若 DLsite 原包内部目录恰好包含 `bilibili/disc/voice.wav` 与 `bilibili/disc/voice.json`，在「下载管理」仅删除 `voice.wav` 时会顺手把 `voice.json` 删掉，留下指向已不存在文件的附件记录。
   - 最小修复：仅在明确识别为 B 站缓存音轨（从 `filesDir`、BV 和 CID 构造预期路径并校验完全一致）时删除对应 `.json`。

2. **DLsite 字幕扫描暴露 `original/` 下尚未发布的正在解压代次**
   - 位置：[StoredFiles.kt:230](../../app/src/main/java/com/nanahoshi/audioplayer/data/StoredFiles.kt#L230)（`StoredCatalog.list`）
   - 问题：DLsite 作品目录的字幕递归扫描覆盖整个作品磁盘目录，包括 `original/` 下历史代次以及正在解压但尚未发布的新代次目录。
   - 触发边界：旧代次尚有一条音频被列出，因另一文件缺失重下整包。新代次解压写出 `.srt` 后、提交发布前，用户刷新「下载管理 → 字幕」即可看到并删除该新代次的字幕；随后的扫描只按磁盘剩余文件入库，会把缺少该字幕的原包登记为完成。
   - 最小修复：普通递归扫描跳过 DLsite 作品下的 `original/` 子树；原包字幕仅从已提交的 `dlsite_original_entries` 枚举，并在删除事务内复核该路径仍属于已发布清单。

3. **恢复原包副本时未校验预期根目录，并发移除可使已删作品复活**
   - 位置：[DlsiteOriginalStore.kt:103](../../app/src/main/java/com/nanahoshi/audioplayer/data/DlsiteOriginalStore.kt#L103)（`DlsiteOriginalStore.importDirectory`）
   - 问题：`LibraryRepository.restoreOriginalCopy` 调用 `importDirectory` 时未传入预期根目录 ID；事务提交时若发现 `library.folderBySource(code)` 已为空，会新建一个根目录并重新发布文件。
   - 触发边界：重新入库带磁盘原包的作品时，在 `restoreOriginalCopy` 扫描磁盘或读取音频时长期间，用户从音频库移除该作品。移除事务先提交，随后恢复事务重新创建根目录与曲目，导致已移除作品复活，且 `saveOnlineWork` 仍可能返回旧根 ID。
   - 最小修复：将本次入库目标 `rootId` 传入恢复流程，在发布事务内同时核对作品号、根 ID 与待恢复代次，任一项变化即中止恢复。

4. **路径冲突时把占用者写入清单 `trackId`，二次发布改写无关曲目身份**
   - 位置：[DlsiteOriginalStore.kt:185-210](../../app/src/main/java/com/nanahoshi/audioplayer/data/DlsiteOriginalStore.kt#L185-L210)（`DlsiteOriginalStore.importDirectory`）
   - 问题：首次发生路径占用冲突时，第 208 行把实际占用者 `owner.id` 写入清单条目的 `trackId`。下一次导入同相对路径时，第 185 行的 `previousTrack` 会优先选中这个占用者，并在第 192 行改写其 `title`、`folderId` 和 `localUri`。
   - 触发边界：作品内两条曲目中 `kept.wav` 已占用 `voice.wav` 的目标路径。首次导入按预期跳过；再次发布相同相对路径时，`previousTrack` 选中 `kept.wav` 并将其改名为 `voice.wav`，导致原有歌单与字幕绑定随 `trackId` 错指到被改写的曲目。现有冲突测试仅覆盖首次发布。
   - 最小修复：在清单模型或匹配校验中区分「已匹配曲目」与「外部占用者」；复用旧 `trackId` 前验证其作品归属及 Play 身份/完整相对路径一致，对仅记录了占用关系的条目重新执行冲突判定。

5. **完整 GET 接受 `206 Partial Content` 且按片段长度验收，截断音轨可标为 `DONE`**
   - 位置：[DownloadCenter.kt:1283](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L1283)（`DownloadCenter.transfer`）· [DownloadCenter.kt:1630](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L1630)（`DownloadCenter.downloadBili`）
   - 问题：普通音轨与 B 站音轨在未发 `Range` 头的完整 GET 中均接受任意 `response.isSuccessful`（包含 `206`），且仅将已读字节数与本次响应的 `Content-Length` 比较。
   - 触发边界：上游 CDN 对不带 `Range` 的 GET 返回了只含首段的 `206` 响应且读满该段字节。长度比较通过，截断文件被重命名落盘并标记为 `DONE`，B 站分支后续还会凭文件非空直接复用。
   - 最小修复：完整 GET 仅接受 `200`，或严格解析 `206` 的 `Content-Range` 确认范围为 `0-(total-1)` 并按资源总长验收。

6. **在线相对目录保留独立 `..` 段，可跳出作品根目录写入磁盘**
   - 位置：[DownloadCenter.kt:1591](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L1591)（`DownloadCenter.relativeDirs`）· [EntryNames.kt:61-66](../../app/src/main/java/com/nanahoshi/audioplayer/data/EntryNames.kt#L61-L66)（`sanitizeEntryName`）
   - 问题：`sanitizeEntryName` 替换了 `/`、`\` 等字符，但保留独立的 `.` 与 `..` 名称；`relativeDirs` 直接将其拼为路径片段，且 `destination` 未校验规范化后的目标路径仍在作品根目录下。
   - 触发边界：在线文件树包含名为 `..` 的目录时，其下下载的音轨会被写到作品目录之外；数据库仍登记下载成功，按作品目录扫描与清理时会漏掉该文件。
   - 最小修复：在 `sanitizeEntryName` 或 `relativeDirs` 中替换独立 `.`、`..` 段，并在创建目录与写 `.part` 前校验规范化路径位于预期作品根之下。

#### P2（中危边界缺陷，12 项）

7. **取消与重试并发时旧取消协程命中新 lazy runner，任务滞留 `RUNNING`**
   - 位置：[DownloadCenter.kt:553-559, 772, 1226-1235](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L553-L559)（`cancel`, `downloadOne`）
   - 问题：`cancel` 先改数据库为 `FAILED`，随后在 `progress.finishFromDb` 处挂起，最后按数据库 ID 查找 `runners[id]?.cancel()`；第 772 行的状态观察器同样按 ID 取消。若挂起期间用户触发重试，新尝试已注册为 `RUNNING` 的 lazy runner，旧取消操作恢复后会取消这次新 runner；若尚未进入 `transfer`，`downloadOne` 捕获取消退出，数据库行滞留 `RUNNING` 直至重启。
   - 最小修复：为每次执行分配单次尝试令牌，使 `runners` 注册、取消与未启动清理都只作用于对应尝试。

8. **YouTube 暂存路径只含任务 ID，旧取消回调可删除重试新建的 `.part`**
   - 位置：[DownloadCenter.kt:1106](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L1106) · [YouTubeClient.kt:282, 356](../../app/src/main/java/com/nanahoshi/audioplayer/youtube/YouTubeClient.kt#L282)
   - 问题：第 1106 行以 `destination.path + ".${job.id}.part"` 命名暂存，但重试沿用同一个 `job.id`。取消正在读取响应的任务后，旧 OkHttp 回调在重试已打开同名 `.part` 后执行 `partial.delete()`，导致新重试在校验或重命名阶段失败。
   - 最小修复：在每次调用 `downloadYouTube` 时生成唯一后缀（如递增序号或 UUID），让旧回调只持有当次临时路径。

9. **JA 整包下载无大小且无校验值时，成功下载会在 `finally` 被立即删除**
   - 位置：[JaWorkClient.kt:510](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JaWorkClient.kt#L510)（`JaWorkClient.download`）
   - 问题：当托管文件既无 `file.size` 又无 SHA-256 与强 ETag 时，`finally` 中的条件 `reuseToken == null && file.size != destination.length()`（`null != length`）恒为 `true`，连同第 493 行 `verify` 已通过的合法文件也会被删除，随后计算摘要或解压必然失败。
   - 最小修复：记录本次传输是否已通过验证，仅在未通过且不可续传时清理 `destination`。

10. **DLsite 分卷元数据无长度且 `.part` 恰已在 EOF 崩溃时，`416` 导致重试死循环**
    - 位置：[DlsiteArchiveClient.kt:265-266](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteArchiveClient.kt#L265-L266)（`Access.downloadPart`）
    - 问题：若前置请求未拿到 `part.size` 且进程恰好在写入最后一个字节后、改名成 `target` 前崩溃，重试时按 `offset == EOF` 发起 `Range: bytes=EOF-`，服务器返回 `416` 后被当作普通错误抛出并保留 `.part`，后续每次重试均卡在同一偏移。
    - 最小修复：解析 `416` 响应的 `Content-Range: bytes */N`，若 `N == offset` 则直接进入完整文件重命名与校验，否则清空该卷 `.part` 重试一次。

11. **DLsite 原包复用仅校验 `isFile`，损坏或截断副本被直接复用**
    - 位置：[DownloadCenter.kt:525, 571-573](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L525) · [LibraryRepository.kt:776](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt#L776)
    - 问题：原包清单已持久化每个文件的 `size`，但 `enqueueDlsiteArchive`、`downloadArchive` 与 `restoreOriginalCopy` 均只校验 `File.isFile`。某音轨文件被截断后仍存在磁盘时，三个入口都会将其视为完整原包跳过下载，甚至把截断大小写回清单。
    - 最小修复：抽出一处统一的清单完整性检查函数，对文件同时校验 `isFile` 与记录的 `size`。

12. **`hasStoredCopy` 不检查文件长度与 `content:` 可读性，失效关联阻止重新下载**
    - 位置：[LocalAudio.kt:76-80](../../app/src/main/java/com/nanahoshi/audioplayer/data/LocalAudio.kt#L76-L80) · [DownloadCenter.kt:1248](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L1248)
    - 问题：`LocalAudio.hasStoredCopy` 对所有 `content:`/`file:` 一律返回 `true`，对绝对路径也仅查 `isFile` 不查 `length() > 0L`。外部授权失效、文件被删或零字节损坏文件会导致 `enqueueTrack` 与 `transfer` 直接判定已有副本而跳过下载。
    - 最小修复：对齐 `contentUriReadable` 与 `length() > 0L` 检查（或拆出接收 `Context` 的可读校验函数供入队与传输共用）。

13. **`workHasSource` 未传入 `jaFileIds`，将 JA 无前缀附件误判为 `asmr.one` 来源**
    - 位置：[LibraryRepository.kt:1766](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt#L1766)（同型绕过还见于 [LibraryRepository.kt:324](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt#L324)、[LibraryRepository.kt:1781](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt#L1781) 与 [DlsiteOriginalStore.kt:126, 224](../../app/src/main/java/com/nanahoshi/audioplayer/data/DlsiteOriginalStore.kt#L126)）
    - 问题：JA 整部作品附件的 `fileHash` 为无前缀 SHA-256，而 `libraryFileSource` 将无前缀哈希默认归类为 `TrackSource.ASMR`。当作品已有 JA 下载的文本/图片附件而尚无 `asmr.one` 曲目时，`workHasSource(..., ASMR)` 返回 `true`，导致切换到 `asmr.one` 时被 `fillWorkSource` / `saveOnlineWork` 误判为已导入而跳过，页面无曲目。
    - 最小修复：统一使用 `resolveLibraryFileSource(file, jaFileIds)`（[EntryNames.kt:29](../../app/src/main/java/com/nanahoshi/audioplayer/data/EntryNames.kt#L29)）。

14. **JA HLS 分片仅校验同步字节且最终时长校验失败时不清理缓存，损坏分片永久复用**
    - 位置：[JapaneseAsmrHlsDownload.kt:73-94, 154-172](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JapaneseAsmrHlsDownload.kt#L73-L94)
    - 问题：`validTs()` 仅检查长度为 188 字节整数倍及每包首字节为 `0x47`。当无 `Content-Length` 的分片在 TS 包边界被提前截断时，它能通过 `validTs()`，随后在第 87 行触发成品时长不足报错；但分片保留在缓存中，只要清单未变，此后每次重试都会永久复用该损坏分片。
    - 最小修复：成品封装或时长校验失败时清理该清单暂存（网络中断等前序 `IOException` 仍保留已完成分片）。

15. **JA 直链下载仅前置探测检查非 HTML，正式 GET 可能将 HTML 错误页发布为音频**
    - 位置：[DownloadCenter.kt:1454-1500](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L1454-L1500)（`downloadJaDirect`）
    - 问题：第 1460 行的 `subtype != "html"` 检查只用于 `Range` 探测请求；正式 GET 仅检查 `code == 200`（未复核响应类型或正文魔数），第 1406–1415 行解析时长失败也只静默回退 `0L`。若正式 GET 返回未命中 Cloudflare 特征的普通 HTML 错误页，会被发布为音频并标为 `DONE`。
    - 最小修复：对正式响应复核 `Content-Type` 与首块正文前缀，拒绝 HTML，并在发布前要求有效媒体容器/时长确认。

16. **大作品触发 Room `IN (:ids)` 999 绑定参数上限崩溃**
    - 位置：[LibraryRepository.kt:583](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt#L583)（`orderedSelection`）、[LibraryRepository.kt:1486](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt#L1486)（`adoptLocalWork`）、[DlsiteOriginalStore.kt:124](../../app/src/main/java/com/nanahoshi/audioplayer/data/DlsiteOriginalStore.kt#L124)（`importDirectory`）
    - 问题：三处均将未分片的列表直接传入单条 SQL `IN` 查询。当选中曲目或作品子目录数达到 1,000 时，在 SQLite 参数上限为 999 的设备上会直接抛出异常。
    - 最小修复：按项目内惯例使用 `chunked(500)`/`chunked(900)` 分批查询并在内存中合并结果。

17. **JA 合法长文件名拼接 `.version` 后超出 255 字节文件系统段限制**
    - 位置：[JaWorkClient.kt:468](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JaWorkClient.kt#L468)
    - 问题：`normalizeJaWorkPath` 允许单个文件名为 248 字节 ASCII，但第 468 行直接在其旁拼接 8 字节后缀 `".version"` 生成 256 字节文件名，写入版本标记即在 255 字节上限的文件系统上失败。
    - 最小修复：在每个独立哈希暂存目录下使用固定短文件名（如 `.version`），并与原文件名隔离。

18. **本地目录保留结构导入缺少整树事务，中断重试产生错乱目录与虚假计数**
    - 位置：[LibraryRepository.kt:1558-1593](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt#L1558-L1593)（`importTree`, `importNode`）
    - 问题：`importNode` 先插入 `FolderEntity` 再逐个读取元数据并插入 `TrackEntity`（无外层事务）。中途退出后重试会新建一批目录，且 `upsertLocal` 命中已有 `localUri` 时直接返回原曲目，调用方仍将其计入本次导入总数；重复导入完整目录还会留下空文件夹。
    - 最小修复：先在事务外读取文件树与音频元数据，再在单一 `db.withTransaction` 中仅对新增曲目所在的子树创建目录，并按实际新增条数计数。

> **第 1 组已确认无该类边界缺陷的区域**：ZIP 条目路径逃逸与符号链接放行（[DlsiteArchiveExtractor.kt](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteArchiveExtractor.kt)）、DLsite 原包跨白名单重定向、MEGA CBC-MAC 在 128 KiB 递增至 1 MiB 分块边界处的偏移计算（[JaMega.kt:286-317](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JaMega.kt#L286-L317)）、以及 `TaskPump` 执行槽占用至清理结束的生命周期（[TaskPump.kt:13-54](../../app/src/main/java/com/nanahoshi/audioplayer/data/TaskPump.kt#L13-L54)）。

---

### 1.2 第 2 组完整发现：听写、翻译、字幕、播放、认证与数据库迁移（23 项：5 P1 + 17 P2 + 1 P3）

#### P1（高危边界缺陷，5 项）

1. **`WhisperCenter.retryLocked` 复用本地路径时不重算指纹，污染同指纹缓存**
   - 位置：[WhisperCenter.kt:534](../../app/src/main/java/com/nanahoshi/audioplayer/data/WhisperCenter.kt#L534)（`WhisperCenter.retryLocked`）
   - 问题：重试仍存在的 `localPath` 时直接把保存的 `job.fingerprint` 传给 `requeueWithFile`，未重新计算当前文件字节的摘要（相邻第 542 行的 `restoreLocalCopy` 分支会重算 `sha256`）。
   - 触发边界：音频 A 的任务失败后，重新下载或本地替换将音频 B 写入同一路径并点重试，会把 A 的字幕误复用到 B，或将 B 的转录结果发布在 A 的指纹下，进而污染其他复用 A 指纹的曲目。
   - 最小修复：对当前重试输入重新计算 `sha256`（对可变路径先快照再哈希转录），统一将新指纹用于查重、入队与发布。

2. **`WhisperClient.execute` 未校验端点与密钥归属，跨地址发送当前 Bearer Key**
   - 位置：[WhisperClient.kt:80](../../app/src/main/java/com/nanahoshi/audioplayer/data/WhisperClient.kt#L80)（`WhisperClient.execute`）
   - 问题：请求 URL 取自队列任务保存的 `endpoint`，而 Bearer Token 却从模型当前设置读取，且查找密钥时不核对规范化端点。
   - 触发边界：向自建 Qwen3-ASR 服务器 A 排队任务后，将模型设置改到服务器 B 和密钥 B，再续跑旧任务时，发往服务器 A 的请求会携带服务器 B 的密钥。
   - 最小修复：按「模型 + 规范化端点」绑定解析凭证；若排队任务的端点已不匹配当前配置，在发起请求前明确失败。

3. **`TranslateClient.complete` 各字段独立读取当前平台，切平台时 Token 跨平台泄漏**
   - 位置：[TranslateClient.kt:444-468](../../app/src/main/java/com/nanahoshi/audioplayer/data/TranslateClient.kt#L444-L468)（`TranslateClient.complete`）
   - 问题：`translateModel`、`translateToken`、`translateBaseUrl`、`translatePlatform` 与 `translateEffort` 在方法内各自独立读取当前选中平台。
   - 触发边界：在第 445 行读取 Token 与第 446 行读取 URL 之间切换翻译平台，会把平台 1 的 Bearer Token 发给平台 2；在长字幕批次之间切换也会导致同一翻译任务中途换用不同平台。
   - 最小修复：在翻译任务开始时针对同一显式平台捕获不可变配置快照，并贯穿该任务的所有批次请求。

4. **`BilibiliClient.refreshSession` 与退出登录/切换账号并发时覆盖当前会话**
   - 位置：[BilibiliClient.kt:615](../../app/src/main/java/com/nanahoshi/audioplayer/bilibili/BilibiliClient.kt#L615)（`BilibiliClient.refreshSession`）· [CredentialStore.kt:66-113](../../app/src/main/java/com/nanahoshi/audioplayer/data/CredentialStore.kt#L66-L113)
   - 问题：在途续期响应与当前存储 Cookie 合并后无条件落盘，互斥锁未串行化退出登录或账号替换。
   - 触发边界：账号 A 发起续期期间用户退出登录或切为账号 B，旧续期响应返回后第 615 行可恢复已登出会话或覆写账号 B 的凭证，失败分支也会把冷却标记写脏到新会话。
   - 最小修复：续期前快照当前会话及 `biliSession` 代号，基于快照合并且仅当提交时代号仍为当前时才保存凭证与冷却状态（退出登录与覆盖凭证时推进代号）。

5. **`TranslationDao.pendingPublications` 一次拉取全部字幕 JSON 载荷，单条大字幕触发 CursorWindow 溢出卡死恢复**
   - 位置：[Daos.kt:720](../../app/src/main/java/com/nanahoshi/audioplayer/data/db/Daos.kt#L720)（`TranslationDao.pendingPublications`）· [SubtitleSource.kt:545](../../app/src/main/java/com/nanahoshi/audioplayer/playback/SubtitleSource.kt#L545)
   - 问题：启动恢复将 `subtitle_publications` 全表含完整 `payload` 字段一次查询进 Room 结果集，再进入逐条恢复。
   - 触发边界：长双语字幕生成的发布暂存行超过设备 SQLite CursorWindow 上限后，若进程在落盘前崩溃，下次启动读取结果集即直接失败，连带使表中其余待恢复发布全部滞留。
   - 最小修复：先仅枚举发布记录 ID 与元数据（不含 `payload`），大载荷改存应用私有暂存文件（已有 SQLite 旧行按 `substr` 分块读取），并在逐条恢复内分别捕获读取与发布异常。

#### P2（中危边界缺陷，17 项）

6. **`WhisperCenter.stageContent` 未串行化且写向最终文件名，并发提交读到半成品**
   - 位置：[WhisperCenter.kt:1181, 1194](../../app/src/main/java/com/nanahoshi/audioplayer/data/WhisperCenter.kt#L1181)（`WhisperCenter.stageContent`）
   - 问题：第 1178 行只要共享暂存文件长度大于 0 即视作就绪，而首个写入方在第 1194 行直接写入同一最终路径。
   - 触发边界：对同一 `content:` URI 并发两次提交且首个拷贝尚在进行时，第二个调用方可能对半成品前缀算指纹并用最终成品转录，或直接返回 `null`。
   - 最小修复：按 URI 串行化暂存，先写私有临时文件并关闭，再原子重命名到共享目标，并发调用方等待同一结果。

7. **`WhisperCenter.finishTranscription` 用入队时间 `job.createdAt` 而非用户请求时间比较手动绑定**
   - 位置：[WhisperCenter.kt:1112](../../app/src/main/java/com/nanahoshi/audioplayer/data/WhisperCenter.kt#L1112)（`WhisperCenter.finishTranscription`）
   - 问题：生成字幕覆盖手动绑定时对比的是入队时刻的 `job.createdAt`，而非播放页发起听写请求时的 `requestedAt`。
   - 触发边界：用户在音频拷贝或计算指纹期间手动改选其他字幕，随后入队的 `job.createdAt` 晚于该手动操作，听写完成时反将新手动选择覆盖掉。
   - 最小修复：将发起请求时的 `requestedAt` 透传到任务并在初次入队时用作 `bindGeneratedIfOlder` 的判断基线，与队列/重试排序时间戳解耦。

8. **`cuesFromQwenPayload` 漏检 `transcripts[].text`，有识别文本无时间轴时误发布为空语音**
   - 位置：[QwenCues.kt:26](../../app/src/main/java/com/nanahoshi/audioplayer/data/QwenCues.kt#L26)（`cuesFromQwenPayload`）
   - 问题：同一解析器已支持 `transcripts[]` 响应结构，但第 26 行的无时间轴校验只检查 `output.text`。
   - 触发边界：接口返回 `transcripts: [{"text":"spoken words","sentences":[]}]` 时产出零条时间轴字幕，被误当作成功的「没有听出人声」缓存并复用。
   - 最小修复：零有效句时检查所有已支持的文本结构，只要存在非空识别文本即抛出 `whisper_error_no_timing`。

9. **`AudioPcmReader.pump` 忽略 `INFO_OUTPUT_FORMAT_CHANGED` 且硬编码 16-bit PCM**
   - 位置：[AudioPcmReader.kt:151, 175](../../app/src/main/java/com/nanahoshi/audioplayer/data/AudioPcmReader.kt#L151)（`AudioPcmReader.pump`）
   - 问题：每个解码缓冲区都按 16-bit 小端整数读取，且循环显式忽略 `MediaCodec.INFO_OUTPUT_FORMAT_CHANGED`，继续沿用输入轨的采样率与声道数。
   - 触发边界：解码器实际输出 Float PCM 或变更采样率/声道数（如 HE-AAC/SBR）时，每个 4 字节浮点样本被错拆为两个无关 `Short`，导致样本损坏与时长漂移。
   - 最小修复：在格式变更时读取输出 `MediaFormat` 的 PCM 编码、采样率与声道数并按实际格式解码，不支持的编码直接报错退出。

10. **`SubtitleSource.documentSiblings` 构造同级查询 URI 时丢失 SAF 树权限**
    - 位置：[SubtitleSource.kt:935, 953](../../app/src/main/java/com/nanahoshi/audioplayer/playback/SubtitleSource.kt#L935)（`SubtitleSource.documentSiblings`）
    - 问题：从带树授权的音频 URI 提取父文档 ID 后，构造了不带树权限的普通 `buildChildDocumentsUri` 与 `buildDocumentUri`。
    - 触发边界：通过系统目录树选择器导入文件夹（仅授予树读权限）时，同目录下的 `.lrc` 在权限上完全可读，但非树作用域查询或打开被权限拒绝，自动匹配静默漏掉字幕。
    - 最小修复：保留目录树 URI，改用 `buildChildDocumentsUriUsingTree` 与 `buildDocumentUriUsingTree`，并对不透明 `documentId` 的提供方在遍历时保留父文档 ID。

11. **`SubtitleSource.boundCues` 借用 512 KiB 预览读取并丢弃截断标记，长字幕被静默截断**
    - 位置：[SubtitleSource.kt:198, 200, 964](../../app/src/main/java/com/nanahoshi/audioplayer/playback/SubtitleSource.kt#L198)（`SubtitleSource.boundCues`, `readLimited`）
    - 问题：手动绑定的外部 URI、应用副本、库内及同级字幕均调用 `readLocalTextPreview`/`readRemoteTextPreview` 并只取 `.text`，丢弃 `truncated` 标志。
    - 触发边界：超过 512 KiB 上限的合法字幕仅加载前缀，后半段全部消失；基于此执行翻译时还会把截断前缀当作完整原文。
    - 最小修复：播放与翻译路径改用完整或流式字幕读取；若确需保留上限，超限时返回显式加载失败。

12. **`SubtitleBindingStore.cleanupOrphans` 仅清理拷贝文件，不回收崩溃泄漏的 SAF 持久读权限**
    - 位置：[SubtitleBindingStore.kt:174](../../app/src/main/java/com/nanahoshi/audioplayer/data/SubtitleBindingStore.kt#L174)（`SubtitleBindingStore.cleanupOrphans`）
    - 问题：申请/释放外部字幕持久权限与数据库绑定提交拆在两步执行，没有崩溃恢复对账机制。
    - 触发边界：在 `takePersistableUriPermission` 之后提交前崩溃，或解除最后绑定之后释放权限前崩溃，都会永久遗留无引用的持久权限并逐渐耗尽系统授权配额。
    - 最小修复：记录字幕专属的持久权限变更日志并在启动时对账回收（释放前校验所有字幕绑定与音频库引用）。

13. **`SubtitleOffsetWriter.reconcile` 仅在 `persist` 返回后登记 `writtenTargets`，Room 提前回弹丢弃新偏移**
    - 位置：[SubtitleOffsetWriter.kt:144, 218](../../app/src/main/java/com/nanahoshi/audioplayer/ui/components/SubtitleOffsetWriter.kt#L144)（`SubtitleOffsetWriter.reconcile`, `runWriter`）
    - 问题：已写入目标值直到第 218 行 `persist` 返回后才加入 `writtenTargets`，而 Room 失效通知可在 `persist` 返回前提前到达。
    - 触发边界：从 `0` 先点 `+500 ms`、落盘途中再点 `+1000 ms`，若 Room 在 `persist(+500)` 返回前先推送 `+500`，`reconcile` 会将其误判为外部重置并丢弃待写的 `+1000`，最终停留在 `+500`。
    - 最小修复：发起持久化前即登记在途 `(target, version)` 标记，回弹时据此识别自身写入并保留更新的待写值，失败时清除在途标记。

14. **`PlaybackService.onCreate` 冷启动队列恢复见 `mediaItemCount > 0` 即放弃，增量追加导致队列撕裂**
    - 位置：[PlaybackService.kt:188](../../app/src/main/java/com/nanahoshi/audioplayer/playback/PlaybackService.kt#L188)（`PlaybackService.onCreate`）
    - 问题：异步恢复获取锁后只要播放器 `mediaItemCount > 0` 就直接退出，未区分「整队列替换」与「增量追加/下一首插入」。
    - 触发边界：冷启动时数据库已有 A、B，恢复解析媒体期间用户触发增量追加 C，导致数据库为 `[A, B, C]` 而播放器仅含 `[C]`，播放导航与队列索引永久撕裂。
    - 最小修复：增量队列操作等待冷启动恢复完成，或在队列锁内按完整有序数据库 ID 对账播放器列表，仅全队列替换才覆盖恢复。

15. **`PlaybackService.onCreate` 持久队列项 ID 失效回退首项时错沿用旧项进度**
    - 位置：[PlaybackService.kt:202](../../app/src/main/java/com/nanahoshi/audioplayer/playback/PlaybackService.kt#L202)（`PlaybackService.onCreate`）
    - 问题：当持久化记录的 `queueItemId` 在当前队列中已找不到时，`index` 回退到 `0`，但第 202 行仍把旧项的 `state?.positionMs` 喂给 `setMediaItems`。
    - 触发边界：移除或替换当前播放曲目后在下次保存进度前退出进程，重启后新的第一首曲目会从被删曲目的播放位置开始。
    - 最小修复：仅当 `queueItemId` 精确匹配时才恢复 `positionMs`，索引回退首项时固定传 `0L`。

16. **`PlaybackService.onPlayerError` 流错误自动重试无条件置 `playWhenReady = true`**
    - 位置：[PlaybackService.kt:153](../../app/src/main/java/com/nanahoshi/audioplayer/playback/PlaybackService.kt#L153)（`PlaybackService.onPlayerError`）
    - 问题：针对可重试的在线流错误，自动刷新重试后第 153 行无条件设置 `exoPlayer.playWhenReady = true`。
    - 触发边界：在暂停状态下准备在线项目或缓冲途中暂停，此时触发可重试流失败会擅自开始出声播放。
    - 最小修复：移除无条件 `playWhenReady = true`，在 `seekTo` 与 `prepare` 后保持用户原有播放意图。

17. **`MediaItemFactory` 把已下载的 B 站曲目写死为本地 URI，删副本后现有队列无法回退在线**
    - 位置：[MediaItemFactory.kt:87](../../app/src/main/java/com/nanahoshi/audioplayer/playback/MediaItemFactory.kt#L87)（`TrackEntity.toPlayableItem`）
    - 问题：已下载的 B 站曲目在组装 `MediaItem` 时直接把 `uri` 固定为本地缓存 URI（对比第 84 行 YouTube 始终保留虚拟身份 URI），之后丢失 BV/CID 回退路径。
    - 触发边界：将已下载的 B 站分 P 加入队列，通过「下载管理」删除其音轨副本，再从现有队列点播该项时仍尝试打开已删除的本地文件并播失败。
    - 最小修复：对 B 站曲目同样保留 `bilibili://audio?bvid=...&cid=...` 作为播放身份，在数据源打开时动态择优读本地或 DASH（或本地失败时按 BV/CID 重建播放项并保留进度）。

18. **`BiliPlaybackDataSource.openStored` 吞掉本地打开异常前未调用 `fallback.close()`**
    - 位置：[BiliPlaybackDataSource.kt:282](../../app/src/main/java/com/nanahoshi/audioplayer/playback/BiliPlaybackDataSource.kt#L282)（`BiliPlaybackDataSource.openStored`）
    - 问题：本地 `content:`/`file:` 打开失败后直接吞掉异常转远端源，未关闭 `fallback`（`DefaultDataSource` 仅在 `close()` 时重置内部选中的源）。
    - 触发边界：已存 URI 短暂不可读时转远端播放成功，随后复用同一数据源实例再次重开，可能因 `fallback` 状态未复位而在权限恢复后仍失败。
    - 最小修复：每次 `fallback.open` 失败后先安全调用 `fallback.close()`（保留原始异常），再切换远端或向上抛出。

19. **`BrowserHttpDataSource.open` 把恰好位于 EOF 的 `HTTP 416` 当作播放错误**
    - 位置：[BiliPlaybackDataSource.kt:509](../../app/src/main/java/com/nanahoshi/audioplayer/playback/BiliPlaybackDataSource.kt#L509)（`BrowserHttpDataSource.open`）
    - 问题：第 509 行将所有 `HTTP 416` 一律按异常抛出，而 Media3 约定恰好从资源总长 `N` 处打开时应正常返回并由下一次 `read` 返回 `C.RESULT_END_OF_INPUT`。
    - 触发边界：对长度为 N 的远端音频 Seek 到精确末尾 `N`，服务端返回 `416`（`Content-Range: bytes */N`），正常的 EOF 被报成播放失败。
    - 最小修复：解析 `Content-Range` 的总长 `N`，当 `N == dataSpec.position` 时返回空流 EOF，位置超出 `N` 时按越界错误上报。

20. **`PlayerClient.init` 主线程监听未捕获 `future.get()` 异常，`ready` 永久挂起**
    - 位置：[PlayerClient.kt:127](../../app/src/main/java/com/nanahoshi/audioplayer/playback/PlayerClient.kt#L127)（`PlayerClient.init`）
    - 问题：主线程回调内直接调用 `future.get()` 未捕获失败或取消异常，`ready: CompletableDeferred<MediaController>` 永远不被完成且异常从主回调逃逸。
    - 触发边界：会话服务拒绝连接或初始化取消时，主线程抛出未捕获异常，等待控制器就绪的所有调用永久挂起。
    - 最小修复：在监听内捕获异常并以 `ready.completeExceptionally(error)` 结束等待，同时接入既有播放错误反馈通道。

21. **`BilibiliClient.maybeRefreshSession` 把非 `-101` 接口错误（如 `-412` + `data=null`）当作检查成功**
    - 位置：[BilibiliClient.kt:569](../../app/src/main/java/com/nanahoshi/audioplayer/bilibili/BilibiliClient.kt#L569)（`BilibiliClient.maybeRefreshSession`）
    - 问题：`needsRefresh` 为假（非 `-101` 且 `refresh != true`）时直接走到 `markBiliRefreshChecked()`。
    - 触发边界：HTTP 200 返回业务风控/错误体 `code = -412, data = null` 时，应用将其当作「检查完成无需刷新」，错误清除失效提示并把下次检查推迟约 20 小时，而非进入 10 分钟失败冷却。
    - 最小修复：仅当 `info.code == 0 && info.data?.refresh == false` 时判定无需刷新，保留 `-101` 刷新分支，其余接口错误或缺字段一律记为失败冷却。

22. **`BilibiliClient.refreshSession` 先落盘新 Token，旧 Token 作废确认失败或崩溃后永久失去重试机会**
    - 位置：[BilibiliClient.kt:617, 630](../../app/src/main/java/com/nanahoshi/audioplayer/bilibili/BilibiliClient.kt#L617)（`BilibiliClient.refreshSession`）
    - 问题：第 615 行保存新凭证后即覆盖了旧 `refresh_token`，若第 617 行新 `bili_jct` 缺失或第 630 行作废接口返回非 0 或中途崩溃，方法仍返回成功且本地不再留有旧 Token 供后续重发作废。
    - 最小修复：将新会话与加密存储的「待作废旧 Token」原子落盘，在同一会话下重试作废确认，确认成功后再清除待作废记录。

#### P3（低危边界缺陷，1 项）

23. **`parseSubtitleDocument` 见任意 `"-->"` 即跳过 LRC 解析**
    - 位置：[SubtitleCues.kt:246](../../app/src/main/java/com/nanahoshi/audioplayer/playback/SubtitleCues.kt#L246)（`parseSubtitleDocument`）
    - 问题：只要文档任意位置包含 `"-->"` 就立即进入 `parseTimedBlocks` 并直接返回，不再尝试 LRC 解析。
    - 触发边界：合法的 `.lrc` 文件中只要有一行歌词文本包含 `"-->"`（如 `[00:01.00]A --> B`），整份 LRC 都会被判成 0 条字幕。
    - 最小修复：按真实时间范围行正则检测分段格式，或当 `parseTimedBlocks` 返回空列表时回退 `parseLrc`。

> **第 2 组数据库迁移核对与其余安全确认区域**：
> - **数据库 1→24 迁移结构一致**：在内存中执行 [AppDatabase.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/db/AppDatabase.kt) 全部迁移 SQL，由按迁移反推重建的 v1 基线升级至冻结 `schema-v12.sql`，再由 v12 升级至编译生成的全新 v24（`AppDatabase_Impl.kt`），**全部 21 张业务表**的列类型、可空性、默认值、主键、索引列与唯一性以及外键级联动作完全吻合（因仓库无冻结 v1 基线资产，v1 表结构系由迁移 SQL 逆向重建）。
> - **其余确认无新增边界缺陷的区域**：sherpa-onnx JNI 解码与 `release` 在协程取消下的并发同步顺序、前台服务销毁时 WakeLock 释放、批量字幕匹配的歧义重名与重目标冲突拒绝、库内批量字幕绑定的事务原子性，以及 Parakeet 语音分段与重叠拼接逻辑。

---

## 二、代码优雅度、冗余代码与高维护成本算法

### 2.1 活跃的工程缺陷与性能反模式

1. **`YouTubeClient.request` 吞没自身刚抛出的精确异常（高危）**
   - [YouTubeClient.kt:380-396](../../app/src/main/java/com/nanahoshi/audioplayer/youtube/YouTubeClient.kt#L380-L396)：`try` 块内刚抛出 `youtube_error_login_expired`（401）、`youtube_error_request_failed` 与 `youtube_error_no_content` 三种 `YouTubeException`，紧接着第 389 行的 `catch (_: Exception)` 却把所有异常全部捕获并改写为泛化的 `R.string.youtube_error_request_check`。那 3 个错误文案资源在主代码中无任何其他引用。

2. **裸 `error()` / `check()` 系统性吞没本地化错误文案（高危）**
   - `Throwable.userTextOr(fallback)`（[UserFacingError.kt:11-12](../../app/src/main/java/com/nanahoshi/audioplayer/text/UserFacingError.kt#L11-L12)）只读取实现 `UserFacingError` 接口的异常，普通 `IllegalStateException` 会被直接丢弃并替换为兜底文案。
   - 主代码中（仅 `data/`、`playback/` 与核心 UI）存在 **113+ 处裸 `error()`/`check()`**，导致精细提示在生产环境失效：
     - [DownloadCenter.kt:1281, 1304, 1325, 1361](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L1281) 与 `retry`（`:1135-1144`）：在 `transfer()` 和 `DownloadScreen` 兜底捕获后全部退化为通用失败文案。
     - [LibraryRepository.kt:475, 589-636, 720-726, 946-1055](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt#L589)：`recognizeLocalFolder` 的细粒度失败原因和重复入库提示在 `LibraryViewModel` 处全部退化为 `"操作失败"`。
     - [TranslateCenter.kt:269](../../app/src/main/java/com/nanahoshi/audioplayer/data/TranslateCenter.kt#L269)（`translate_source_missing` 被兜底吞没）。
     - [OnlineScreens.kt:1127](../../app/src/main/java/com/nanahoshi/audioplayer/ui/asmr/OnlineScreens.kt#L1127)、[LibraryViewModel.kt:576, 915](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/LibraryViewModel.kt#L576)、[BiliCollectionViewModel.kt:292-300](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliCollectionViewModel.kt#L292-L300)（`bilibili_no_playable_parts` 被 `bilibili_read_failed_short` 吞没）。
     - [BiliLibraryStore.kt:22-24, 47-48](../../app/src/main/java/com/nanahoshi/audioplayer/data/BiliLibraryStore.kt#L22-L24)（`check` 内硬编码中文）。

3. **`AudioPcmReader` 忽略解码输出格式变化且硬编码 16-bit PCM（高危）**
   - [AudioPcmReader.kt:151-176](../../app/src/main/java/com/nanahoshi/audioplayer/data/AudioPcmReader.kt#L151-L176)：第 175 行显式跳过 `MediaCodec.INFO_OUTPUT_FORMAT_CHANGED`（注解“INFO_OUTPUT_FORMAT_CHANGED 等负值继续循环”）。对于 HE-AAC/SBR 或容器头信息与实际解码输出采样率/声道数不一致的音频，会导致 2× 时间戳漂移；第 151 行硬编码 16-bit `buffer.short` 未读取 `KEY_PCM_ENCODING`；第 32–50、183 行无抗混叠低通滤波的线性降采样每帧还触发数组拼接拷贝。

4. **UI 重组期反模式、主线程磁盘 I/O 与二次方分配**
   - **每次重组重建 Room `Flow`**：[PlaylistScreens.kt:114, 125](../../app/src/main/java/com/nanahoshi/audioplayer/ui/playlist/PlaylistScreens.kt#L114) 直接调用 `viewModel.playlist(playlistId)` / `playlistTracks(playlistId)`（每次返回新 `Flow` 实例，缺少 `remember(playlistId)`），且 `PlaylistSummaryRow`（`:228-229`）在行内反复收集 `collectionSources` 并线性扫描。
   - **主线程 / 重组期同步文件检查**：[LibraryRows.kt:534-540](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/LibraryRows.kt#L534-L540)（`TrackRow`，对比 `FileRow:618` 已切 IO）、[BiliBrowser.kt:205-210, 338](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliBrowser.kt#L205-L210)、[YouTubeScreens.kt:160-161, 238-240](../../app/src/main/java/com/nanahoshi/audioplayer/ui/youtube/YouTubeScreens.kt#L160-L161)、[StoredFilesScreen.kt:159-161](../../app/src/main/java/com/nanahoshi/audioplayer/ui/download/StoredFilesScreen.kt#L159-L161)（在 `Main` 上调用 `canonicalFile`）。
   - **重组与过滤 `O(N×M)` 分配 / 全表加载**：
     - [LibraryViewModel.kt:1402-1403](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/LibraryViewModel.kt#L1402-L1403)：在 `tracks.filter` 内部对每首 B 站曲目重复 `folders.associateBy { folder.id }`。
     - [LibraryViewModel.kt:1432, 1443-1446](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/LibraryViewModel.kt#L1432)：在 `mixedEntries` 内逐轨全表过滤 `subtitlePool`。
     - [OnlineScreens.kt:1155](../../app/src/main/java/com/nanahoshi/audioplayer/ui/asmr/OnlineScreens.kt#L1155)：为过滤单部作品读取全量 `tracks` 表；`:2366-2367` 在 `items(urls)` 中反复 `urls.indexOf(url)`。
     - [DownloadScreen.kt:251, 411, 457](../../app/src/main/java/com/nanahoshi/audioplayer/ui/download/DownloadScreen.kt#L411) + [TaskProgressContent.kt:35](../../app/src/main/java/com/nanahoshi/audioplayer/ui/download/TaskProgressContent.kt#L35)：每次进度刷新单个 `DownloadRow` 最多反序列化 `decodeProgressSummary` 4 次且在根部引发整页重组。
     - [StoredFilesScreen.kt:561-594](../../app/src/main/java/com/nanahoshi/audioplayer/ui/download/StoredFilesScreen.kt#L561-L594)：`SubtitlePreviewDialog` 使用 `Column + verticalScroll` 一次展开全部字幕行，长音频易掉帧。
   - **其它逻辑与结构瑕疵**：
     - [DlsiteClient.kt:166-229, 562-575](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteClient.kt#L166-L229)：大量商店接口使用阻塞不可取消的 `client.newCall(request).execute()`；`productCards()` 把可批量的 `loadProductCards` 拆成单 ID 并发扇出（单页最坏 160 次阻塞请求），并与 `product()`（`:452-475`）维护两套 `product.json` 解析逻辑。
     - [MainActivity.kt:914-915](../../app/src/main/java/com/nanahoshi/audioplayer/MainActivity.kt#L914-L915)：`Routes.SubtitleTasks` 未传 `onVerifyJaDownload`（对比 `Routes.Downloads` 第 883 行），从字幕任务通知切到音频页点击「验证并继续」失效。
     - [DlsiteGenres.kt:186-187](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteGenres.kt#L186-L187)：硬编码的 333 项标签中 `"同居"` 重复占用两个 ID（`031`、`438`），另有 8 个未翻译日文假名标签（`660`、`645`、`659`、`636`、`637`、`641`、`646`、`638`）。
     - [ModelSettingsScreen.kt:557](../../app/src/main/java/com/nanahoshi/audioplayer/ui/settings/ModelSettingsScreen.kt#L557)：切换翻译平台时误用草稿 `translateToken` 设置 `translateSaved`；`:522-532` 删除离线模型漏捕非 `WhisperException`。
     - [CacheScreen.kt:48-82, 127](../../app/src/main/java/com/nanahoshi/audioplayer/ui/settings/CacheScreen.kt#L48-L82)：根布局缺 `verticalScroll`（大字号易裁切），且 `usedBytes` 初值为 `0L` 闪现 `"0 B"`。

---

### 2.2 超大 God File 与耦合状态机

| 文件 | 行数 | 混杂职责与主要问题 | 建议拆分方向 |
| --- | --- | --- | --- |
| [OnlineScreens.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/asmr/OnlineScreens.kt) | 3,009 | 4 个独立页面 + 4 个 ViewModel + 5 个弹窗全部堆在同一文件（`OnlineWorkViewModel` 单体 847 行） | 拆为 `OnlineSearchScreen.kt`、`OnlineWorkScreen.kt`、`MineScreen.kt`、`PurchasesScreen.kt` |
| [LibraryRepository.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt) | 1,849 | 杂糅目录树 CRUD、三来源在线入库与刷新、本地目录识别、播放列表、队列、下载完成、原包可见性；另有 3 处 `catch (Exception)` 漏重抛 `CancellationException`（`:188, 206, 960, 1054`）、全表扫描及未类型化 `reorder(List<Any>)` | 按域拆为 `WorkImportRepository`、`LocalWorkRecognizer`、`PlaylistRepository`、`QueueRepository`、`LibraryBrowserRepository` |
| [DownloadCenter.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt) | 1,738 | 混杂队列泵、347 行 JA 验证状态机、DLsite 原包调度与 GC、5 个来源传输器、在线目标解析；`retireOriginalCopies` / `cleanUnpublishedOriginals` 读全表文件列表且循环内编译 `Regex` | 抽出 `JaWorkVerificationCoordinator`、`DlsiteArchiveRunner`、`DownloadTargetResolver` 及各来源 `TrackDownloader` |
| [LibraryViewModel.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/LibraryViewModel.kt) | 1,483 | 音频库列表与作品页各自实例化同一 VM 导致均启动 3 个 `init` 订阅；同时承载本地删除、下载来源向导与批量字幕匹配 | 独立 `LibraryWorkViewModel`、`WorkDownloadCoordinator` 与纯函数 `LibrarySearch.kt` |
| [WhisperCenter.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/WhisperCenter.kt) | 1,333 | 混杂队列调度、下载等待接管、3 个高度相似的执行模板、90 行引用计数暂存器 | 抽出 `ContentUriStager` 与统一 `executeTranscription` 模板 |
| [PlayerChrome.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/player/PlayerChrome.kt) | 1,323 | `NowPlayingScreen` 单函数 585 行（12 个局部状态 + 7 个弹层/弹窗）；`SubtitleReader` 接收 31 个参数 | 拆出 `MiniPlayer.kt`、`NowPlayingScreen.kt`、`QueueSheet.kt`、`SubtitleReader.kt`、`PlayerDialogs.kt` 并聚合状态对象 |
| [SubtitleSource.kt](../../app/src/main/java/com/nanahoshi/audioplayer/playback/SubtitleSource.kt) | 1,060 | 混杂字幕优先级加载、发布恢复、引用计数清理（`discardTranslationOverlay` 漏校验活跃翻译）、SAF 同级扫描；`boundCues` 每次读全量 `folders` 表 | 拆出 `SubtitleLoader`、`SubtitlePublicationStore`、`SubtitleCleanupStore` |
| [Daos.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/db/Daos.kt) | 1,041 | 9 个 DAO 全集中在一处（而新 DAO 已按领域分文件），含 23 处死查询及重复的多表 JOIN SQL | 按表拆分 DAO 文件，删去死查询，消除 `PlaylistDao`/`QueueDao` 重复 SQL |
| [MainActivity.kt](../../app/src/main/java/com/nanahoshi/audioplayer/MainActivity.kt) | 962 | `PlayerApp` 单函数 609 行，包含 22 条路由表、4 个全局弹窗、根部订阅全量数据库任务计数（引发根组件高频重组）及字符串化的 `operation` 路由参数 | 拆出 `AppNavHost.kt`、`AppGlobalOverlays.kt`、`ActiveTaskBadge` 与强类型 `JaVerifyOperation` |
| [SubtitlePickerSheet.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/components/SubtitlePickerSheet.kt) | 924 | `TrackSubtitlePickerSheet` 单函数 453 行 + `AppGraph` 适配桥接 + `FileToTrackSubtitleSheet` | 拆出 `AppGraph` 绑定查询适配与列表区块组件 |
| [ModelSettingsScreen.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/settings/ModelSettingsScreen.kt) | 879 | 3 个设置卡片、5 份重复开关行、离线模型面板、帮助弹窗与双草稿 VM | 抽取通用设置组件与 `KeyedDraft` |
| [JapaneseAsmrVerificationScreen.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/asmr/JapaneseAsmrVerificationScreen.kt) | 870 | 514 行单体 Composable 含 17 个状态字段 + 285 行内联 JS 字符串 | 抽离 `JapaneseAsmrWebScripts` 与验证控制器 |
| [BatchSubtitleMatch.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/BatchSubtitleMatch.kt) | 770 | 630 行向导 Composable 且在 UI 文件内直接递归扫 SAF `DocumentFile`（3 次 IPC/子项） | SAF 扫描移入 `data/LocalAudio.kt`，按步骤拆分 UI |
| [BiliCollectionViewModel.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliCollectionViewModel.kt) | 438 | 12 个独立可变属性靠跨字段条件互相制约，`bookmark()` 中途篡改 `work.operation` 以迎合返回键逻辑 | 收敛为 `sealed interface` 弹窗与阶段状态 |

---

### 2.3 已确认的死代码、不可达分支与导入腐化

1. **第三方 JNI 包装中的 1,292 行死代码与 R8 混淆隐患**
   - [OfflineRecognizer.kt:298-1523](../../app/src/main/java/com/k2fsa/sherpa/onnx/OfflineRecognizer.kt#L298-L1523)（`getOfflineModelConfig`，1,226 行上游 demo 工厂）与 [Vad.kt:120-153](../../app/src/main/java/com/k2fsa/sherpa/onnx/Vad.kt#L120-L153)（`getVadModelConfig`，34 行）以及若干未使用封装方法（`createStream(hotwords)`、`setConfig`、`prependAdspLibraryPath`、`OfflineStream.setOption/getOption/use`、`Vad.clear/isSpeechDetected/reset`）全工程零调用。由于 [build.gradle.kts:29-33](../../app/build.gradle.kts#L29-L33) 关闭了 Release 优化（`optimization.enable = false`），这部分代码与硬编码示例路径字符串全部打入正式 APK DEX。
   - **额外风险**：[rules.keep:15-17](../../app/src/main/keepRules/rules.keep#L15-L17) 仅保留了 `native <methods>`，未保留 `libsherpa-onnx-jni.so` 通过 JNI `GetFieldID` 访问的 `Offline*ModelConfig` 数据类字段；日后一旦开启 R8 压缩将导致离线听写崩溃。

2. **全仓零调用（或仅剩测试引用）的死文件、死组件与死方法**
   - **死文件/死组件**：
     - [WorkFactsBlock.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/asmr/WorkFactsBlock.kt)（73 行，全库 0 调用）。
     - [Badges.kt:80-166](../../app/src/main/java/com/nanahoshi/audioplayer/ui/components/Badges.kt#L80-L166)（`BadgeLine` 与 `fitBadgeCount`，87 行，生产代码 0 调用，已完全被 `WorkCard` 中的 `LazyRow` 替代，仅余 [BadgeLineTest.kt](../../app/src/test/java/com/nanahoshi/audioplayer/ui/components/BadgeLineTest.kt) 测 `fitBadgeCount` 与过时注释；需决定彻底移除并同步更新 `AGENTS.md`/`DESIGN.md` 或恢复使用）。
   - **23 个零调用 DAO 查询 + 4 个仅测试用 DAO 查询**（[Daos.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/db/Daos.kt)）：
     - 彻底死查询（23 个）：`TrackDao.countByWorkSource`（`:143`）；`LibraryFileDao.findByLocalUri`（`:194`）、`forWork`（`:215`）；`TrackSubtitleDao.observeAll`（`:236`）；`DownloadDao.locatedForWork`（`:361`）、`markRunning`（`:382`）、`progress`（`:385`）、`fail`（`:391`）、`failActive`（`:394`）；`WhisperDao.clearAwaitDownload`（`:439`）、`nextUploadable`（`:508`）、`deleteDoneForTrack`（`:534`）、`deleteDoneForUrl`（`:541`）、`deleteDoneForPath`（`:548`）、`markRunning`（`:570`）、`finish`（`:591`）、`markTranslating`（`:603`）、`markTranslatingIfActive`（`:612`）、`markTranslatedIfRunning`（`:633`）、`setTranslatedAt`（`:645`）；`TranslationDao.latestForUrl`（`:789`）、`finish`（`:811`）、`fail`（`:823`）；另有仅被死方法 `moveItems` 调用的 `FolderDao.setParent`（`:65`）。
     - 仅测试使用（4 个）：`DownloadDao.nextQueued`（`:349`）；`TranslationDao.latestForTrack`（`:786`）、`latestForQueue`（`:792`）、`markRunning`（`:808`）。
   - **死仓储/客户端/UI 方法与字段**：
     - [LibraryRepository.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt)：`hasAsmrWork`（`:316`）、`moveChoices`（`:491`）、`moveItems`（`:504`）、`importPlayWork`（`:741`）、`childFolders`（`:780`）、`filesIn`（`:784`）、`lookupBvid`（`:1189`）、`folderPath`（`:1804`）、`FolderChoice`（`:1846`）。
     - [PlayerClient.kt:232](../../app/src/main/java/com/nanahoshi/audioplayer/playback/PlayerClient.kt#L232)（`playTracks`）、[MediaItemFactory.kt:118](../../app/src/main/java/com/nanahoshi/audioplayer/playback/MediaItemFactory.kt#L118)（`biliLocalCache`）、[QueuePlayable.kt:33](../../app/src/main/java/com/nanahoshi/audioplayer/playback/QueuePlayable.kt#L33)（`mediaItem(id, context)`）。
     - [WhisperClient.kt:207](../../app/src/main/java/com/nanahoshi/audioplayer/data/WhisperClient.kt#L207)（`audioContainerFormat`）、[DlsiteClient.kt:360](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteClient.kt#L360)（`fileUrlBlocking`）、[BilibiliClient.kt:752](../../app/src/main/java/com/nanahoshi/audioplayer/bilibili/BilibiliClient.kt#L752)（`NavData.isLogin`）、[JapaneseAsmrParse.kt:52](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JapaneseAsmrParse.kt#L52)（`JaMediaRef.hasDescription` 仅单测读取）。
     - 6 个零调用遗留 `String` 异常构造器：[AsmrClient.kt:30](../../app/src/main/java/com/nanahoshi/audioplayer/asmr/AsmrClient.kt#L30)、[DlsiteClient.kt:44](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteClient.kt#L44)、[DlsiteArchiveClient.kt:43, 52](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteArchiveClient.kt#L43)、[JaWorkClient.kt:38, 41](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JaWorkClient.kt#L38)。
     - [PlayerScreens.kt:307](../../app/src/main/java/com/nanahoshi/audioplayer/ui/player/PlayerScreens.kt#L307)（`discardSubtitles`）、[LibraryViewModel.kt:545](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/LibraryViewModel.kt#L545)（`createFolder`）、[UserFacingError.kt:18](../../app/src/main/java/com/nanahoshi/audioplayer/text/UserFacingError.kt#L18)（`userTextOrNoUrl`）、[Motion.kt:22, 35-48](../../app/src/main/java/com/nanahoshi/audioplayer/ui/theme/Motion.kt#L35-L48)（`move`/`enterSpec`/`exitSpec`/`moveSpec`）、[SubtitleOffsetWriter.kt:99](../../app/src/main/java/com/nanahoshi/audioplayer/ui/components/SubtitleOffsetWriter.kt#L99)（`displayedOffset` 仅单测）、[YouTubeScreens.kt:295](../../app/src/main/java/com/nanahoshi/audioplayer/ui/youtube/YouTubeScreens.kt#L295)（`YouTubeAccountViewModel.busy` 只写不读）、[PlaylistScreens.kt:70](../../app/src/main/java/com/nanahoshi/audioplayer/ui/playlist/PlaylistScreens.kt#L70)（外层多余 `name` 状态）、[DownloadScreen.kt:327-328, 686](../../app/src/main/java/com/nanahoshi/audioplayer/ui/download/DownloadScreen.kt#L327-L328)（`onDelete` 与 `onDeleteRecord` 传同一回调、`toneFor` 多余 `@Composable`）。
   - **不可达分支**：[BiliChoices.kt:20-32](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliChoices.kt#L20-L32) / [BiliBrowserViewModel.kt:187, 213](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliBrowserViewModel.kt#L187) / [BiliBrowser.kt:254-265](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliBrowser.kt#L254-L265)（`prepare` 对非 `PLAY` 早退，导致多选 `SAVE` 分支生产不可达）；[JapaneseAsmrVerificationScreen.kt:347, 352](../../app/src/main/java/com/nanahoshi/audioplayer/ui/asmr/JapaneseAsmrVerificationScreen.kt#L347)（此前已早退）；[YouTubeClient.kt:94-95](../../app/src/main/java/com/nanahoshi/audioplayer/youtube/YouTubeClient.kt#L94-L95)（重复 `when` 分支）；[FavoriteScreen.kt:147, 182](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/FavoriteScreen.kt#L147)（恒为 `null` 的 `folderId` 参数）。
   - **废弃导入与错配命名**：[PlayerScreens.kt:3-127](../../app/src/main/java/com/nanahoshi/audioplayer/ui/player/PlayerScreens.kt#L3-L127) 含 **75 个未使用 UI 导入**（文件内已无任何 Screen，仅剩 `PlayerViewModel`）；[LibraryScreen.kt:11-116](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/LibraryScreen.kt#L11-L116) 含 **37 个未使用导入**；另有 12 个文件存零散死导入与重复全限定名。

---

### 2.4 高维护成本的自研实现与 Gradle 依赖错配

1. **忽略已引入的 `kotlinx.serialization` 与 `jsoup`，手写 JSON / XML / 正则 HTML**
   - `build.gradle.kts:65, 70` 已配置 `kotlinx-serialization-json` 与 `jsoup`，但：
     - [SubtitleCues.kt:159-238](../../app/src/main/java/com/nanahoshi/audioplayer/playback/SubtitleCues.kt#L159-L238) 用 `StringBuilder` + 手写 `jsonEscape` 拼装字幕 JSON（且第 87–139 行手工强转 `JsonObject`）。
     - [AsmrClient.kt](../../app/src/main/java/com/nanahoshi/audioplayer/asmr/AsmrClient.kt)、[DlsiteClient.kt](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteClient.kt)、[TranslateClient.kt:450-540](../../app/src/main/java/com/nanahoshi/audioplayer/data/TranslateClient.kt#L450-L540)（同一文件混用两套 JSON 库且将响应解析两遍）、[WhisperClient.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/WhisperClient.kt)、[QwenFileTranscriber.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/QwenFileTranscriber.kt)、[QwenCues.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/QwenCues.kt) 使用 Android `org.json`，阻碍 JVM 单元测试；[AppUpdate.kt:76-96](../../app/src/main/java/com/nanahoshi/audioplayer/data/AppUpdate.kt#L76-L96) 手工强转 `JsonElement`。
     - [DlsiteClient.kt:587-839](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteClient.kt#L587-L839) 用约 170 行脆弱正则与固定字符窗口（`+2500`/`+12000`/`+20000`）切片抓取 HTML，[DlsiteArchiveClient.kt:395-465](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteArchiveClient.kt#L395-L465)、[BiliSearch.kt:68-70](../../app/src/main/java/com/nanahoshi/audioplayer/bilibili/BiliSearch.kt#L68-L70)、[BiliSession.kt:84-90](../../app/src/main/java/com/nanahoshi/audioplayer/bilibili/BiliSession.kt#L84-L90) 手工替换 HTML 实体，均可直接迁移至 `Jsoup`。

2. **WebView 网页验证与 JS 签名桥高维护脚本**
   - [JapaneseAsmrVerificationScreen.kt:176-207, 585-870](../../app/src/main/java/com/nanahoshi/audioplayer/ui/asmr/JapaneseAsmrVerificationScreen.kt#L176-L207)：每 500ms 发起 3 次 `evaluateJavascript` 轮询且用 `.trim('"')` 剥引号（遇到转义字符易出错）；285 行内联 JS 中 `BUZZ_CAPTURE` 的 `follow()`（`:750`）还会重入自身 monkey-patch 的 `window.fetch`（`:817`）污染计数器。
   - [YouTubeSolver.kt:31-118](../../app/src/main/java/com/nanahoshi/audioplayer/youtube/YouTubeSolver.kt#L31-L118)：每次签发求解都新建并销毁整个 `WebView` 并注入 158 KiB 的 `yt-solver.cjs` 字符串，冷启动开销高。

3. **媒体流传输、校验与解压边界粗糙点**
   - [YouTubeClient.kt:253-368](../../app/src/main/java/com/nanahoshi/audioplayer/youtube/YouTubeClient.kt#L253-L368)：`copyAudio` 采用 `enqueue` 回调递归，在 OkHttp 回调线程上逐 1 MiB 块开关 `RandomAccessFile` 写盘，且取消时与后台写循环并发删文件；建议改写为 `withContext(Dispatchers.IO)` 线性循环 + `Call.withResponse`。
   - [JaMega.kt:304](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JaMega.kt#L304)：`JaMegaCrypto.verify` 每处理 1 MiB 分块都分配一个 1 MiB `ByteArray` 却只读取最后 16 字节（1 GiB 文件产生 ~1 GiB 临时垃圾），可复用单缓冲或分步 `cipher.update`。
   - [JapaneseAsmrHlsDownload.kt:237-281](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JapaneseAsmrHlsDownload.kt#L237-L281)：`AacTrack` 对每帧 AAC（约 43 帧/秒）做两次数组拷贝；`.ts` 分片在下载后、续传前和解复用时共被全文件扫描 3 遍；合成完成后与 [DownloadCenter.kt:1406](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L1406) 连续打开两次 `MediaMetadataRetriever` 读取同一个 `.m4a` 的时长。
   - [DlsiteArchiveExtractor.kt:93, 139-144](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteArchiveExtractor.kt#L93)：直接调用 `Charset.forName("MS932")` 未检查设备支持（对比 `TextDecoding.kt:77`）；`extractArchive` 将非单卷 `.zip`（含 `.zip.001`）全部送进 `extractRar`；solid RAR 跳过重复条目时 `CheckedOutput` 仍累计文件数与空间检查。

4. **其它高复杂度手写状态机与算法**
   - [LibraryRows.kt:115-151](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/LibraryRows.kt#L115-L151)：仅因 `LibraryViewModel` 缺少单步 `goToFolder(folderId)`，而在 UI 侧手写带 2 秒超时的循环 `up()` 协程爬升面包屑。
   - [SubtitleOffsetWriter.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/components/SubtitleOffsetWriter.kt)（234 行）：每条曲目维护 10 个同步可变字段来对冲 Room 流回弹。
   - [SubtitleCues.kt:275, 314, 338](../../app/src/main/java/com/nanahoshi/audioplayer/playback/SubtitleCues.kt#L275)：在逐条/逐时间戳循环内反复编译 `Regex(...)`（同型问题见 `AppUpdate.kt:57`、`ClipboardImportInput.kt:49`、`PlayingBiliCollection.kt:15`、`JaWorkFiles.kt:68, 523`、`DlsiteClient.kt:607, 629`）。
   - [BiliSession.kt:113-132](../../app/src/main/java/com/nanahoshi/audioplayer/bilibili/BiliSession.kt#L113-L132)：手写 20 行 Base64 解码器，未用 Okio `decodeBase64()`。
   - [BiliPlaybackDataSource.kt:475-593](../../app/src/main/java/com/nanahoshi/audioplayer/playback/BiliPlaybackDataSource.kt#L475-L593)：120 行自写 `BrowserHttpDataSource`，可用标准 DataSource + OkHttp 拦截器精简。
   - [WorkFolderMatch.kt:158-178](../../app/src/main/java/com/nanahoshi/audioplayer/data/WorkFolderMatch.kt#L158-L178)、[DlsiteOriginalStore.kt:344-382](../../app/src/main/java/com/nanahoshi/audioplayer/data/DlsiteOriginalStore.kt#L344-L382)、[WorkSubtitleMatcher.kt:214-232](../../app/src/main/java/com/nanahoshi/audioplayer/playback/WorkSubtitleMatcher.kt#L214-L232) 各自实现三套文件名宽窄字符/Unicode 规范化匹配。
   - [PressScale.kt:13-25](../../app/src/main/java/com/nanahoshi/audioplayer/ui/components/PressScale.kt#L13-L25) 混用 `@Composable` 与已弃用的 `Modifier.composed`；[Motion.kt:28-32](../../app/src/main/java/com/nanahoshi/audioplayer/ui/theme/Motion.kt#L28-L32) 每次重组读取 `Settings.Global` 未做 `remember`/监听缓存。

---

## 三、项目基建复用不足与高重复实现抽取建议

### 3.1 已有基建被绕过清单

#### 数据与网络基建

| 已有基建 | 绕过位置与典型现象 | 建议动作 |
| --- | --- | --- |
| 共享 `OkHttpClient` 连接池与调度器 | 全工程在 [AppGraph.kt](../../app/src/main/java/com/nanahoshi/audioplayer/AppGraph.kt)、5 个在线客户端、`DownloadCenter`、`BiliPlaybackDataSource`、`WhisperClient`、`TranslateClient`（甚至新建 2 个根实例）、`OfflineWhisperModelManager`、`ExternalFileSession`（每次预览新建实例）、`QwenFileTranscriber`、`AppUpdate` 共建了 **17+ 个独立根 `OkHttpClient`** | 在 `AppGraph` 维护共享基线 `OkHttpClient`，各组件通过 `.newBuilder()` 派生（JA 保持独立的 `NO_PROXY`/DNS 根实例供其 3 个客户端派生） |
| `resolveLibraryFileSource`（[EntryNames.kt:29](../../app/src/main/java/com/nanahoshi/audioplayer/data/EntryNames.kt#L29)） | [LibraryRepository.kt:324, 1766, 1781](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt#L1766)、[DlsiteOriginalStore.kt:126, 224](../../app/src/main/java/com/nanahoshi/audioplayer/data/DlsiteOriginalStore.kt#L126) 仍直接调 `libraryFileSource` | 统一改调 `resolveLibraryFileSource(file, jaFileIds)`，修复 JA 附件误判 |
| `LocalRemovalPlan` + `deleteLocalTargets`（[LocalRemoval.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/LocalRemoval.kt)） | [BiliVideoScreen.kt:84-106](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliVideoScreen.kt#L84-L106) 仍调旧 `deleteDownloaded`（跳过自定义字幕副本统计与清理）；[StoredFiles.kt:388-427](../../app/src/main/java/com/nanahoshi/audioplayer/data/StoredFiles.kt#L388-L427) 自写删除归属逻辑 | 全数收敛到 `localRemovalPlan` + `deleteLocalTargets` |
| `String.toHttps()`（[Https.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/Https.kt)） | [BilibiliClient.kt:361-368](../../app/src/main/java/com/nanahoshi/audioplayer/bilibili/BilibiliClient.kt#L361-L368)（已 import 却不用）、[BiliCollections.kt:46-50](../../app/src/main/java/com/nanahoshi/audioplayer/bilibili/BiliCollections.kt#L46-L50)、[BiliSearch.kt:71-75](../../app/src/main/java/com/nanahoshi/audioplayer/bilibili/BiliSearch.kt#L71-L75)、[YouTubeClient.kt:67](../../app/src/main/java/com/nanahoshi/audioplayer/youtube/YouTubeClient.kt#L67) | 删除 4 处本地副本，统一调 `toHttps()` |
| `Call.withResponse`（[ExternalFileSession.kt:240](../../app/src/main/java/com/nanahoshi/audioplayer/data/ExternalFileSession.kt#L240)） | [DlsiteClient.kt:564](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteClient.kt#L564)（不可取消 `execute()`）、[DlsiteArchiveClient.kt:72, 169](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteArchiveClient.kt#L72)（自写取消集合）、[BilibiliClient.kt:145](../../app/src/main/java/com/nanahoshi/audioplayer/bilibili/BilibiliClient.kt#L145)（`browseGet`）、[YouTubeClient.kt:372](../../app/src/main/java/com/nanahoshi/audioplayer/youtube/YouTubeClient.kt#L372)、[AppUpdate.kt:102](../../app/src/main/java/com/nanahoshi/audioplayer/data/AppUpdate.kt#L102)、[QwenFileTranscriber.kt:227](../../app/src/main/java/com/nanahoshi/audioplayer/data/QwenFileTranscriber.kt#L227) | 将 `Call.withResponse` 迁至公共 `HttpSupport.kt` 并全面替换 |
| `ProgressHttp` / `TaskProgressCenter`（[ProgressHttp.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/ProgressHttp.kt)） | [JaWorkClient.kt:281-282](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JaWorkClient.kt#L281-L282)、[TranslateClient.kt:500-504](../../app/src/main/java/com/nanahoshi/audioplayer/data/TranslateClient.kt#L500-L504) 未接网络阶段事件；`OfflineWhisperModelManager` 与 `SubtitleSource:596` 绕过进度门面 | 补齐 `ProgressNetworkEvents` 与 `.taskProgress(...)` 挂载，进度摘要读写收口到 `TaskProgressCenter` |
| `UserFacingError` / `AppGraph.showError` | 除上文 113+ 处 `error()` 外，[BiliBrowserViewModel.kt:109, 153, 160, 217](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliBrowserViewModel.kt#L109)、[YouTubeScreens.kt:103](../../app/src/main/java/com/nanahoshi/audioplayer/ui/youtube/YouTubeScreens.kt#L103)、[FilePreviewScreen.kt:561](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/FilePreviewScreen.kt#L561)、[JapaneseAsmrVerificationScreen.kt:129, 387](../../app/src/main/java/com/nanahoshi/audioplayer/ui/asmr/JapaneseAsmrVerificationScreen.kt#L129)、[LibraryViewModel.kt:848](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/LibraryViewModel.kt#L848)（`as? JaWorkException` 漏掉 `JapaneseAsmrException`）手动分支或直接吞没错误 | 全部改用 `UserFacingException` + `graph.showError` / `resolveUserError`；另外 [AboutScreen.kt:76](../../app/src/main/java/com/nanahoshi/audioplayer/ui/settings/AboutScreen.kt#L76) 与 [StoredFilesScreen.kt:658](../../app/src/main/java/com/nanahoshi/audioplayer/ui/download/StoredFilesScreen.kt#L658) 应在 VM 中发消息而非在 UI 内强转 `Application` |
| 其它小工具与既有协调器 | `readBoundedBytes`（[DlsiteArchiveClient.kt:507](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteArchiveClient.kt#L507) 重写）；`contentUriReadable`（[SubtitleBindingStore.kt:237](../../app/src/main/java/com/nanahoshi/audioplayer/data/SubtitleBindingStore.kt#L237) 重写）；`SubtitleSource.generatedFile`（4 处重复）；`DlsiteDownloadRequests` 持锁等用户（已记入 [open-issues](./2026-11-10-open-issues.md#L65)，应与 `BiliImportRequests` 对齐非阻塞占位提示）；9 处 `Data`/`DAO` 硬编码中文字符串未入 `strings*.xml` | 抽共用后删除各自复制品 |

#### UI 组件与 `DESIGN.md` 规范基建

- **`ConfirmActionDialog`（[ConfirmActionDialog.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/components/ConfirmActionDialog.kt)）被 11 处原始 `AlertDialog` 绕过**：[LibraryRows.kt:238](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/LibraryRows.kt#L238)（`ConfirmRemoveDialog` 完全克隆）、[OnlineScreens.kt:2844](../../app/src/main/java/com/nanahoshi/audioplayer/ui/asmr/OnlineScreens.kt#L2844)（已 import 却不用）、[PlayerChrome.kt:414, 441, 1271](../../app/src/main/java/com/nanahoshi/audioplayer/ui/player/PlayerChrome.kt#L441)（其中清空队列确认还漏了 `DESIGN.md` §1.2 要求的破坏性操作 `error` 颜色）、[SubtitlePickerSheet.kt:580](../../app/src/main/java/com/nanahoshi/audioplayer/ui/components/SubtitlePickerSheet.kt#L580)、[BatchSubtitleMatch.kt:713](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/BatchSubtitleMatch.kt#L713)、[BiliVideoScreen.kt:238](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliVideoScreen.kt#L238)、[PlaylistScreens.kt:192](../../app/src/main/java/com/nanahoshi/audioplayer/ui/playlist/PlaylistScreens.kt#L192)、[BiliCollectionScreen.kt:247](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliCollectionScreen.kt#L247)、[StoredFilesScreen.kt:488](../../app/src/main/java/com/nanahoshi/audioplayer/ui/download/StoredFilesScreen.kt#L488)。只需给 `ConfirmActionDialog` 增加可选 `title: String?`、`dismissLabel`、`enabled` 即可全部吸收。
- **`ErrorState` / `EmptyState` / `LoadingState`（[States.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/components/States.kt)）被绕过**：[FilePreviewScreen.kt:469](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/FilePreviewScreen.kt#L469)、[BiliBrowser.kt:238](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliBrowser.kt#L238)、[CollectionCatalogue.kt:45](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/CollectionCatalogue.kt#L45)、[BiliCollectionScreen.kt:155, 165](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliCollectionScreen.kt#L155)、[YouTubeScreens.kt:218](../../app/src/main/java/com/nanahoshi/audioplayer/ui/youtube/YouTubeScreens.kt#L218)（缺重试按钮）、[FavoriteScreen.kt:268](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/FavoriteScreen.kt#L268)、[SubtitlePickerSheet.kt:188](../../app/src/main/java/com/nanahoshi/audioplayer/ui/components/SubtitlePickerSheet.kt#L188)、[OnlineScreens.kt:1956, 2132](../../app/src/main/java/com/nanahoshi/audioplayer/ui/asmr/OnlineScreens.kt#L1956)。
- **`RoundedProgress`（[Progress.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/components/Progress.kt)）被 9 处原始 `LinearProgressIndicator` 绕过**：[ModelSettingsScreen.kt:732](../../app/src/main/java/com/nanahoshi/audioplayer/ui/settings/ModelSettingsScreen.kt#L732)（丧失平滑进度与 `LocalReduceMotion` 退化）、[BiliBrowser.kt:169](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliBrowser.kt#L169)、[BiliCollectionScreen.kt:116](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliCollectionScreen.kt#L116)、[YouTubeScreens.kt:217](../../app/src/main/java/com/nanahoshi/audioplayer/ui/youtube/YouTubeScreens.kt#L217)、[AboutScreen.kt:97](../../app/src/main/java/com/nanahoshi/audioplayer/ui/settings/AboutScreen.kt#L97)、[ClipboardImportDialog.kt:60](../../app/src/main/java/com/nanahoshi/audioplayer/ui/importing/ClipboardImportDialog.kt#L60)、[DlsiteWebLogin.kt:148](../../app/src/main/java/com/nanahoshi/audioplayer/ui/asmr/DlsiteWebLogin.kt#L148)、[JapaneseAsmrVerificationScreen.kt:425](../../app/src/main/java/com/nanahoshi/audioplayer/ui/asmr/JapaneseAsmrVerificationScreen.kt#L425)、[LibraryRows.kt:755](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/LibraryRows.kt#L755)。
- **`Motion` 动效工厂（[Motion.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/theme/Motion.kt)）零调用**：[MainActivity.kt:434](../../app/src/main/java/com/nanahoshi/audioplayer/MainActivity.kt#L434)（魔数 `180`ms）、[PlayerChrome.kt:169, 174, 709, 853, 1138](../../app/src/main/java/com/nanahoshi/audioplayer/ui/player/PlayerChrome.kt#L169)（魔数 `100`ms 及退出使用 `Motion.enter`）、[OnlineScreens.kt:1426, 2671](../../app/src/main/java/com/nanahoshi/audioplayer/ui/asmr/OnlineScreens.kt#L1426) 均在手工重复 `if (reduce) snap() else tween(...)`。
- **`SettingsGroup`（[SettingsScreen.kt:252](../../app/src/main/java/com/nanahoshi/audioplayer/ui/settings/SettingsScreen.kt#L252)）被同包 3 处重写**：[AppearanceScreen.kt:95](../../app/src/main/java/com/nanahoshi/audioplayer/ui/settings/AppearanceScreen.kt#L95)、[AboutScreen.kt:86, 92](../../app/src/main/java/com/nanahoshi/audioplayer/ui/settings/AboutScreen.kt#L86)、[ModelSettingsScreen.kt:389](../../app/src/main/java/com/nanahoshi/audioplayer/ui/settings/ModelSettingsScreen.kt#L389)。
- **其它组件与 [DESIGN.md](../../../DESIGN.md) 局部漂移**：
  - [WorkHeader.kt:457](../../app/src/main/java/com/nanahoshi/audioplayer/ui/components/WorkHeader.kt#L457) 重写 `SourceBadge`；`TrackSource` 显示名在 `Badges` / `SubtitlePickerSheet` / `OnlineScreens` / `LibraryRows` 重复 4 次；13 个文件仍导入 [Common.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/Common.kt) 遗留 `CoverImage`/`EmptyHint` 透传包装器。
  - [PlayerChrome.kt:355, 815](../../app/src/main/java/com/nanahoshi/audioplayer/ui/player/PlayerChrome.kt#L355) 在 `ModalBottomSheet` 内的 `ListItem` 未传透明容器色；`:521` 及 [PlaylistScreens.kt:151](../../app/src/main/java/com/nanahoshi/audioplayer/ui/playlist/PlaylistScreens.kt#L151)、[AboutScreen.kt:88](../../app/src/main/java/com/nanahoshi/audioplayer/ui/settings/AboutScreen.kt#L88)、[BiliBrowser.kt:221](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliBrowser.kt#L221)、[YouTubeScreens.kt:259](../../app/src/main/java/com/nanahoshi/audioplayer/ui/youtube/YouTubeScreens.kt#L259)、[StoredFilesScreen.kt:580](../../app/src/main/java/com/nanahoshi/audioplayer/ui/download/StoredFilesScreen.kt#L580) 使用了未在 [Type.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/theme/Type.kt) 配置的字阶；[PlayerChrome.kt:683](../../app/src/main/java/com/nanahoshi/audioplayer/ui/player/PlayerChrome.kt#L683) 使用字符 `"×"` 而非 `Icons.Default.Close`；[ModelSettingsScreen.kt:797](../../app/src/main/java/com/nanahoshi/audioplayer/ui/settings/ModelSettingsScreen.kt#L797) 与 [BiliImportDialog.kt:81](../../app/src/main/java/com/nanahoshi/audioplayer/ui/bilibili/BiliImportDialog.kt#L81) 单选行缺 `48.dp` 最小触控高度。

---

### 3.2 高重复率代码与推荐抽取的通用抽象

#### 数据、服务与客户端层

1. **三个前台服务合并为 `DataSyncForegroundService` + `ForegroundTaskRequests.kt`**
   - 重复来源：[AudioDownloadService.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/AudioDownloadService.kt)、[SubtitleTaskService.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/SubtitleTaskService.kt)、[OfflineModelDownloadService.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/OfflineModelDownloadService.kt) 各自复制约 150+ 行的渠道创建、`ServiceCompat.startForeground` 异常防护、6 小时 `PARTIAL_WAKE_LOCK` 管理、`onDestroy`、`start()` 入口以及 `AudioDownloadStopped` / `SubtitleTaskStopped`（且 `OfflineModelDownloadService` 还漏掉了 `VISIBILITY_PRIVATE`）。
2. **`HttpSupport.kt` 统一网络、重定向、Range 与流式下载原语**
   - **6 处 64KB 流式拷贝 + 限频进度报告**：[DownloadCenter.kt:1287, 1479, 1634](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L1287)、[YouTubeClient.kt:332](../../app/src/main/java/com/nanahoshi/audioplayer/youtube/YouTubeClient.kt#L332)、[DlsiteArchiveClient.kt:292](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteArchiveClient.kt#L292)、[JapaneseAsmrHlsDownload.kt:111](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JapaneseAsmrHlsDownload.kt#L111)、[JaWorkClient.kt:597](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JaWorkClient.kt#L597)、[ExternalFileSession.kt:205](../../app/src/main/java/com/nanahoshi/audioplayer/data/ExternalFileSession.kt#L205) → 统一 `copyStreamToFile(...)`。
   - **5 处手动 HTTP 重定向循环**：`DlsiteArchiveClient:158`、`JaNetworkPolicy:29`、`JaWorkClient:362, 637`、`ExternalFileSession:163`。
   - **4 处 `Content-Range` 解析器**：`DlsiteArchiveClient:467`、`YouTubeModels:163`、`JaWorkClient:740`（缺少起止范围边界校验）、`JapaneseAsmrHlsDownload:109` → 统一 `HttpByteRange.parse(...)`。
   - **3 处 `InflightCalls.execute(...)`**：`WhisperClient:73`、`QwenFileTranscriber:213`、`TranslateClient:476`。
3. **跨客户端与数据层的纯函数公共库**
   - **`WorkIds.kt`**：收敛 5 处 RJ/VJ/BJ（其中 [AsmrClient.kt:344](../../app/src/main/java/com/nanahoshi/audioplayer/asmr/AsmrClient.kt#L344) 漏掉 `BJ`）与 9 处 BV 正则（多处每次调用内联编译）。
   - **`CookieHeaders.kt`**：统一 5 处 Cookie 解析/合并/过滤（`BiliSession`、`DlsiteCookies`、`DlsiteArchiveClient`、`DlsiteClient`、`YouTubeModels`）。
   - **`JsonHelpers.kt`**：统一 5 处 `kotlinx.serialization.json` DOM 便捷取值（`BiliSearch`、`BiliCollections`、`BiliVideoCollection`、`JaMega`、`YouTubeModels`）与 2 处逐字克隆的 `org.json` `names()`（`AsmrClient:396`、`DlsiteClient:968`）。
   - **`Digests.kt`**：统一全库 **10 处**重复的 SHA-256 / 摘要转十六进制代码。
   - **`LocalAudio.readDurationMs`**：合并全库 **6 处**重复的 `MediaMetadataRetriever` 时长提取（`LocalAudio`、`DownloadCenter`、`DlsiteOriginalStore`、`JaWorkFiles`、`WhisperCenter`、`JapaneseAsmrHlsDownload`）。
   - **`FolderTree.kt`**：统一 **9 处**目录树祖先/后代遍历；统一 **5 处**彼此漂移的音频/字幕扩展名集合；统一 **4 处**大小格式化（消除 `KiB/MiB` 与 `KB/MB` 单位分裂及 `ModelSettingsScreen:737` `0 / 0 MiB` 问题）。
4. **原包代次清理与任务调度协调器**
   - `WorkGenerationCleaner`（合并 `DownloadCenter:656-714` 与 `JaWorkFiles:519-548` 的 UUID 目录与孤儿回收）+ `DlsiteOriginalStore.isIntact`（3 处复制）。
   - `SinglePromptCoordinator<P, R>`（合并 `BiliImportRequests` 与 `DlsiteDownloadRequests`）与 `TokenRequestCoordinator<P, R>`（合并 `JaRefreshRequests` 与 `DownloadCenter` 验证映射）。
   - `PreferenceFlow` 轻封装，同时修复 `AppearancePreferences`、`DownloadPreferences`、`LibraryPreferences`、`SubtitleDisplayPreferences` 未用 `applicationContext` 的隐患，并按领域拆分 `CredentialStore`。

#### UI 与 ViewModel 层

1. **音频库与作品页共享 UI 脚手架**（约 150 行重复）：将 [LibraryScreen.kt:343-405](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/LibraryScreen.kt#L343-L405) 与 [LibraryWorkScreen.kt:295-363](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/LibraryWorkScreen.kt#L295-L363) 的曲目/文件列表、多选顶栏动作及 5 个共用弹窗宿主抽为 `libraryEntries` 与 `LibrarySharedDialogs`。
2. **统一已存音频播放入口**：合并 `DownloadViewModel.play`（[DownloadScreen.kt:196-235](../../app/src/main/java/com/nanahoshi/audioplayer/ui/download/DownloadScreen.kt#L196-L235)）与 `StoredFilesViewModel.play`（[StoredFilesScreen.kt:132-171](../../app/src/main/java/com/nanahoshi/audioplayer/ui/download/StoredFilesScreen.kt#L132-L171)）到 IO 协程的 `PlayerClient.playStoredAudio(...)`，修复 `StoredFilesViewModel` 缺 B 站 `Referer` 及 `DownloadViewModel` 缺 `japaneseasmr://file?` 识别的分化。
3. **可复用通用 UI 组件（`ui/components/` 与 `ui/settings/`）**：
   - `ModalSheetHeader`（合并 `SubtitlePickerSheet:741`、`BatchSubtitleMatch:228`、`PlayerChrome:346, 775`）。
   - `ImmersiveWorkScaffold`（合并 `OnlineScreens:1974`、`LibraryWorkScreen:175`、`BiliVideoScreen:137` 的 Hero 测量与顶栏透明渐变）。
   - `SecretTextField`（合并 5 处带眼睛图标的密文输入框）。
   - `SelectableLeading`（合并 `WorkCard` 与 `LibraryRows` 4 处多选态渐变勾选框）、`SegmentedTabBar`（3 处）、`LoadMoreOnScrollEnd`（3 处）、`DropdownFilterButton`（4 处）、`TaskRowScaffold`（3 种任务行）、`DeleteSubtitleLanguageDialog`（`StoredFilesScreen` 与 `PlayerChrome`）、`SettingsComponents.kt`（统一 `SettingsGroup`、6 处开关行、4 处单选弹窗、2 处下拉、2 处帮助弹窗）。
   - 小聚合：`imageModelOf`（3 处 Coil 本地/HTTPS 模型构造）、`FolderEntity.toWorkFacts`（6 处）、让 `WorkPreviewRow` 委托给 `WorkCard`、`InfoNoticeDialog`（4 处单按钮提示弹窗）、`openExternalUrl`（2 处外部浏览器跳转）、`AppGraph.launchGuarded`、`SelectionState<K>`（5 处 VM 多选状态）、`KeyedDraft<K, V>`（`ModelSettingsViewModel` 双草稿状态）。

---

## 四、测试套件审计（冗余无效测试、静默失效与覆盖缺口）

### 4.1 冗余、低价值或非自动化测试

| 类别 | 目标文件 / 行号 | 具体原因与处置建议 |
| --- | --- | --- |
| 模板占位 | [ExampleUnitTest.kt](../../app/src/test/java/com/nanahoshi/audioplayer/ExampleUnitTest.kt)、[ExampleInstrumentedTest.kt](../../app/src/androidTest/java/com/nanahoshi/audioplayer/ExampleInstrumentedTest.kt) | IDE 默认模板测试（`2 + 2 == 4`、包名校验），直接删除 |
| 非自动化调试脚本 | [TaskProgressPreviewAndroidTest.kt](../../app/src/androidTest/java/com/nanahoshi/audioplayer/data/TaskProgressPreviewAndroidTest.kt) | 恒被 `assumeTrue` 跳过，直接改写真实数据库并阻塞 600 秒等待 `adb` 外部哨兵文件；迁出正式 `androidTest` 目录 |
| 100% 重复文件 | [SubtitleTaskListTest.kt](../../app/src/test/java/com/nanahoshi/audioplayer/ui/download/SubtitleTaskListTest.kt) | 测 `isClearableCompletedWhisper` 的全部情形已完全被 [SubtitleTaskSortTest.kt:41-53](../../app/src/test/java/com/nanahoshi/audioplayer/ui/download/SubtitleTaskSortTest.kt#L41-L53) 覆盖，直接删除 |
| 同义反复 / 低价值 | [LoadedSubtitlesStateTest.kt](../../app/src/test/java/com/nanahoshi/audioplayer/playback/LoadedSubtitlesStateTest.kt) | 4 个用例中 3 个只测 Kotlin 默认参数和 `copy()`；仅把 `cuesClippedByNegativeOffsetCanStillBeTranslated` 并入 [SubtitleLanguageTest.kt](../../app/src/test/java/com/nanahoshi/audioplayer/playback/SubtitleLanguageTest.kt) 后删除 |
| 同义反复 / 复制粘贴 | [BusyCounterTest.kt](../../app/src/test/java/com/nanahoshi/audioplayer/ui/library/BusyCounterTest.kt#L19-L38) | 两个 `@Test` 语句逐行完全一致，却漏测 `end()` 下溢（underflow） |
| 断言错失重点 | [SettingsInputBaselineTest.kt](../../app/src/test/java/com/nanahoshi/audioplayer/ui/settings/SettingsInputBaselineTest.kt) | 仅测 `List.equals`，漏测类注释明确声明的 `toString()` 不含凭证不变量 |
| 过碎微测试 | [SourcePagingTest.kt](../../app/src/test/java/com/nanahoshi/audioplayer/ui/asmr/SourcePagingTest.kt)、[LibraryWorkSortTest.kt](../../app/src/test/java/com/nanahoshi/audioplayer/ui/library/LibraryWorkSortTest.kt)、[BiliSubtitleTest.kt](../../app/src/test/java/com/nanahoshi/audioplayer/bilibili/BiliSubtitleTest.kt)、[BiliTicketTest.kt](../../app/src/test/java/com/nanahoshi/audioplayer/data/BiliTicketTest.kt)、[BiliChoicesTest.kt](../../app/src/test/java/com/nanahoshi/audioplayer/ui/bilibili/BiliChoicesTest.kt) | 单行表达式拆出独立文件或保留已死 `SAVE` 分支的单元素循环；建议并入对应包级测试 |
| 应下沉至快速 JVM | [TaskProgressTransferAndroidTest.kt:23-100](../../app/src/androidTest/java/com/nanahoshi/audioplayer/data/TaskProgressTransferAndroidTest.kt#L23-L100) | 前 3 个用例零 Android API 依赖，可直接迁到 `src/test`（待 `Qwen*` 迁离 `org.json` 后可整文件迁移） |
| 非 UI 被 UI 规则阻塞 | [AboutAndroidTest.kt:22-28](../../app/src/androidTest/java/com/nanahoshi/audioplayer/settings/AboutAndroidTest.kt#L22-L28)、[ClipboardImportAndroidTest.kt:23-48](../../app/src/androidTest/java/com/nanahoshi/audioplayer/importing/ClipboardImportAndroidTest.kt#L23-L48) | 非 Compose 测试与 `createComposeRule()` 放在同一文件/类里，被 Espresso 问题一并阻断，且 `AboutAndroidTest.currentVersionIsTheInstalledPackageVersion` 属同义反复 |

### 4.2 Compose UI 测试失灵、4 处文案静默失效与迁移兼容性隐患

- **API 36/37 全量阻断**：[libs.versions.toml:6](../../gradle/libs.versions.toml#L6) 固定 `espressoCore = "3.5.1"`，导致全库 **11 个 Compose UI `androidTest`（943 行、25 个用例）** 在 API 36/37 触发 `InputManager.getInstance()` 反射崩溃（升级到 Espresso 3.6.1+/3.7.0 可解决）。
- **4 个已静默失效的 UI 测试**：10 个 UI 测试文件中共有 **102 处硬编码中文 `onNodeWithText` 断言**。以下 4 处已与当前 `strings*.xml` 脱节：
  1. [BiliVideoRowAndroidTest.kt:66, 67, 81, 111, 112, 114](../../app/src/androidTest/java/com/nanahoshi/audioplayer/ui/library/BiliVideoRowAndroidTest.kt#L66-L67)：断言 `"B站"` 与 `"多P"`，实际资源已加空格为 `"B 站"` 与 `"多 P"`。
  2. [WorkBrowserAndroidTest.kt:72](../../app/src/androidTest/java/com/nanahoshi/audioplayer/ui/library/WorkBrowserAndroidTest.kt#L72)：断言 `"正在读取文件…"`，实际 `R.string.stored_loading` 为 `"正在读取文件"`（无省略号）。
  3. [ClipboardImportAndroidTest.kt:63](../../app/src/androidTest/java/com/nanahoshi/audioplayer/importing/ClipboardImportAndroidTest.kt#L63)：断言 `"导入这个内容？"`，实际 `R.string.import_confirm_title` 为 `"加入音频库？"`。
  4. [AboutAndroidTest.kt:34](../../app/src/androidTest/java/com/nanahoshi/audioplayer/settings/AboutAndroidTest.kt#L34)：断言 `"当前版本：1.2（3）"`，实际 `R.string.about_version` 为 `"版本 1.2（构建 3）"`。
- **可剥离到 JVM 的纯逻辑**：`DeleteAudioDialog` 文案与按钮决策模型、`ClipboardImportPreview` 状态谓词、`PlayerCollectionAction` 与 `SubtitleReader` 可见性判断、`CollectionFailureDialog` 失败展示模型，均应抽成纯函数迁往快速 `src/test`。
- **迁移测试低版本兼容隐患**：[BiliCollectionMigrationAndroidTest.kt:36-59](../../app/src/androidTest/java/com/nanahoshi/audioplayer/bilibili/BiliCollectionMigrationAndroidTest.kt#L36-L59)、[TaskHistoryMigrationAndroidTest.kt:30-34](../../app/src/androidTest/java/com/nanahoshi/audioplayer/data/TaskHistoryMigrationAndroidTest.kt#L30-L34)、[TaskProgressAndroidTest.kt:55-68](../../app/src/androidTest/java/com/nanahoshi/audioplayer/data/TaskProgressAndroidTest.kt#L55-L68)、[JapaneseAsmrAndroidTest.kt:105-118](../../app/src/androidTest/java/com/nanahoshi/audioplayer/japaneseasmr/JapaneseAsmrAndroidTest.kt#L105-L118) 通过在最新 v24 表上执行 `ALTER TABLE ... DROP COLUMN` 逆向构造旧表，在 API 24–33 的真机/模拟器上会直接崩溃。

### 4.3 高风险功能覆盖缺口（51 / 182 个主文件零测试引用，占 24.7% 代码量）

1. **数据库迁移**：`MIGRATION_1_2` 至 `MIGRATION_11_12`（共 11 个迁移，含重建 `tracks` 表的 `1_2`、重建 `queue_items` 外键的 `5_6` 与关联子查询回填的 `3_4`）**零测试**；`MIGRATION_17_18` 等缺少带预设历史脏数据的真实清理断言。
2. **核心调度与字幕管线**：
   - [WhisperCenter.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/WhisperCenter.kt)（1,333 行）：全库仅 2 个测试实例化且只走 `QWEN3 + forceRegenerate` 一条分支；`resumePending`、下载接力等待、`skipTranslate` 自动触发翻译、`retryTranslation`、Qwen3-ASR/阿里云/离线模型分发、`needsDlsiteDownloadChoice` 均**零测试**。
   - [TranslateClient.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/TranslateClient.kt)（545 行）HTTP 请求/截断检测与 [TranslateCenter.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/TranslateCenter.kt) `translateAll`（40/20 批次、结构失败重传、软违规修正与回退、润色失败兜底）**零测试**。
   - [DownloadCenter.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt)（DLsite 整包与 Play 下载互斥、B 站待补字幕恢复、进程中断恢复、YouTube/ASMR/Play 端到端下载）及 **3 个前台服务**（超时、WakeLock 释放、通知更新）**零测试**。
   - [BatchSubtitleMatch.kt](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/BatchSubtitleMatch.kt)（770 行）、`SubtitleBindingStore.batchBind*` 及 `LibraryViewModel` 批量提交路径**零测试**。
   - [SubtitleSource.kt](../../app/src/main/java/com/nanahoshi/audioplayer/playback/SubtitleSource.kt) 的 `crossSourceFallback` 开关切换、`bilibiliCues` 缓存/登录态/限流重试、本地失败转远端、同级文件格式排序及时间轴负偏移边界**零测试**。
   - [LibraryRepository.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt) 的 `importTree`、`recognizeLocalTree` 事务及多来源填充**零端到端测试**。
3. **播放栈、在线客户端与 ViewModel**：
   - [PlayerClient.kt](../../app/src/main/java/com/nanahoshi/audioplayer/playback/PlayerClient.kt)（532 行）、[BiliPlaybackDataSource.kt](../../app/src/main/java/com/nanahoshi/audioplayer/playback/BiliPlaybackDataSource.kt)（除 JA 外全部协议）、[PlaybackService.kt](../../app/src/main/java/com/nanahoshi/audioplayer/playback/PlaybackService.kt) 生命周期与进度持久化几乎**零测试**。
   - [WbiSigner.kt](../../app/src/main/java/com/nanahoshi/audioplayer/bilibili/WbiSigner.kt)（41 行纯 JVM 签名算法）、[QwenCues.kt](../../app/src/main/java/com/nanahoshi/audioplayer/data/QwenCues.kt)、以及 `BilibiliClient`/`DlsiteClient`/`AsmrClient`/`QwenFileTranscriber`/`OfflineWhisperTranscriber` 的 HTTP 与异常分支缺口显著。
   - 所有主要 ViewModel（`LibraryViewModel`、`OnlineSearchViewModel`、`OnlineWorkViewModel`、`BiliBrowserViewModel`、`PlaylistViewModel`、`ClipboardImportViewModel`、`ModelSettingsViewModel`）均无直接 ViewModel 状态测试。
4. **安全与隐私不变量缺口**：
   - [DlsiteArchiveExtractor.kt:99, 135-138, 178, 195-211, 242-268](../../app/src/main/java/com/nanahoshi/audioplayer/dlsite/DlsiteArchiveExtractor.kt#L99) 缺少 NUL/控制字符/盘符路径、ZIP/RAR 符号链接拒绝、加密 RAR 拒绝、重名/目录文件碰撞、解压超限（zip-bomb）防御测试。
   - [BiliPlaybackDataSource.kt:314-365](../../app/src/main/java/com/nanahoshi/audioplayer/playback/BiliPlaybackDataSource.kt#L314-L365) 缺少「DLsite 试听（`fileKey == null`）绝不携带登录 Cookie」与主机校验回归测试。
   - [SettingsInputBaseline.kt:3-7](../../app/src/main/java/com/nanahoshi/audioplayer/ui/settings/SettingsInputBaseline.kt#L3-L7) 与 `TranslateClient` / `WhisperClient` 缺少异常/字符串不泄露凭证测试。

### 4.4 测试基建重复与真实偏好污染缺陷

- **真实偏好污染漏洞**：[DownloadLibraryGateAndroidTest.kt](../../app/src/androidTest/java/com/nanahoshi/audioplayer/data/DownloadLibraryGateAndroidTest.kt) 与 [BiliGroupedImportAndroidTest.kt](../../app/src/androidTest/java/com/nanahoshi/audioplayer/bilibili/BiliGroupedImportAndroidTest.kt) 的 `ContextWrapper` 仅覆盖了 `filesDir` 而**未隔离 `getSharedPreferences()`**，运行时会读写真实应用的 `player_prefs`。
- **高重复测试样板量化**：
  - `Room.inMemoryDatabaseBuilder`：20 个文件中重复 **49 次**（仅 `StoredCatalogOverlayAndroidTest` 就重复 15 次）。
  - 隔离 `ContextWrapper`：13 个文件中 **17 次**；`LibraryRepository` 构造：12 个文件中 **20 次**；`SubtitleSource` 构造：9 个文件中 **28 次**。
  - `AppDatabase` 未导出 `ALL_MIGRATIONS` 数组，导致 8 个迁移测试各自复制整串 `addMigrations(...)` 与 `PRAGMA` 校验逻辑（9 处）。
  - `QueuedTrack` / `TrackEntity` / `WhisperJobEntity` 等实体手工构造跨 `test` 与 `androidTest` 合计 **136 次**；`test` 中有 **32 个手写 OkHttp 拦截器**与 **29 处手工临时目录清理**；`androidTest` 中维护了 **3 套手写 `ServerSocket` HTTP 服务**与 **7 处重复 WAV 生成器**。
- **建议**：暴露 `AppDatabase.ALL_MIGRATIONS`，建立 `androidTest` 共享 `TestDb.kt`（`withIsolatedApp` 同时隔离 `filesDir`、`cacheDir` 与 `SharedPreferences`）及 `test` 共享 `TestFixtures.kt` / `TestHttp.kt`，并引入 `kotlinx-coroutines-test`。

---

## 五、分阶段整改路线图

> 说明：第 1、2 组全部 **41 项边界缺陷**均可直接基于当前数据库版本 `v24` 修复，无需升级 `AppDatabase` Schema 版本。

1. **Phase 0 — 止血与高危缺陷修复（建议优先）**
   - 修复全部 **11 项 P1 边界缺陷**：
     - 存储与下载（6 项）：`StoredFiles` B 站误删与 `original/` 未发布暴露、`DlsiteOriginalStore` 恢复防复活与冲突占用者隔离、`DownloadCenter` 禁止 206 误发与 `..` 目录逃逸。
     - 听写/翻译/字幕/认证（5 项）：[WhisperCenter.kt:534](../../app/src/main/java/com/nanahoshi/audioplayer/data/WhisperCenter.kt#L534) 重试重算指纹、[WhisperClient.kt:80](../../app/src/main/java/com/nanahoshi/audioplayer/data/WhisperClient.kt#L80) 按模型+端点绑定凭证、[TranslateClient.kt:444](../../app/src/main/java/com/nanahoshi/audioplayer/data/TranslateClient.kt#L444) 捕获不可变平台配置快照、[BilibiliClient.kt:615](../../app/src/main/java/com/nanahoshi/audioplayer/bilibili/BilibiliClient.kt#L615) 续期校验会话代号、[Daos.kt:720](../../app/src/main/java/com/nanahoshi/audioplayer/data/db/Daos.kt#L720) 发布恢复剥离大载荷防 CursorWindow 溢出。
   - 修复 [YouTubeClient.kt:389](../../app/src/main/java/com/nanahoshi/audioplayer/youtube/YouTubeClient.kt#L389) 异常吞没、[JaWorkClient.kt:510](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JaWorkClient.kt#L510) 成功下载被删、[LibraryRepository.kt:1766](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt#L1766) JA 附件来源误判（及另 4 处同型绕过）、[AudioPcmReader.kt:151, 175](../../app/src/main/java/com/nanahoshi/audioplayer/data/AudioPcmReader.kt#L151) 解码格式变化与位深忽略。
   - 批量将关键路径裸 `error()`/`check()` 切换为 `UserFacingException(TextRef.Res(...))`；同步在 [rules.keep](../../app/src/main/keepRules/rules.keep) 补齐 `sherpa-onnx` 配置数据类字段保留规则。

2. **Phase 1 — 稳态边界加固与测试止腐**
   - 修复全部 **29 项 P2 与 1 项 P3 边界缺陷**：
     - 下载与仓储（12 项）：SQLite 999 参数分批、取消/重试尝试令牌隔离、YouTube 独立暂存、DLsite 416 处理与原包复用长度校验、`hasStoredCopy` 非零可读校验、HLS 损坏分片失效、JA 直链正式 GET 防 HTML 误发布与 `.version` 短文件名、`importTree` 整树事务。
     - 听写/字幕/播放/认证（18 项）：`WhisperCenter` 并发 `stageContent` 原子化与透传 `requestedAt`、`QwenCues` 校验 `transcripts[].text`、`SubtitleSource` 保留 SAF 树权限与取消 512 KiB 截断、`SubtitleBindingStore` 持久授权日志对账、`SubtitleOffsetWriter` 提前登记在途目标、`PlaybackService` 3 项冷启动/位置/重试修复、`MediaItemFactory` B 站本地失效回退在线、`BiliPlaybackDataSource` 失败关闭 `fallback` 与 416 EOF、`PlayerClient.init` 异常传播、`BilibiliClient` `-412` 冷却与旧 Token 重试作废、`SubtitleCues` LRC `"-->"` 误判。
   - 删除模板/100% 重复测试，迁出 `TaskProgressPreviewAndroidTest`，升级 `espresso-core` 并改用 `R.string.*` 修复 4 个已失效的 UI 测试；抽离 `TestDb`（修复 `player_prefs` 污染）与 `TestFixtures`，补齐解压安全、DLsite 试听禁 Cookie、`MIGRATION_1_2~11_12`、`WhisperCenter`/`TranslateCenter`/`PlaybackService` 核心链路测试。

3. **Phase 2 — 高 ROI 基建整合与性能优化**
   - `AppGraph` 统一共享根 `OkHttpClient`；落地 `HttpSupport.kt`（流式下载、重定向、ByteRange、`Call.withResponse`）与 `DataSyncForegroundService` 基类。
   - 将 `SubtitleCues`、`TranslateClient`、`WhisperClient`、`Qwen*`、`AsmrClient`、`DlsiteClient` 迁移至 `kotlinx.serialization.json` 与 `jsoup`。
   - 落地共用 UI 组件（`ConfirmActionDialog` 扩展、`ModalSheetHeader`、`SecretTextField`、`ImmersiveWorkScaffold`、`SettingsComponents`、`playStoredAudio`），消除主线程 `File.isFile`/`canonicalFile`、未 `remember` 的 Room `Flow`、`O(N×M)` 重组分配、`JaMegaCrypto.verify` 1 MiB 循环分配及 `DlsiteClient.productCards` N+1 扇出。
   - 清理 23 个死 DAO 查询、`WorkFactsBlock.kt`、`sherpa-onnx` 1,292 行死示例及 110+ 无用导入（并决定 `BadgeLine` 去留）。

4. **Phase 3 — 大文件按域拆分**
   - 按领域边界拆分 `OnlineScreens.kt`、`LibraryRepository.kt`、`DownloadCenter.kt`、`LibraryViewModel.kt`、`PlayerChrome.kt`、`SubtitleSource.kt`、`WhisperCenter.kt`、`BatchSubtitleMatch.kt`、`CredentialStore.kt`、`Daos.kt` 与 `AppDatabase.kt`（抽离 `Migrations.kt`）。
