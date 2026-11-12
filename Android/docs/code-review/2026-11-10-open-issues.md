# 已知未修复问题清单

- 整理日期：2026-11-10；逐项复核：2026-11-12（基线 `f4d84aa`，含 11-11 改动；下行号均为当前实际行号）
- 范围：Android 工程，当前 main 工作树
- 说明：只列**尚未修改**的问题；本轮已修的 7 项「数据不一致 / 卡死」缺陷见同目录 `2026-11-10-review.md`，更早的 16 项与合集、确认弹窗等修复不再重复。2026-11-12 再修 6、8、9、12 四项，记录在对应章节；新增 13 个 JVM 用例，全套 297 例通过，另新增 3 个 androidTest（沙箱内仅编译验证，未上真机）。同日再修 19、23、25、27 四项，新增 23 个 JVM 用例，全套 320 例通过，另新增 CredentialStoreAndroidTest 并扩展 LocalRemovalAndroidTest（仅编译）
- 2026-11-12 二次核对：15 项未修问题逐条对照当前代码确认仍存在；第 8 项修复已落地（`CollectionFailureDialog`），删除第二节残留条目；修正 10、13、15、26 及第七节漂移行号
- 2026-11-12 三次修复：7、10、11 三项修复，22 更正为误报并做相邻加固；新增 6 个 JVM 用例（LibraryClimbTest 5、ExternalFileDownloadTest 1），全套 326 例通过；新增 MediaItemFactoryAndroidTest 3 例、BiliCollectionUiAndroidTest 1 例（仅编译）。未修余 11 项：13、14、15、16、17、18、20、21、24、26、28
- 2026-11-12 四轮（独立 diff 审查 13 项，全部处理，与本清单编号无关）：进度写串行化（limitedParallelism）、RJ 改字符串身份、零长 file: 校验、contentUriReadable 复用、subtitleItem 合并；PlayingBiliCollection 协程泄漏（coroutineScope）、搜索分页提交后前进、孤儿覆盖身份找回（recoveredOverlayIdentities）、YouTube parsedResponse 重试判定、翻译表按需加载（overlayCandidates）、translationOverlay 标记分派、cueFiles 合并；末三项：翻译删除只清终态记录（deleteInactiveFor*，保留进行中，与听写一致）、服务恢复异步化（withQueueLock＋锁内快照重读）、队列锁外预探测（biliLocalCache 两段拆分）。新增 5 个 JVM 用例＋8 个 androidTest，JVM 全套 331 通过；真机 RMX5010 本轮相关仪器测试全过，25 个 Compose UI 测试因 Espresso/Android 17 不兼容统一失败（环境问题，需升级 espresso），2 个预存失败（ExternalFile 缓存断言与 journal 保留冲突、DlsiteOriginalStore 冲突跳过）与本轮无关；另修 LocalRemovalAndroidTest 非 void 方法声明。本清单未修 11 项不变
- 标记：`已复核` = 对照当前代码确认仍存在。原第五节 8 项「待复核」已于 2026-11-12 全部确认，编号 21–28
- 路径均相对仓库根目录的 `Android/app/src/main/java/com/nanahoshi/audioplayer/`

## 一、播放与网络（2026-11-12 已全部处理，本节暂无未修问题）

- 原 1、2、3、5 项已修复，新增 9 个 JVM 用例，全套 284 例通过：
  - **1 合集补查按需运行**：`playback/PlayingBiliCollection.kt` 改 `WhileSubscribed(5_000)`，内部 `linkState`（MutableStateFlow）的 `subscriptionCount` 门控发布与补查；无订阅（Activity 级实例）不发请求，离开播放页即停。
  - **2 YouTube 认证重试**：`youtube/YouTubeClient.kt` 重试集合加入 `""`（匿名无确定响应也带 Cookie 重试），通用 catch 不再清 `lastStatus`。
  - **3 Qwen3 模型选择**：`data/WhisperModels.kt` 以 `resolveQwen3Model` 替换 `qwen3AsrModelId`，多模型无匹配在上传前明确失败；单模型自定义名与空列表行为不变。
  - **5 播放状态去重**：`playback/PlaybackService.kt` `saveProgress` 相等去重，暂停/静止/无媒体不再每 5 秒重复写；保留 `(-1, 0)` 复位写入（清空队列后新建队列再杀进程，恢复不会误用旧进度）。
- 原第 4 项（队列重建后不恢复播放）2026-11-12 用户真机确认无需改动，移入第六节。

## 二、B 站合集（2026-11-12 已全部处理，本节暂无未修问题）

- 原 6、7、8 项已修复：
  - **6 合集准备切 IO**：`data/BiliCollectionRepository.kt` `stored()` 整体包 `withContext(Dispatchers.IO)`，整集播放、离线分 P 与单曲准备的文件检查不再阻塞 Main；行为由既有 androidTest 覆盖。离线分支 `details()` 与 `playable()` 的双重查询留作后续优化。
  - **8 失败弹窗区分全部失败**：`ui/bilibili/BiliCollectionScreen.kt` 抽出 `CollectionFailureDialog`，全部失败时标题改「视频读取失败」，不再显示禁用的「继续处理其余 0 个视频」；新增 2 个 Compose androidTest。
  - **7 合集路由错误页**：`ui/bilibili/BiliCollectionScreen.kt` 新增 `BiliCollectionRouteError`（AppTopBar + EmptyState，标题「无法打开这个合集」），`MainActivity.kt` 的 `ref` 解码失败分支渲染错误页，顶栏与按钮均可返回；新增 1 个 Compose androidTest（仅编译）。
## 三、界面与状态（9、10、11、12 已于 2026-11-12 修复，本节余 13、14）

- 原 9、10、11、12 项已修复：
  - **9 busy 改操作计数**：`ui/library/LibraryViewModel.kt` 新增 `BusyCounter`，各入口 begin/end 配对、归零才复位；去掉 openFolder/useWorkSource 的 generation 守卫与 `markWorkMissing` 的强制清零，顺带修复 `up()` 取消 openJob 后 busy 卡 true。新增 4 个 JVM 用例。
  - **12 翻译覆盖纳入下载管理**：`playback/SubtitleSource.kt` 抽出 `overlayFileName`、新增 `discardTranslationRecords`；`data/StoredFiles.kt` 字幕分类按翻译任务记录反查列出 `extra-` 文件（孤儿按原始文件名列出），删除走文件优先、只清翻译记录不动听写；`TranslationDao` 新增 `all()`（不过滤状态）。新增 9 个 JVM 用例与 1 个 androidTest。
  - **10 climb 超时即停**：`ui/library/LibraryRows.kt` 抽出 `climbUp`，`withTimeoutOrNull` 超时即停止剩余步数，不再越过目标层级；顺带删除 `LibraryScreen.kt` 未使用的 import。新增 5 个 JVM 用例。
  - **11 content: 可读性校验**：`playback/MediaItemFactory.kt` 的 `content:` 分支按 `hasLocalPart` 惯例用 `openAssetFileDescriptor` 校验，失效时回退 `bilibili://` 在线音轨；工厂增加 Context 参数，`PlayerClient` / `PlaybackService` 共 9 个构建点挪到 IO（避免 Main 上 IPC）。新增 3 个 androidTest（仅编译）。

13. **下载管理 GENERATED 先清记录再删文件**（已复核，可能有意）— `data/StoredFiles.kt:280-283`：普通生成字幕分支仍是 `discardStored` 之后才 `file.delete()`，且不检查删除结果，删除失败时记录已清；`extra-` 翻译覆盖分支（:276-279）已随 12 项改为文件优先。若顺序是有意设计则无需改。
14. **缓存页操作无异常兜底**（已复核，低危）— `ui/settings/CacheScreen.kt:120-132` 的 `refresh()` / `setLimit()` 直接调用 `cache.usage()`，无 try/catch（`clear()` 有），抛异常时无人捕获。

## 四、低危残留（19 已于 2026-11-12 修复，本节余 15-18、20）

- 原 19 项已修复：
  - **19 SenseVoice 删除守卫**：`data/WhisperModels.kt` 新增 `hasActiveSenseVoiceJob`（与运行分发一致，空模型任务不计入），`WhisperDao.active()` 查排队/进行中任务；`ModelSettingsViewModel.deleteSenseVoice` 命中时提示「有听写任务正在使用本机模型，请先中止后再删除」，不自动中止任务。新增 1 个 JVM 矩阵用例。

15. **`saveTranslations` 先写文件后标记**（已复核）— `playback/SubtitleSource.kt:122-132`：写盘已原子化（:127 写文件、:128 标记），但进程在写文件与 `markTranslated` 之间结束时，会出现字幕已含译文、任务状态未更新。
16. **`TranslateCenter.readGenerated` 用宽松解析**（已复核）— `data/TranslateCenter.kt:247-251` 用 `parseGeneratedCues`（`playback/SubtitleCues.kt:34`，即 `OrNull().orEmpty()`）；损坏的生成字幕返回空列表，`:98-99` 显示「没有需要翻译的日文」，而不是损坏提示。
17. **`moveItems` 无调用方且无事务**（已复核）— `data/LibraryRepository.kt:407-416`：无事务、不校验作品边界（对比 `deleteItems` 有 `LibraryRemovalPolicy`），全仓库无调用方，属于死代码。
18. **`canonicalWorkKey` 解析失败回退裸值**（已复核）— `data/DownloadCenter.kt:736-740`：回退值经 `enqueueRemote:273,292` 落库，在 `remoteDestination:717` 直接作为路径段使用。`canonicalWorkno` 本身几乎不抛异常（正则不匹配时返回大写原值），属防御性残留。
20. **`retry(id)` 没有任务状态守卫**（已复核）— `data/DownloadCenter.kt:427-433` 只检查运行中的协程；DAO `requeue` 也无状态条件（`data/db/Daos.kt:285-286`），依赖调用方只对 FAILED 显示重试（`ui/download/DownloadScreen.kt:243-245`）。

## 五、原「待复核」项（23、25、27 已于 2026-11-12 修复，22 确认为误报，余 21、24、26、28）

- 原 23、25、27 项已修复：
  - **23 作品搜索分页续读**：`ui/asmr/OnlineScreens.kt` 按来源独立记页（`SourcePaging`），失败保留页码与 hasMore、只有成功才前进；「重试」改续读失败页，不清空列表；触底自动加载加 error 守卫，失败后不自动续读；两来源读完但合并阶段异常时仍允许重试。新增 7 个 JVM 用例。
  - **25 B 站关联删除对齐**：`data/LocalRemoval.kt` 去掉 ASSOCIATION 的 BILIBILI 排除，content:/file: 关联与 ASMR/DLSITE 一致只取消关联，用户文件、字幕与视频字幕缓存保留。新增 9 个 JVM 用例并扩展 androidTest。
  - **27 票据过期与重登复位**：`data/CredentialStore.kt` 抽 `ticketIsFresh`（60 秒裕量），cookieHeader 不再发送过期 ticket；saveBiliSession 同时清 CHECKED，扫码重登后立即重新判断续期需求。新增 6 个 JVM 用例与 CredentialStoreAndroidTest。

21. **UP 主没有任何合集时报错而非空状态**（代码确认，触发待联网验证）— `bilibili/BiliCollections.kt:66` 强制要求 `page.total`，`:38-39` 强制要求两个数组；缺字段抛「合集分页信息缺失」/「B 站没有返回合集列表」，由 `BiliPagedState` 显示错误。服务端在 UP 主无合集时是否省略这些字段未联网验证（测试 fixture 均含 `page.total`）；若返回 `total=0` 与空数组则正常显示空。
24. **普通下载取消时提示「下载失败」**（残留写法，触发路径已无）— `data/DownloadCenter.kt:552` else 分支无「已中止」映射；但「中止」按钮只对 YouTube / 完整原始包显示（`ui/download/DownloadScreen.kt:239-242`），`cancel(id):113-118` 也只处理这两类，普通下载没有取消入口，实际不可触发。
26. **DLsite Cookie 未过滤换行；asmr `order` / `sort` 未 URL 编码**（已复核；后者无实际影响）— `dlsite/DlsiteCookies.kt:10-23` 只 trim 两端，内部换行不过滤（对比 B 站 `data/CredentialStore.kt:363` 有过滤 `\r\n`）；`asmr/AsmrClient.kt:157` 直接插值，但值来自固定 `OnlineSort` 枚举（`ui/asmr/OnlineScreens.kt:185-197`），全是 URL 安全常量。
28. **B 站分页在服务端 `has_more=false` 但整页时多发一次请求**（已复核，限收藏夹）— `bilibili/BilibiliClient.kt:257` `hasMore == true || medias.size >= PAGE_SIZE`。搜索分页无此写法（`bilibili/BiliSearch.kt:131` 按 count、`mergeBiliPage:159` `hasMore && added.isNotEmpty()`）。

## 六、已决定不修 / 搁置（2026-11-12 复核，均维持原状）

1. **更新检查的 APK 回退** — 按决定忽略：`data/AppUpdate.kt:78-83` 仍要求已上传 APK，缺失即抛错，不回退旧发布。
2. **DLsite Play 凭证主机白名单** — 搁置，另行处理：`dlsite/DlsiteClient.kt:80-81` 与下载所用 `asmr/AsmrClient.kt:110-116` 均跟随重定向；`signedPlayFileBlocking:341-345` 的 URL 来自签发响应，`data/DownloadCenter.kt:580,589` 把合并后的登录 Cookie 发往该主机，无白名单。风险为潜在，尚未命中。
3. **`DlsiteDownloadRequests` 持锁等待用户** — 与 B 站入库弹窗同型，尚未修改：`data/DlsiteDownloadRequests.kt:29-40` 的 `lock.withLock` 包住 `reply.await()`，第二个调用方会静默排队。
4. **队列重建后不恢复播放** — 2026-11-12 用户真机确认无需改动：`playback/PlayerClient.kt:326-337` 保持只 `prepare()`，Media3 的 `playWhenReady` 跨 `setMediaItems` 保留，删除曲目后自动续播。

## 七、此前记录但当前代码已不复现 / 误报（2026-11-12 复核）

- 「字幕末尾被截 1ms」：`data/WhisperModels.kt:113` 现为 `if (end > start) end else start + 1`，没有截断。
- 「收藏夹列表吞 `CancellationException`」：`ui/library/FavoriteScreen.kt:113-114` 已重新抛出。
- 「`deleteDownloads` 只报 `deleted` 忽略 `unlinked`」：现用 `deleteDownloadedNotice`（`ui/library/LibraryViewModel.kt:994-1000`），deleted / unlinked / changed 三者都提示。
- 「删除本地文件只凭 `startsWith("/")`」：现用 `localRemoval(filesDir)`（`data/LocalRemoval.kt:20-30`），以 `canonicalFile.startsWith(filesDir.canonicalFile)` 限定 App 目录。
- 「`"RJ${file.asmrWorkId}"` 拼出 `RJnull`」（原 22）：**误报**——`LibraryFileEntity.asmrWorkId` 为非空 `Long`（`data/db/Entities.kt:118`），不可能拼出 `RJnull`；原报告混淆了可空的 `TrackEntity.asmrWorkId`（`:91`）。已做相邻加固：`ui/library/FilePreviewScreen.kt:61` 回退加 `takeIf { it > 0 }` 守卫（不再拼出无效 `RJ0`）；`data/ExternalFileSession.kt:100-101` 改抛 `ExternalFileException`，授权缺失时显示「请重新打开作品以获取文件授权」而非被包成泛化提示。新增 1 个 JVM 用例。
