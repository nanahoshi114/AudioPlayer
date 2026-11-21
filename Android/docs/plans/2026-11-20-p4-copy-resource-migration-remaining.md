# 完成 P4 第二、三批：剩余数据层文案资源化

> 本文承接 `2026-11-20-p4-copy-resource-migration.md`。第一批及大部分第二/三批已完成，本计划只覆盖剩余部分。

## Context

P4 第一批及大部分第二批/第三批已完成：`ui/` 下用户可见中文字面量已清零，`TextRef`、`UserFacingError`、`PersistedErrors` 已建立，核心 `data` 中心与主要客户端已迁移，453 个单元测试通过。

`rg` 复测仍有 637 行含中文字面量，其中 `DlsiteGenres.kt` 占 320 行；其余 **317 行**（先前按 318 行计）：**约 274 条用户可见、33 条数据库/控制流保留、11 条模型提示词**（另有三引号 prompt 33 行）。主要集中在 DLsite、Japanese ASMR、翻译/听写、`BiliPlaybackDataSource`、前台服务通知和少量播放/合集代码。上一轮只把它们放进了 `UserFacingError.userText`，但仍是 `TextRef.Raw` 中文。

本轮目标：把剩余用户可见文案迁入资源，并在迁移前先拆掉“按中文文案做分支”的耦合，避免出现 `res:#123` 写入数据库、错误分类失效或 UI 直接显示调试串。

## 审查修订（2026-11-21）

本轮审查建议全部采纳，不驳回。合并时按现有实现细化为以下 8 条，避免按原稿实施后改变现有行为：

1. **DP1：持久化错误分类。** 新增 `DlsiteArchiveFailureKind`，在写库前把可续传中断映射为固定哨兵 `PersistedErrors.DLSITE_ARCHIVE_INTERRUPTED`；`failureResult` 的精确集合必须包含该值，并保留对旧句子的只读兼容。独立翻译的取消、超时和中断也写固定哨兵，`abandon` 不以展示文本作为持久化值。
2. **DP2：播放错误过滤。** `playbackErrorMessage` 仍在最终展示文本上过滤 `://`、`Source error` 和 `androidx.media3`；不能只检查 `TextRef.Raw` 或资源 ID。服务端返回的正文继续走 `TextRef.Raw`，但在展示/写库边界统一过滤，防泄漏断言落在最终字符串上。
3. **DP3：B 站合集标题回退。** 保留 `BiliCollectionKind` 的持久化标题回退，收藏、刷新、列表卡片和复制默认名称都继续得到非空标题；只迁移独立展示的类型标签 `labelRes`。
4. **DP4：可编译分包。** WP0 拆为 WP0-A（异常签名、控制流、DB/UI 边界）和 WP0-B（进度 detail 类型及全部调用方）。每个包都包含所需的资源、全部调用方和断言，单独跑编译与受影响单测。WP0-A 提前创建 `strings_japaneseasmr.xml` 中的 `JaFailure` 资源，避免 WP3 才补齐资源导致 WP0 无法编译。
5. **DP5：翻译返回的双重语义。** `TranslateReply` 同时保留 `error: TextRef?` 与 `raw: String?`：前者是网络/配置/截断提示，后者只放裁剪后的模型原文。格式错误统一标题，但仍允许在失败详情中展示清洗后的模型片段；`TextRef.Joined` 显式使用换行分隔符。
6. **DP6：阶段名参数。** `TextRef.Res` 的参数必须嵌套 `TextRef.Res(stageRes)`，不能传整数资源 ID；资源占位符使用 `%1$s`。验证、地址失效、入口变化分别保留等待验证、重新解析和重选入口的后续动作。
7. **DP7：扫描与白名单。** `ui/` 扫描是回归门禁而非发现命令；`$` 模板检查先剔除 `%N$s`/`%N$d`。DLsite/asmr.one 的「只显示前面一段」属于预览提示，不在内容回退名白名单内，复用现有 `library_file_truncated`。
8. **DP8：验收口径。** 以格式参数、实际展示文本和数据库结果验收；单元测试数量只作为基线，不作为唯一门槛。设备测试和 360 dp 截图复核单列。

已确认：

- 评审 6.5 保持现状：说明句可用「你」，按钮/标签省略主语，全站不用「您」。
- 不建立 `values-en`。
- 不改 Room schema/版本、SQL 哨兵值、`DlsiteGenres.kt`。
- 不迁移模型提示词、软违规反馈和内容数据。
- 不提交、不推送。

## 目标与非目标

### 目标

1. 剩余用户可见错误、状态、通知、进度详情、桌面字幕、服务文案全部使用 `stringResource` / `getString` / `TextRef.Res`。
2. 为 DLsite、Japanese ASMR、asmr.one、播放数据源补齐 `TextRef` 异常主构造器，保留现有 `String` 次级构造器，避免大范围改测试构造。
3. 拆掉中文文案分支：`contains("登录")`、`when (message)`、`"已中止"`、`startsWith("文件 ")` 等改为类型、状态码、枚举或结构化字段。
4. 所有写入 Room `error` 列的异常文案必须是持久化哨兵或已解析的展示文本：取消、超时、中断、验证、入口变化等控制流错误写固定哨兵；不落资源 ID，也不把展示资源文本当作控制流依据。
5. 更新受影响的 JVM 测试和仪器测试编译；补充关键回归断言。每个工作包必须能独立编译，并包含所需的资源、全部调用方和断言。

### 非目标

- 不做英文翻译，不建 `values-en`。
- 不改变用户可见的任务结果和下载、听写、翻译、播放行为；错误分类改为类型或固定哨兵，但结果保持一致。
- 不迁移 `DlsiteGenres.kt`、`PersistedErrors` 常量、`Daos.kt` SQL 哨兵。
- 不迁移模型 prompt、违规反馈、模型返回原文、日志、注释、`testTag`。
- 不迁移会写入标题/文件名/DB 的“内容回退名”，见白名单。

## 迁移边界

### 必须迁移

| 类别 | 代表 |
| --- | --- |
| `Exception` / `IOException` 文案 | DLsite、JA、asmr.one、B 站合集、YouTube 签名、播放数据源 |
| 面向用户的 `error/check/require` 文案 | 解压、合集、原始包、HLS（内部参数校验见白名单） |
| 通知标题/正文/动作 | 音频下载、字幕任务、模型下载 |
| 进度 detail / 阶段说明 | 离线听写解码、人声检测、JA 分片/文件计数 |
| 非 Compose 界面 | `DesktopSubtitleWindow` 的按钮与无障碍描述 |
| 服务/消息总线提示 | `PlaybackService`、`OfflineModelDownloadService`、`AppGraph` |
| 标签枚举 | `TranslatePlatform`、`TranslateEffort`、`WhisperModel` 显示名、B 站合集/系列 |
| 预览截断提示 | DLsite、asmr.one 的 `library_file_truncated` |
| 持久化控制流值 | 取消、超时、中断、验证、入口变化、文件缺失的固定哨兵；只写固定值，不写资源文本 |
| UI 兜底错误状态 | `BiliPagedState`、`BiliCollectionRepository`、`SubtitleSource` |

### 保留（白名单）

| 类别 | 位置 |
| --- | --- |
| 数据库/控制流哨兵 | `text/PersistedErrors.kt`、`Daos.kt`、`JaWorkClient.kt:26,32`、`AsmrClient.kt:20-21` 的旧常量、服务内的常量别名；新增 `DLSITE_ARCHIVE_INTERRUPTED` 与旧的 `JA_*`、`DOWNLOAD_*` 值 |
| 模型 prompt / 软违规反馈 | `TranslateClient.kt:141-150,189-196,197,237-245,272-275,300-310`、`OfflineRecognizer.kt:69` |
| 翻译结构诊断（仅内部） | `TranslateClient.kt:117-155` 的 `structuralError`；用户看到统一标题，失败详情可附清洗后的模型片段 |
| 内容回退名（写入标题/文件名或作为搜索别名） | `DlsiteClient.kt:769,813,917`、`AsmrClient.kt:387`、`JapaneseAsmrParse.kt:174`、`JaWorkFiles.kt:386`、`BilibiliClient.kt:243`、`LocalAudio.kt:36,50,73`、`EntryNames.kt:60`、`StoredFiles.kt:448,562,644,645` |
| B 站合集持久化标题回退 | `BiliCollections.kt:94` 的 `fallback.label`；收藏、刷新和复制默认名称依赖非空标题，本轮保留该字符串，只迁移独立展示标签 |
| 服务器返回正文 | `BilibiliClient.kt:746`、`ApiResponse.detail(TextRef)` 等；保留 `TextRef.Raw`，在最终展示/写库边界过滤地址 |
| 内容/调试/技术文本 | 注释、KDoc、日志、英文技术异常、服务器返回正文 |
| 内部 `require/check` 校验 | `QueuePlayable.kt:15`、`BiliLibraryImport.kt:19-20`、`BiliLibraryStore.kt:23,48`；保留 `IllegalArgumentException/IllegalStateException`，不强行改类型 |
| 站点内容数据 | `DlsiteGenres.kt`、服务端返回的标题/简介/错误正文 |

预览截断提示不在此白名单：`DlsiteClient.kt:381`、`AsmrClient.kt:267` 的 `"\n…（只显示前面一段）"` 是用户可见提示，复用现有 `R.string.library_file_truncated`。客户端返回裁剪标记，由 UI 边界追加资源文本，避免把展示文案混入内容数据。

这些回退名会随“未命名作品”“试听”“下载文件”等进入曲目标题、文件夹名或搜索别名；改资源需要改写入边界并兼容旧数据，但不改 schema，本轮不做。

## 总体设计

### 1. 异常类型统一

在 `text/UserFacingError.kt` 追加：

```kotlin
class UserFacingIOException(
    override val userText: TextRef,
    cause: Throwable? = null,
) : IOException(userText.debugString(), cause), UserFacingError
```

为以下异常增加 `TextRef` 主构造器和 `String` 次级构造器，保留原有字段和行为：

```kotlin
enum class DlsiteFailureKind { AUTH, OTHER }
enum class DlsiteArchiveFailureKind { INTERRUPTED, INTEGRITY, UNAVAILABLE, OTHER }
enum class AsmrErrorKind { NOT_FOUND, INVALID_RJ, OTHER }

class DlsiteException(
    override val userText: TextRef,
    internal val statusCode: Int? = null,
    internal val kind: DlsiteFailureKind = DlsiteFailureKind.OTHER,
    cause: Throwable? = null,
) : Exception(userText.debugString(), cause), UserFacingError {
    constructor(message: String, statusCode: Int? = null) : this(TextRef.Raw(message), statusCode)
}

open class DlsiteArchiveException(
    override val userText: TextRef,
    internal val unavailable: Boolean = false,
    internal val archiveKind: DlsiteArchiveFailureKind = DlsiteArchiveFailureKind.OTHER,
    cause: Throwable? = null,
) : IOException(userText.debugString(), cause), UserFacingError {
    constructor(message: String, unavailable: Boolean = false) : this(TextRef.Raw(message), unavailable)
}
```

`DlsiteArchiveException` 保留 `unavailable` 兼容字段；新代码以 `archiveKind` 为准，旧调用点传 `unavailable = true` 时可同时映射为 `UNAVAILABLE`。`DlsiteArchiveClient.kt:316` 的「压缩包下载中断，可重试继续下载」改为
`DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_interrupted), archiveKind = INTERRUPTED)`；
`DownloadCenter` 在写库前把它映射为固定哨兵，而不是写入资源文本或依赖 `.message` 内容。

`PersistedErrors` 增加：

```kotlin
const val DLSITE_ARCHIVE_INTERRUPTED = "压缩包下载中断，可重试继续下载"
const val DLSITE_ARCHIVE_FAILED = "完整原始包下载失败，请重试"
const val TRANSLATE_SAVE_FAILED = "无法保存译文，请重试"

fun isCancelled(raw: String?): Boolean = raw == CANCELLED
fun isInterrupted(raw: String?): Boolean =
    raw in INTERRUPTED_VALUES || raw?.let { it.contains("中断") || it.contains("后台") } == true

private val INTERRUPTED_VALUES = setOf(
    DLSITE_ARCHIVE_INTERRUPTED,
    AUDIO_DOWNLOAD_INTERRUPTED, AUDIO_DOWNLOAD_START_FAILED, AUDIO_DOWNLOAD_TIMEOUT,
    SUBTITLE_INTERRUPTED, SUBTITLE_START_FAILED, SUBTITLE_TIMEOUT,
)
```

精确集合覆盖现有已知值；`contains("中断")` / `contains("后台")` 只用于读取旧版本已落库文本，禁止用于新写入分支。`persistedErrorText` 同时映射 `DLSITE_ARCHIVE_INTERRUPTED` 和 `DLSITE_ARCHIVE_FAILED`。

同一模式处理：

- `DlsiteArchiveIntegrityException`（构造时传 `archiveKind = INTEGRITY`）
- `JaWorkException`
- `JaWorkAddressExpired`（保留 `sessionRequired`）
- `JaBuzzVerification`
- `AsmrException`（增加 `kind: AsmrErrorKind`）
- `LibraryRemovalRejected`（改为接收 `TextRef`，无参次构造器默认 `R.string.library_removal_rejected`）

`JaDownloadVerification` 的 `message` 保持 `PersistedErrors.JA_VERIFY` 供控制流使用，`userText` 改为 `TextRef.Res(R.string.download_status_waiting_verification)`。

`BilibiliException` / `YouTubeException` / `WhisperException` 已有 `TextRef` 主构造，不改签名，只改 throw 点。

### 2. `JaFailure` 改为资源

```kotlin
internal enum class JaFailure(@param:StringRes val messageRes: Int) {
    VERIFY(R.string.ja_error_verify),
    ...
    WORK_FILE_MISSING(R.string.ja_error_work_file_missing),
}

internal class JapaneseAsmrException(
    val failure: JaFailure,
    val verificationPage: String? = null,
) : IOException(failure.name), UserFacingError {
    override val userText = TextRef.Res(failure.messageRes)
}
```

规则：

- `message` 只用于日志/异常链，不再被 UI 读取。
- `OnlineScreens.kt:940`、`JapaneseAsmrVerificationScreen.kt:110/112/361`、`ClipboardImportViewModel.kt:82/86` 改用 `userText` 或 `messageRes`。
- `JaFailure.WORK_FILE_MISSING` 的展示资源与 `PersistedErrors.JA_WORK_FILE_MISSING` 指向同一句，但数据库仍使用常量。

### 3. 控制流解耦（先于文案迁移）

| 位置 | 现状 | 改为 |
| --- | --- | --- |
| `DlsiteClient.kt:410` | `error.message?.contains("登录")` | 认证类错误统一 `kind = AUTH`（同时保留 `statusCode = 401`），`sign()` 判 `kind == AUTH` |
| `AsmrClient.kt:179` | `when (error.message)` 匹配中文哨兵 | `AsmrException.kind`：`NOT_FOUND` / `INVALID_RJ` / `OTHER` |
| `OnlineScreens.kt:759`、`LibraryViewModel.kt:367` | `failure.message == ASMR_WORK_NOT_FOUND` | `failure.kind == NOT_FOUND` |
| `TaskProgressCenter.kt:169-170` | `"已中止"`、`contains("中断")`、`contains("后台")` | `PersistedErrors.isCancelled/isInterrupted`；精确集合 + 旧文本兼容 |
| `TaskProgressDisplay.kt:162` | `detail.startsWith("文件 ")` | `detail.resIdOrNull() == R.string.progress_detail_files` |
| `DownloadCenter.kt:1216/1219` | 把 `.message` 写入 DB | 先按异常类型映射固定哨兵；其余 `resolve(userTextOr(...))`，并在写入前做地址过滤 |
| `BiliBrowserViewModel.kt:99`、`LibraryViewModel.kt:1007` | 直接读 `BilibiliException.message` | `error.userText` 后在 ViewModel 边界 `resolve` |
| `BiliCollectionRepository.kt:96` | `.message` 兜底 | `(error as? BilibiliException)?.userText ?: TextRef.Res(...)` |
| `FilePreviewScreen.kt:310` | `ExternalFileException.message` | `context.resolve(error.userText)`；该回调不是 Composable，不能用 `asString()` |
| `ModelSettingsScreen.kt:326` | `WhisperException.message` | ViewModel 中调用 `graph.show(error.userText)`；不能调用 Compose 的 `asString()` |
| `PlayerChrome.kt:244` | `error.reason.message`（JaFailure） | `stringResource(error.reason.messageRes)` |
| `PlaybackService.kt:106/110/149/177` | JaFailure / 异常 `.message` 直接展示 | JaFailure 用 `getString(messageRes)`；其余按第 7 节过滤后展示 |
| `WhisperCenter.kt:787/813/864`、`TranslateCenter.kt:237` | 任意异常 `.message` 写 DB | 控制流错误写固定哨兵；其余 `resolve(userTextOr(...))` 并做地址过滤 |
| `WhisperStart.BackgroundUnavailable`、`WhisperCenter.retry()` | 失败文案为 `String` | 改 `TextRef` / `TextRef?`，UI 用 `graph.show` |

`DownloadCenter.kt:1215` 的 `DOWNLOAD_LIBRARY_REQUIRED`、`:1218-1222` 的 JA 已落库值属于持久化兼容，保留常量比较；只在展示/写入边界映射。`LibraryViewModel.kt:698` 的书名号拼接摘要另加 `library_ja_refresh_line` 资源。

写库前按以下类别处理，不能一律 `resolve(userText)`：

| 异常/来源 | 写入值 |
| --- | --- |
| `DlsiteArchiveException.archiveKind == INTERRUPTED` | `PersistedErrors.DLSITE_ARCHIVE_INTERRUPTED` |
| `DlsiteArchiveException` 其他 | 固定哨兵或 `resolve(userText)`；保留旧值兼容 |
| `JapaneseAsmrException(JaFailure.WORK_FILE_MISSING)` | `PersistedErrors.JA_WORK_FILE_MISSING` |
| `JaDownloadVerification` / `JaBuzzVerification` 进入等待验证 | `PersistedErrors.JA_VERIFY`，同时写入 `VERIFY` 阶段 |
| `JaWorkAddressExpired` 重新解析后仍失败 | `resolveNoUrl(userText, fallback)` |
| `JaWorkException` 入口变化 | `PersistedErrors.JA_ENTRY_CHANGED` |
| 取消 / 超时 / 服务中断 | `PersistedErrors.CANCELLED` / `*_TIMEOUT` / `*_INTERRUPTED` |
| 其他用户可见异常 | `resolve(userTextOr(...))`，并通过 `Context.resolveNoUrl` 过滤地址 |

在 `text/TextRefAndroid.kt` 增加统一边界：

```kotlin
fun Context.resolveNoUrl(text: TextRef, @StringRes fallback: Int): String {
    val value = resolve(text)
    return if ("://" in value) getString(fallback) else value
}
```

该函数在解析后检查嵌套参数，覆盖 `TextRef.Res` 参数里携带地址的情况；`userTextOrNoUrl` 仍保留给只持有异常、没有 Context 的旧调用点，但不能作为最终展示/写库的唯一防线。

### 4. 翻译错误

`TranslateClient` 通过返回值传递配置、网络和格式失败。协程取消仍通过异常传播。

```kotlin
private data class Completion(val content: String? = null, val error: TextRef? = null)
class TranslateReply(
    val lines: List<String>? = null,
    val error: TextRef? = null,
    val raw: String? = null,
)
internal class RevisedTranslationReply(
    val cues: List<SubtitleCue>? = null,
    val violations: List<String> = emptyList(),
    val error: TextRef? = null,
    val raw: String? = null,
)
internal fun translationFailureDetail(
    error: TextRef?,
    raw: String?,
    redactedLabel: String,
): TextRef
```

- 网络/配置错误（335/338/371/376/381/385）改为 `TextRef.Res`。
- 结构错误（117-155）保留内部 `structuralError: String` 作为诊断；`error` 统一为 `R.string.translate_error_format_retry`。失败详情仍可附模型原文，但任务徽章只显示统一标题。
- `violations`、prompt、协议字段保持 Kotlin 常量。
- `TranslateReply.raw` 只保存裁剪后的模型内容；`error` 单独保存错误提示，不再把两者混在 `raw`。
- `translationFailureDetail` 返回 `TextRef.Joined(..., separator = "\n")`：`Res(head)` + `Res("模型返回：%1$s", Raw(snippet))`；保留现有 1,500 字符上限和 `\w+://\S+` 地址遮盖，`redactedLabel` 由调用方传入 `R.string.translate_error_url_redacted`。
- 展示路径：日语纠错失败和普通翻译/润色格式失败共用 `translationFailureDetail`；网络、配置、取消等错误只显示 `error`，不附模型原文。听写任务徽章仍只显示短状态，详情另起一行。
- `TranslateCenter.TranslatedCues.detail` 改为 `TextRef`；`abandon` **保持接收持久化 `String`**，取消/超时/中断分支传 `PersistedErrors.*` 或 `SubtitleTaskStopped.persistedError`，普通异常在边界 `resolve(userTextOr(...))` 后传入。不要为了 `TextRef` 把展示文本写进任务状态。
- 保留截断边界：服务返回 `finish_reason == "length"` 但解析结果完整时，仍交付字幕；只有解析失败才展示错误详情。
- `applyGenerated` 返回给 `WhisperCenter` 的失败文本在边界 `resolve`。

### 5. 进度 detail

`TaskProgressSnapshot.detail`、`TransferProgress.units(..., detail)` 改为 `TextRef?`。这一步放在 WP0-B，连同全部调用方和资源一起改，避免 WP0 因 WP1/WP3 尚未迁移而无法编译：

- `JaWorkFiles.kt:267` → `TextRef.Res(R.string.progress_detail_files, n, total)`
- `JapaneseAsmrHlsDownload.kt:62/70` → `TextRef.Res(R.string.progress_detail_segments, n, total)`
- `OfflineWhisperTranscriber` 四个 detail → `TextRef.Res`
- `WhisperCenter.kt:800` 直接把 `TextRef` 交给 tracker，不再提前拼 String
- `TaskProgressDisplay.detailLines` / `audioNotificationState` 用 `context.resolve`
- 只有 `progress_detail_files` 参与通知 detail 过滤

WP0-B 的文件清单至少包括：`data/TaskProgress.kt`、`TaskProgressCenter.kt`、`TaskProgressDisplay.kt`、`DownloadCenter.kt`、`WhisperCenter.kt`、`japaneseasmr/JaWorkFiles.kt`、`JapaneseAsmrHlsDownload.kt`、`OfflineWhisperTranscriber.kt`、`AudioPcmReader.kt`，以及新增 `strings_progress.xml` key。任一调用方未同步时该包不通过。

`TaskSegmentSummary.model/platform` 保持 `String`，但调用方在写入前用资源组装：`whisperModelLabelRes`、`translatePlatform.labelRes`，避免把资源 ID 序列化进 `progressSummary`。

### 6. 服务与停止异常

- `SubtitleTaskService` / `AudioDownloadService` 的本地常量改为引用 `PersistedErrors` 同名常量；`CancellationException(PersistedErrors.CANCELLED)`。
- `SubtitleTaskStopped` / `AudioDownloadStopped` 增加 `persistedError: String`，与展示用 `userText` 分离：
  ```kotlin
  internal class SubtitleTaskStopped(
      val persistedError: String,
      override val userText: TextRef = persistedErrorText(persistedError) ?: TextRef.Raw(persistedError),
  ) : CancellationException(persistedError), UserFacingError
  ```
  `message` 仍等于 `persistedError`，控制流和旧测试保持可用；`abandon` 写 `persistedError`，不写 `userText`。
- 通知标题/正文/动作全部 `getString`。
- `OfflineWhisperModelState.Failed` 改为 `TextRef`；`cancelDownload(interruption: TextRef?)`，模型服务传 `TextRef.Res`。

### 7. 播放、合集与桌面字幕

- `BiliPlaybackDataSource` 的 `IOException` 改为 `UserFacingIOException(TextRef.Res(...))`；BilibiliException 包装保留 `userText`。
- `PlaybackService.playbackErrorMessage` 仍在**最终展示字符串**上过滤 `://`、`Source error` 和 `androidx.media3`：
  - 遍历异常链，优先取 `UserFacingError.userText`，否则取 `Raw(message)`；
  - 在 Service 边界 `resolve` 后过滤，保留现有「取最后一个可接受原因」的语义；
  - 没有可接受原因时使用 `R.string.player_error_playback`；
  - JaFailure 分支直接 `getString(messageRes)`，不绕过过滤。
  断言落在最终展示文本，不只看 `.message` 或资源 ID；`TextRef.Res` 参数中的地址也必须被过滤。
- 写库使用同一个 `resolveNoUrl` 边界；服务端返回的 `TextRef.Raw` 可以保留，但不能原样进入 Snackbar、通知或 Room。
- `BiliCollectionKind` 改为同时携带展示资源和持久化回退：
  ```kotlin
  enum class BiliCollectionKind(
      @param:StringRes val labelRes: Int,
      internal val fallbackTitle: String,
  ) {
      SEASON(R.string.bilibili_collection_title, "B 站合集"),
      SERIES(R.string.bilibili_collection_series, "B 站系列"),
  }
  ```
  UI 用 `stringResource(kind.labelRes)`；`metadata` 继续使用 `fallbackTitle`，收藏、刷新、列表卡片和复制默认名称都得到非空标题。删除 `BiliCollection.label` 计算属性，避免 UI 继续误用中文标签；不能把 `metadata` 的标题回退改成资源 ID 或空字符串。
- `BiliPagedState` 的 error 改 `TextRef?`，`BiliBrowser` 用 `LocalContext.current.resolveNoUrl(error, R.string.bilibili_error_read_failed)` 展示；`BiliBrowserViewModel` 的 `graph.messages.value = error.message` 改 `graph.show(error.userText)`。
- `SubtitleCues.kt:110` 使用 `UserFacingIOException`。
- `SubtitleSource` 已有 `Context`，`notice` 字段继续 `String`，用 `context.getString`。
- `DesktopSubtitleWindow` 全部 `context.getString`；复用 `a11y_previous/next/play/pause` 与 `settings_desktop_subtitles_title`。
- `PlayerClient` 已持有 `appContext`，固定提示用 `getString`，`LibraryRemovalRejected` 用 `resolve(userText)`。

## 实施工作包

实施顺序固定为：WP0-A → WP0-B → WP1 → WP2 → WP3 → WP4 → WP5。每个工作包先跑编译和受影响的单测，收尾再跑完整构建；不得把某个包的类型改动留到后续包才补调用方。

### WP0-A：基础设施、异常分类与控制流（先单独验证）

文件：`text/UserFacingError.kt`、`text/TextRefAndroid.kt`、`text/PersistedErrors.kt`、`data/SubtitleSource.kt`、`dlsite/DlsiteClient.kt`、`dlsite/DlsiteArchiveClient.kt`、`japaneseasmr/JaWorkClient.kt`、`japaneseasmr/JapaneseAsmrParse.kt`、`asmr/AsmrClient.kt`、`data/TaskProgressCenter.kt`、`data/DownloadCenter.kt`、`data/WhisperCenter.kt`、`data/TranslateCenter.kt`、`playback/PlaybackService.kt`，以及 `ui/library/FilePreviewScreen.kt`、`ui/settings/ModelSettingsScreen.kt`、`ui/bilibili/BiliBrowserViewModel.kt`、`ui/library/LibraryViewModel.kt`、`ui/player/PlayerChrome.kt`、`ui/asmr/OnlineScreens.kt`、`ui/asmr/JapaneseAsmrVerificationScreen.kt`、`ui/importing/ClipboardImportViewModel.kt` 的读取点。

1. 新增 `UserFacingIOException`、`Context.resolveNoUrl`；改异常签名；保留 String 次构造。
2. `DlsiteException` 认证 throw 点补 `kind = AUTH` 与 `statusCode = 401`，`sign()` 判 `kind`；`DlsiteArchiveException` 增加 `archiveKind`，可续传中断标 `INTERRUPTED`。
3. `AsmrException.kind` 与 UI 分支。
4. 创建 `strings_japaneseasmr.xml`，加入 `JaFailure` 全套资源和最终 `ja_stage_*` key；`JaFailure.messageRes`；同步 `PlayerChrome.kt:244`、`PlaybackService.kt:106/110`、`OnlineScreens.kt:940`、`JapaneseAsmrVerificationScreen.kt:110/112/361`、`ClipboardImportViewModel.kt:82/86`。这样可以独立编译，WP3 只补其余 JA 文案。
5. `PersistedErrors` 增加 `DLSITE_ARCHIVE_INTERRUPTED`、`DLSITE_ARCHIVE_FAILED`、`TRANSLATE_SAVE_FAILED`、`isCancelled`、`isInterrupted`；服务常量别名；`SubtitleTaskStopped.persistedError`；`SubtitleSource.kt:279-280` 改引用 `TRANSLATE_SAVE_FAILED` 并补 `persistedErrorText` 映射。
6. `failureResult` 使用精确集合和旧文本兼容；`DownloadCenter` 对 DLsite 中断、JA 验证/入口/文件缺失、取消/超时/中断分别写固定哨兵；其余异常 `resolveNoUrl`。
7. `DownloadCenter`、`WhisperCenter`、`TranslateCenter` 的异常写库边界修正；修复 `FilePreviewScreen.kt:310`（`context.resolve`）、`ModelSettingsScreen.kt:326`（`graph.show`）、`BiliPagedState.kt:40`、`BiliCollectionRepository.kt:96`、`LibraryViewModel.kt:1007/1009`、`BiliBrowserViewModel.kt:99` 的 `.message` 直读。
8. `PlaybackService.playbackErrorMessage` 改为最终字符串过滤；`PlaybackService.kt:149/177` 的恢复失败也用同一过滤。
9. `WhisperStart.BackgroundUnavailable` / `WhisperCenter.retry()` 改 `TextRef`；`LibraryViewModel.kt:698` 摘要加资源。

本包**不改** `TaskProgress.detail` 类型，也不改 `TranslateReply.raw` 的语义；`JaFailure.message` 可暂时保留供日志，UI 不再读取。

验证：`compileDebugKotlin` + `testDebugUnitTest`；新增 `DlsiteArchive` 中断分类、`PersistedErrors.isInterrupted` 兼容、`playbackErrorMessage` 地址过滤、`FilePreview`/`ModelSettings` 边界断言。

### WP0-B：进度 detail 类型化

文件：`data/TaskProgress.kt`、`TaskProgressCenter.kt`、`TaskProgressDisplay.kt`、`DownloadCenter.kt`、`WhisperCenter.kt`、`japaneseasmr/JaWorkFiles.kt`、`JapaneseAsmrHlsDownload.kt`、`OfflineWhisperTranscriber.kt`、`AudioPcmReader.kt`，资源 `strings_progress.xml`。

- `TaskProgressSnapshot.detail`、`TransferProgress.units(..., detail)` 一次性改为 `TextRef?`。
- 同包更新全部生产者：文件计数、分片计数、离线解码/人声检测、`WhisperCenter`；`TaskProgressDisplay` 在展示/通知边界 `resolve`。
- `TaskProgressDisplay.kt:162` 改 `resIdOrNull()` 判断；通知仍只过滤 `progress_detail_files`。
- 同步受影响的 JVM/仪器测试，断言 `TextRef.Res` 的资源 ID 和参数。

验证：`compileDebugKotlin` + `testDebugUnitTest` + `compileDebugAndroidTestKotlin`。此包不依赖 WP1/WP3 的其余文案迁移。

### WP1：翻译、听写与模型

文件：`data/TranslateClient.kt`、`TranslateCenter.kt`、`TranslatePlatforms.kt`、`WhisperModels.kt`、`OfflineWhisperModelManager.kt`、`OfflineModelDownloadService.kt`，以及 `ui/settings/ModelSettingsScreen.kt`、`SettingsScreen.kt`、`ui/player/PlayerChrome.kt`、`ui/download/DownloadScreen.kt` 的调用点。`OfflineWhisperTranscriber`/`AudioPcmReader` 的进度 detail 已在 WP0-B 完成，本包只处理其错误和标签。

- `Completion.error`、`RevisedTranslationReply.error`、`translationFailureDetail` 改 `TextRef`；`TranslateReply` 同时保留独立的 `error` 与 `raw`，不把模型原文改名为错误。
- 统一格式错误标题，保留失败详情中的清洗后模型片段；换行使用 `TextRef.Joined(separator = "\n")`，保留 1,500 字符和地址遮盖。
- `TranslatePlatform` / `TranslateEffort` 改 `@param:StringRes labelRes`。
- `whisperModelLabelRes` + `whisperModelLabel(context, ...)`；`missingOfflineWhisperModelMessage` 返回 `TextRef`。
- 模型管理器/下载服务错误与通知。
- `TranslateCenter.abandon` 保持持久化 `String`；取消/超时/中断写固定哨兵，普通失败在边界解析。`runRow` 的 `IllegalStateException` 改为 `UserFacingException`。
- 测试：`TranslationReplyTest`（`error`/`raw` 分离、换行、截断仍交付）、`OfflineWhisperModelManagerTest`、`WhisperModelTest`，更新精确中文断言。

### WP2：DLsite

文件：`dlsite/DlsiteArchiveClient.kt`（38）、`DlsiteArchiveExtractor.kt`（19）、`DlsiteClient.kt`（17 + 1 分支）、`asmr/AsmrClient.kt`（预览截断与其余 asmr.one 错误）、`data/DlsiteOriginalStore.kt`（3）、`data/DownloadCenter.kt` 相关写库边界。

- 全部异常改 `TextRef.Res`；`downloadPageError` 返回 `TextRef`；资源写入新文件 `strings_dlsite.xml`。
- 完整性异常保留子类；中断异常在 WP0-A 已带 `archiveKind`，本包只迁移展示资源。
- `require` 改 `UserFacingException(Res)`。
- 内容回退名（试听/文件夹）保留白名单；预览截断不留在客户端硬编码：返回 `clipped` 标记，由 UI 追加 `R.string.library_file_truncated`，asmr.one 共用同一资源。
- 测试：`DlsiteArchiveTest`、`DlsiteArchiveRoutingTest` 改断言 `userText` / 资源 ID；新增中断哨兵与旧句子分类断言；保留“不泄漏 URL/凭证”的反向断言，并检查最终展示文本。

### WP3：Japanese ASMR

文件：`japaneseasmr/JaWorkClient.kt`（19）、`JapaneseAsmrHlsDownload.kt`（17）、`JapaneseAsmrParse.kt`（12）、`data/JaWorkFiles.kt`（8）、`JaMega.kt`（2）、`JapaneseAsmrHls.kt`（1）、`playback/PlaybackService.kt` 的 JA 分支。

- `JaFailure` 资源化；`JapaneseAsmrException.userText` 用 `Res`；其余资源追加到 WP0-A 创建的 `strings_japaneseasmr.xml`。
- `JaWorkClient` 的 stage 名改 `@StringRes`，拼接使用嵌套 `TextRef`：
  ```kotlin
  TextRef.Res(R.string.ja_work_buzz_verify, listOf(TextRef.Res(stageRes)))
  ```
  资源模板为 `Buzzheavier 的%1$s需要网页验证`；不能传整数 `stageRes`，也不能只包成 `listOf(stageRes)`，否则 `%1$s` 会显示资源编号。新增 `ja_stage_hosting_page`（托管文件页）和 `ja_stage_file_download`（文件下载），并验证实际显示。
- `check/error` 改 `UserFacingException`；直接 `IOException` 改 `UserFacingIOException`。
- `JaWorkFiles.execute(): TextRef`，完成摘要用 `TextRef.Joined` / `Res`；`DownloadCenter.notice` 前 `resolve`。
- HLS 分片进度在 WP0-B 已改 `TextRef`；本包只改其余 HLS 错误文案。
- 验证分支：`VERIFY` 进入等待验证；`JaWorkAddressExpired(sessionRequired=true)` 重新打开当前失败文件页；`JaWorkException` 入口变化提示重选入口，不误走普通失败。
- 测试：`JaWorkDownloadTest` 精确中文断言改 `userText`；stage 断言检查 `TextRef.Res` 嵌套参数；`JapaneseAsmrTest` 保持枚举断言；`JaWorkFilesAndroidTest.verificationReservationExcludesItselfAndLeavesOrdinaryFailureFree` 保持占用语义。

### WP4：播放、服务、B 站与剩余

文件：`playback/BiliPlaybackDataSource.kt`（20）、`PlaybackService.kt`（4）、`SubtitleSource.kt`（3）、`SubtitleCues.kt`（1）、`PlayerClient.kt`（1）、`DesktopSubtitleWindow.kt`（8）、`bilibili/BiliCollections.kt`（7）、`data/BiliCollectionRepository.kt`（3）、`bilibili/BiliPagedState.kt`（1）、`AppGraph.kt`（1）、`data/AudioDownloadService.kt`（3）、`data/SubtitleTaskService.kt`（2）、`youtube/YouTubeModels.kt`（6）、`YouTubeSolver.kt`（4）。

- 自定义异常/`UserFacingException` 替换 `IOException`、`check`；`QueuePlayable`、`BiliLibraryImport`、`BiliLibraryStore` 的 `require/check` 属于内部校验，保留并列入白名单，避免改变异常类型。
- `BiliCollectionKind` 增加 `labelRes` 与 `fallbackTitle`；**保留** `BiliCollection.title` 的持久化回退（`B 站合集` / `B 站系列`）。`metadata` 在远端标题为空时仍写入非空标题，收藏、刷新、列表卡片和复制默认名称不变；只把独立展示标签改成 `stringResource(kind.labelRes)`。
- 通知与桌面字幕 `getString`。
- `BiliPagedState` 的 error 改 `TextRef?`，`BiliBrowser` 用 `LocalContext.current.resolveNoUrl(error, R.string.bilibili_error_read_failed)` 展示；`BiliBrowserViewModel` 的 `graph.messages.value = error.message` 改 `graph.show(error.userText)`。
- `AppGraph.kt:96` 改 `show(R.string.bili_import_choice_pending)`。
- 测试：Bili 相关单元测试；新增“远端标题为空时收藏名称非空、刷新不清空、复制默认名称非空”的断言；仪器测试只需编译，若有文案断言改用 `targetContext.getString`。

### WP5：收尾、文档与验收

1. 全局 `rg` 残留，逐项确认只落在白名单；`ui/` 扫描当前为 0，作为回归门禁而非发现命令。
2. 检查重复 key、占位符数量、`$` 模板、`%%`。`$` 检查必须先剔除 `%N$s`/`%N$d`，否则会把合法的位置参数误报。
3. 检查所有最终展示/写库文本：不能含 `://`、`res:#`、资源编号；数据库 `error` 只能是哨兵或已解析文本。
4. 跑完整 Gradle 验证与 `git diff --check`。
5. 更新 `Android/docs/ui-copy-review-v1.4.md` 顶部实施记录：把“仍有约 300 处”改为本轮完成情况、白名单和测试数。
6. 把执行结果补记到本文末尾。
7. 取得安装授权后，运行 `TaskProgressAndroidTest`、`TaskProgressTransferAndroidTest`、`TaskProgressPreviewAndroidTest`、`JaWorkFilesAndroidTest`（含验证占用用例）和 `JapaneseAsmrAndroidTest`；编译通过与实跑结果分别记录。
8. 4.4 截图复核单列：360 dp 宽，字体 1.0、1.3、2.0，检查任务进度、播放错误、DLsite/JA 下载和合集名称。

## 资源组织

不新增 `values-en`。按域拆文件，避免 `strings_errors.xml` 膨胀：

| 文件 | 新增内容 | 前缀 |
| --- | --- | --- |
| `strings_dlsite.xml`（新增） | DlsiteClient/ArchiveClient/Extractor/OriginalStore 错误 | `dlsite_error_*`、`dlsite_archive_error_*` |
| `strings_japaneseasmr.xml`（新增） | WP0-A 先放 `JaFailure` 与 `ja_stage_*`；WP3 再放 JaWorkClient、HLS、JaMega、JaWorkFiles 摘要；把现有 `ja_error_work_file_missing`、`ja_error_entry_changed` 从 `strings_errors.xml` 迁入，避免重复 key | `ja_error_*`、`ja_stage_*`、`ja_work_*` |
| `strings_errors.xml` | 翻译、听写、通用错误 | `translate_error_*`、`whisper_error_*` |
| `strings_bilibili.xml` | 新增 `bilibili_collection_series`；合集/系列标签复用 `bilibili_collection_title`；合集错误 | `bilibili_*` |
| `strings_notifications.xml` | 音频/字幕/模型服务标题、正文、取消动作 | `notification_*` |
| `strings_progress.xml` | 文件/分片/离线解码 detail（WP0-B） | `progress_detail_*` |
| `strings_library.xml` | 复用现有 `library_file_truncated`，DLsite/asmr.one 预览共用 | 不新增 |
| `strings_player.xml` | 播放错误、桌面字幕按钮、兜底标题 | `player_*`、`desktop_subtitle_*`（能复用 `a11y_*` 的复用） |
| `strings_settings.xml` | 模型显示名、推理档位、模型下载错误 | `settings_model_*`、`settings_effort_*` |
| `strings_online.xml` | asmr.one 搜索/连接错误 | `asmr_error_*` |
| `strings_youtube.xml` | YouTube 签名/脚本错误 | `youtube_error_*` |

规则：

- 占位符统一 `%1$s` / `%1$d`；嵌套 `TextRef` 作为参数时先 `resolve`。
- 长句拆 `_title` / `_message`；通知正文不拼接技术信息。
- 新增 key 不与现有 key 重复；能复用现有 `download_*`、`progress_*`、`a11y_*` 的不新造。

预计新增约 200–230 个 key，新增 `strings_dlsite.xml`、`strings_japaneseasmr.xml` 两个文件；复用 `library_file_truncated`。

## 测试策略

### JVM 单元测试

- `DlsiteArchiveTest` / `DlsiteArchiveRoutingTest`：`message.contains(...)` 改为 `assertEquals(TextRef.Res(R.string...), error.userText)` 或 `resIdOrNull()`；新增可续传中断写固定哨兵、旧句子仍归为 `INTERRUPTED` 的断言；保留 URL/凭证不泄漏断言，并在最终展示文本上检查。
- `JaWorkDownloadTest`：三处精确中文断言改为 `userText` 资源 ID；`stage` 断言检查嵌套 `TextRef.Res`，而不是资源编号字符串；新增验证/地址失效/入口变化后续动作断言。
- `TranslationReplyTest`：`translationFailureDetail` 改 `TextRef` 断言；验证 `error` 与 `raw` 分离、换行分隔、1,500 字符上限、地址遮盖，以及“截断但解析完整仍交付”；prompt/violations 中文断言保留。
- `OfflineWhisperModelManagerTest`：`Failed("…")` 改为 `Failed(TextRef.Res(...))`。
- `WhisperModelTest`：`whisperModelLabel` 改为 `whisperModelLabelRes` 或 `context.getString`。
- 新增/扩展：`DlsiteException.kind == AUTH`、`DlsiteArchiveFailureKind.INTERRUPTED`、`AsmrException.kind`、`PersistedErrors.isInterrupted`（新旧值）、`BiliCollection` 空标题收藏/刷新非空、`BiliPlaybackDataSource` 的 `userText` 与最终地址过滤。
- `TranslateCenter` 取消/超时：断言 `failIfActive` 收到 `PersistedErrors.CANCELLED` / `*_TIMEOUT`，不是展示资源文本。
- 保留 `IllegalStateException` 类型断言（Service 清理依赖）。

### 仪器测试

- 只要求 `compileDebugAndroidTestKotlin`；Compose UI 测试仍受旧 Espresso 环境限制。
- `BiliCollectionUiAndroidTest`、`ClipboardImportAndroidTest` 等已有 `targetContext.getString` 模式的继续沿用。
- 持久化哨兵断言（`"已中止"` 等）保持不变。

## 验证

```sh
cd Android
export GRADLE_USER_HOME="$HOME/.gradle"
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest \
  :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-configuration-cache
git diff --check
```

静态检查：

```sh
# UI 中不应再有中文字面量；当前为 0，作为回归门禁
LC_ALL=C rg -n '"[^"]*\p{Han}[^"]*"' app/src/main/java/com/nanahoshi/audioplayer/ui -g '*.kt'

# 全工程残留；结果应只剩白名单
LC_ALL=C rg -n '"[^"]*\p{Han}[^"]*"' app/src/main/java/com/nanahoshi/audioplayer -g '*.kt'

# UI/playback 不应直接读异常 message
LC_ALL=C rg -n '\.message' app/src/main/java/com/nanahoshi/audioplayer/ui app/src/main/java/com/nanahoshi/audioplayer/playback -g '*.kt'

# 中文分支残留
LC_ALL=C rg -n 'contains\("登录"\)|startsWith\("文件 |== "已中止"|ASMR_WORK_NOT_FOUND|ASMR_INVALID_RJ' app/src/main/java -g '*.kt'

# 重复 key
rg -o 'name="[^"]+"' app/src/main/res/values -g '*.xml' | sed 's/.*name="//;s/"//' | sort | uniq -d

# Kotlin $ 模板：先剔除 %N$s / %N$d，避免误报位置参数
rg -l '' app/src/main/res/values -g '*.xml' \
  | xargs perl -ne 's/%\d+\$[sd]//g; print "$ARGV:$_" if /\$[A-Za-z_{]/'
```

最终文本检查不靠正则推断，至少抽查：

- `PlaybackService.playbackErrorMessage` 的最终返回值；
- `DownloadCenter` / `WhisperCenter` / `TranslateCenter` 写入 Room 的值；
- `BiliCollectionRepository` 收藏和刷新后的 `playlist.name`；
- DLsite/asmr.one 预览截断后的最终字符串；
- `JaWorkClient` stage 的 `context.getString` 实际结果（「托管文件页」「文件下载」）。

验收标准：

- `ui/` 中文字面量为 0。
- 全工程中文残留只允许：`DlsiteGenres.kt`、`PersistedErrors.kt`、`Daos.kt`、`TranslateClient` prompt/violations/structuralError、内容回退名白名单、B 站合集持久化标题回退、服务端返回正文。
- 没有 `res:#`、资源编号或 `://` 进入 UI 错误/状态、Snackbar、通知，或 Room 的 `error`/进度摘要文本。
- `DownloadCenter`/`WhisperCenter`/`TranslateCenter` 的所有 Room `error` 写入都是固定哨兵或已解析文本；DLsite 可续传中断、翻译取消/超时和旧记录分类保持正确。
- 播放错误最终展示和写库文本均经过地址过滤；`TextRef.Res` 参数中的地址也被覆盖。
- B 站合集远端标题为空时，收藏、刷新、列表卡片和复制默认名称仍非空。
- DLsite/asmr.one 预览截断复用 `library_file_truncated`，不再硬编码。
- `compileDebugKotlin`、`testDebugUnitTest`、`compileDebugAndroidTestKotlin`、`assembleDebug` 通过；单元测试数不低于 453（仅作基线）。
- 每个 WP 单独编译通过；WP0-A、WP0-B 不依赖后续 WP 的资源或调用方。
- 设备测试和 360 dp 截图复核结果单独记录；未取得安装授权时明确标注未执行。
- 未提交、未推送。

## 风险与处理

| 风险 | 处理 |
| --- | --- |
| 改成 `TextRef` 后 `Exception.message` 变成 `res:#…`，被 UI/DB 直接读取 | WP0-A 先改所有直接 `.message` 读取点；收尾用 `rg` 门禁 |
| `DownloadCenter`/`TranslateCenter` 把 `TextRef` 或展示文本写库 | 控制流错误写固定哨兵；其余写 `resolveNoUrl` 结果；哨兵保持 String |
| DLsite 可续传中断被精确集合漏判 | `DLSITE_ARCHIVE_INTERRUPTED` 入集合；读取兼容旧句子；写库前按 `archiveKind` 映射 |
| 翻译取消/超时改成展示文本后任务页分类失效 | `abandon` 保持持久化 String；`SubtitleTaskStopped.persistedError` 与 `userText` 分离 |
| 播放错误绕过地址过滤 | 在 Service 最终字符串上过滤；`resolveNoUrl` 覆盖嵌套参数；断言最终展示/写库文本 |
| 移除 B 站合集标题回退导致空名称 | 保留持久化标题回退；只迁移 `labelRes`；补充收藏/刷新/复制断言 |
| WP0 因 WP1/WP3 未改调用方无法编译 | 拆为 WP0-A/WP0-B；每包包含类型、资源、全部调用方和断言 |
| 阶段名传整数资源 ID，显示编号 | 使用嵌套 `TextRef.Res(stageRes)`；验证实际显示「托管文件页」「文件下载」 |
| `TranslateReply.raw` 同时表示错误和原文 | 分离 `error: TextRef?` 与 `raw: String?`；保留截断仍交付边界 |
| `TextRef.Joined` 默认分隔符丢失换行 | 失败详情显式 `separator = "\n"` |
| 中文分支失效 | 先做类型/错误码/结构化，再迁移文案 |
| 自定义异常换基类导致 Service catch 失效 | 保持 `IllegalStateException` / `IOException` 基类，只增加接口 |
| 内容回退名迁移破坏标题/文件名 | 列入白名单；若后续要做，另开写入边界改造 |
| `TaskSegmentSummary.platform/model` 把资源 ID 写入 progressSummary | 只在 `report.metadata` 前用 `getString` 解析成展示文本，类型保持 String |
| `JaFailure.message` 仍有直接读取 | WP0-A 全量替换为 `userText`/`messageRes`，测试断言枚举 |
| `JaFailure.WORK_FILE_MISSING` 资源文案与 DB 哨兵漂移 | 写 DB 时映射回 `PersistedErrors.JA_WORK_FILE_MISSING`；资源只用于展示 |
| `TaskProgress.detail` 改类型波及 tracker/UI | WP0-B 一次性改 API、全部生产者和展示点；单测覆盖 |
| 翻译 prompt 被误迁移 | 明确白名单；`TranslationReplyTest` 保留 prompt 断言 |
| 大量资源 key 重复 | 每个 WP 后跑重复 key 检查；复用已有 `download_*` / `progress_*` / `library_file_truncated` |
| `$` 模板检查误报位置参数 | 先剔除 `%N$s`/`%N$d` 再匹配；不用裸 `\$[A-Za-z{]` 作为门禁 |
| `lintDebug` 已失败 | 不把 161 个既存 error 当本轮门槛；在交付说明中注明 |

## 执行记录（2026-11-21）

- WP0-A、WP0-B 和 WP1–WP4 已实施。WP0-B 同步更新了下载页对进度详情的直接读取，以及原有的进度单测。抽查了取消和超时的固定入库值、下载中断的兼容分类、合集中空标题的回退路径，以及格式化后的异常文本。
- Japanese ASMR 的地址过期继续走原有重新解析分支。只有确认进入等待验证的下载写入 `VERIFY` 阶段和 `PersistedErrors.JA_VERIFY`。译文保存失败沿用原入库值「无法保存译文，请重试」。
- DLsite 与 asmr.one 读取预览时同时返回正文和截断标记。文件预览共用截断提示资源，字幕读取使用正文。
- WP5 完整 Gradle 验证通过。执行了 `compileDebugKotlin`、`testDebugUnitTest`、`compileDebugAndroidTestKotlin` 和 `assembleDebug`。单元测试 460 个全部通过，无失败或跳过。Debug APK 位于 `Android/app/build/outputs/apk/debug/app-debug.apk`。
- `git diff --check` 通过。静态扫描在 `ui/` 中无中文字符串命中，主源码其余命中为 DLsite 标签、固定入库值、内容回退名、内部参数校验和模型提示词或诊断等白名单项。字符串资源无重复名称或未替换的 Kotlin 模板。
- 设备测试已编译。待取得安装授权后实跑相关用例，并按审阅报告 4.4 复核 360 dp 宽、字体 1.0、1.3 和 2.0 的截图。本轮未重跑 `lintDebug`，沿用计划记录的既有错误基线。
- 无提交或推送。
