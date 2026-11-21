# 完成 P4 第二、三批：用户文案资源化

## Context

`Android/docs/ui-copy-review-v1.4.md` 的 P4 第一批已完成：通用动作/状态、通知渠道名、无障碍描述已进入 `res/values/strings.xml`。第二批（错误提示、空状态、长帮助）和第三批（其余一次性文案）尚未完成，原因是大量文案产生在 `ViewModel`、Service、Repository 和纯客户端中，无法直接使用 `stringResource`；同时 Room 的三个 `error` 列、SQL 中的中文状态值和现有字符串比较会受影响。

本轮目标是在不改数据库结构和任务行为的前提下，把剩余用户可见中文迁移到资源文件，并建立数据层到 UI 的文案映射。

已确认：

- 采用 `TextRef`（资源 ID + 参数）承载数据层文案，在 Context/Compose 边界解析。
- 一并落地评审文档 6.1–6.3 的命名建议。
- 暂不建立 `values-en`。
- 第三批迁移全部用户可见一次性文案；排除内容数据、日志、数据库状态值、测试标签和注释。

## 目标与非目标

### 目标

1. 第二批：错误提示、空状态、长帮助、通知正文全部资源化。
2. 第三批：导航、页面标题、设置项、播放器、B 站/YouTube、ViewModel 提示、标签枚举、动态计数等全部用户可见文案资源化。
3. 建立可复用的 `TextRef` 和 `TextRef.resolve(context)`，让纯数据类不持有 `Context`。
4. 保持 Room schema、数据库版本、`DlsiteGenres.kt` 内容和 SQL 状态值不变。
5. 更新断言中文文案的单元测试与仪器测试。

### 非目标

- 不新增 `values-en`，不做翻译。
- 不迁移 `DlsiteGenres.kt` 的 320 条内容标签。
- 不迁移日志、注释、`testTag`、服务端返回的内容数据。
- 不改下载、听写、翻译、播放和数据库行为。
- 不新增 Room 列，不提升 `AppDatabase` 版本。
- 不重写 P2 已确认的文案，只做资源提取；6.1–6.3 的命名调整除外。

## 总体方案

### 1. `TextRef` 与解析

新增中立包 `com.nanahoshi.audioplayer.text`（不要放在 `data` 或 `ui`）：

- `text/TextRef.kt`
- `text/TextRefAndroid.kt`
- `text/UserFacingError.kt`
- `text/PersistedErrors.kt`

```kotlin
sealed interface TextRef {
    data class Raw(val value: String) : TextRef
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : TextRef
    data class Joined(val parts: List<TextRef>, val separator: String = " · ") : TextRef

    fun debugString(): String   // Raw -> value; Res -> "res:#id(...)"; Joined -> join
}
```

- `args` 允许嵌套 `TextRef`；`Context.resolve(TextRef)` 递归解析后再调用 `getString(id, *args)`。
- `Raw` 只用于服务端/模型返回的原始原因、文件名、URL 等不可翻译内容；用户看到的兜底句必须是 `Res`。
- `Joined` 用于多段结果（删除结果、整部作品下载统计），分隔符可传 `"。"`、`"；"`、`"\n"`。
- `resIdOrNull()` 用于替换现有 `message.contains("登录")`、`== "未找到该作品"` 等文案判断。
- `@Composable fun TextRef.asString(): String` 用 `LocalContext.current.resolve(this)`；静态文案仍直接用 `stringResource`。
- 新增 `TextRefTest`：嵌套参数、整数参数、`Joined` 分隔符、`debugString`、`resIdOrNull`、`%`/引号转义。

### 2. 异常与状态

新增：

```kotlin
interface UserFacingError { val userText: TextRef }

fun Throwable.userTextOr(fallback: TextRef): TextRef
fun Throwable.userTextOr(@StringRes fallback: Int, vararg args: Any): TextRef

class UserFacingException(text: TextRef) : IllegalStateException(text.debugString()), UserFacingError {
    override val userText = text
}
```

- 全部自定义异常实现 `UserFacingError`：`WhisperException`、`BilibiliException`、`YouTubeException`、`DlsiteException`、`DlsiteArchiveException`、`JaWorkException`、`JapaneseAsmrException`、`ExternalFileException`、`AppUpdateException`、`AsmrException`、`LibraryRemovalRejected`。
- 迁移期保留一个接收 `String` 的次级构造器，内部包成 `TextRef.Raw`；这样可以先改消费者、再逐簇改 throw 点。
- `Exception.message` 使用 `userText.debugString()`，只用于日志；不允许再被 UI/Snackbar 直接读取。
- 约 97 处 `error(...)` / `check(...) { ... }` / `require(...) { ... }` 改为 `UserFacingException(TextRef.Res(...))`。必须继续继承 `IllegalStateException`，否则 Service 的现有清理 catch 会失效。
- 状态载荷改为 `TextRef`：
  - `WhisperStart.BackgroundUnavailable(text: TextRef)`
  - `UpdateStatus.Failed(text: TextRef)`
  - `OfflineWhisperModelState.Failed(text: TextRef)`
  - `ClipboardImportState.error: TextRef?`
  - ViewModel 的 `var error by mutableStateOf<String?>` 改为 `TextRef?`
- `JaFailure` 的 `message: String` 改为 `@StringRes val messageRes: Int`；`JapaneseAsmrException` 暴露对应的 `TextRef`。

### 3. 全局消息通道

把 `AppGraph.messages` 从 `MutableStateFlow<String?>` 改为 `MutableStateFlow<TextRef?>`，并提供：

```kotlin
fun show(text: TextRef)
fun show(@StringRes id: Int, vararg args: Any)
fun showRaw(text: String?)
```

- 第一步机械化：所有现有 `graph.messages.value = ...`（约 102 处）改为 `graph.showRaw(...)` 或 `graph.show(TextRef.Raw(...))`，行为不变。
- `MainActivity` 在 `LaunchedEffect(message)` 中用 `LocalContext.current` 解析后再判断长度和 `SnackbarDuration`。
- ViewModel 的提示改用 `graph.show(R.string.x, args)`；Controller 等无 Context 的组件发 `TextRef`。
- 不再让数据层提前把 `TextRef` 解析成 String 后塞进 `messages`，避免遗漏和重复解析。

### 4. 解析位置

- **Compose**：静态文案 `stringResource`；动态 `TextRef` 用 `asString()`。注意 `stringResource` 不能放在 `LaunchedEffect`、`remember {}` 或点击回调中，需在 Composable 作用域先取出。
- **ViewModel**（均为 `AndroidViewModel`）：用 `getApplication<Application>().getString(...)` 组装需要拼接的提示；捕获 `UserFacingError` 时用 `error.userText`。
- **Repository / Center / Service**（已有 `Context`）：只在写 Room、通知或非 Compose Android View 时解析；其余保持 `TextRef`。
- **纯客户端**：只抛携带 `TextRef` 的异常，不引入 `Context`。
- **`ClipboardImportController`**：保持无 `Context`，`message` 回调和 `state.error` 改为 `TextRef`，由 ViewModel 解析。
- **`DesktopSubtitleWindow`** 等普通 Android View：用 `context.getString(...)`。

### 5. 持久化错误与 SQL 哨兵

**Room 仍存解析后的显示文本，不改表结构。** 写入方（`DownloadCenter`、`WhisperCenter`、`TranslateCenter`、`SubtitleSource`、Service）在 `fail(...)` 前调用 `text.resolve(appContext)`。

以下中文是数据库/控制流值，**不迁移**，集中到 `text/PersistedErrors.kt` 并加注释：

```kotlin
internal object PersistedErrors {
    const val CANCELLED = "已中止"
    const val JA_VERIFY = "需要验证后继续"
    const val JA_POST_REMOVED = "篇目已从音频库移除"
    const val JA_POST_REMOVED_LEGACY = "文章已从音频库移除"
    const val LIBRARY_REQUIRED = "请先加入音频库"
    const val AUDIO_DOWNLOAD_INTERRUPTED = "下载中断，请重试"
    const val AUDIO_DOWNLOAD_START_FAILED = "无法启动后台下载，请回到 App 重试"
    const val AUDIO_DOWNLOAD_TIMEOUT = "后台下载超时，请重试"
    const val SUBTITLE_INTERRUPTED = "后台字幕任务已中断，请重试"
    const val SUBTITLE_START_FAILED = "无法启动后台字幕任务，请回到应用后重试"
    const val SUBTITLE_TIMEOUT = "后台字幕任务超时，请重试"
    const val JA_WORK_FILE_MISSING = "该文件只能随整部作品一起下载，请重新下载整部作品"
    const val JA_ENTRY_CHANGED = "原下载入口已变化，请重新选择入口"
    const val TRANSLATE_FAILED = "翻译失败"
}
```

读取时统一经过：

```kotlin
fun persistedErrorText(raw: String?): TextRef?
```

映射表：

| 持久化值 | 展示资源 |
| --- | --- |
| `"已中止"` | `status_cancelled` |
| `"需要验证后继续"` | `download_status_waiting_verification` |
| `"篇目已从音频库移除"` / `"文章已从音频库移除"` | `error_work_removed` |
| `LIBRARY_REQUIRED` | `download_error_library_required` |
| `AUDIO_DOWNLOAD_INTERRUPTED` | `download_error_interrupted` |
| `AUDIO_DOWNLOAD_START_FAILED` | `download_error_start_failed` |
| `AUDIO_DOWNLOAD_TIMEOUT` | `download_error_timeout` |
| `SUBTITLE_INTERRUPTED` | `subtitle_error_interrupted` |
| `SUBTITLE_START_FAILED` | `subtitle_error_start_failed` |
| `JA_WORK_FILE_MISSING` | `ja_error_work_file_missing` |
| `JA_ENTRY_CHANGED` | `ja_error_entry_changed` |
| `TRANSLATE_FAILED` | `translate_status_failed` |
| 其他未知/旧行/服务端文本 | `TextRef.Raw(raw)` |

- `DownloadScreen`、`failureResult`、`WhisperCenter`、`DownloadCenter`、`TranslateCenter` 的现有比较改用 `PersistedErrors` 常量。
- SQL 中的 `'需要验证后继续'`、`'篇目已从音频库移除'` 保持不变；在 `Daos.kt` 注释中指向 `PersistedErrors`。
- `"翻译失败"` 等用于过滤文本的比较改为检查 `status`/`translateStatus`，不再依赖文案。
- 旧的非哨兵中文错误继续以 `Raw` 显示，不做数据库清洗。
- 已存错误不会因切换语言重新翻译；本轮无 `values-en`，可接受。

### 6. 枚举标签

- `ProgressPhase` / `ProgressResult`：`label: String` 改为 `@StringRes val labelRes: Int`。枚举按名称序列化，`progressSummary` 不受影响。
- `ClipboardSource`、`BiliAction`、`CollectionSort`、`OnlineSort`、`PieceKind`、`ThemeMode`、`SubtitleTask`、`SelectionAction`、`LocalRemovalTargets`、`BiliSearch` 的排序/时长枚举：改为 `@StringRes val labelRes: Int`。
- `AccentPreset.name` 改为 `@StringRes val nameRes: Int`；`id` 不变，已保存的主题色不受影响。
- 迁移期可保留 `val label: TextRef get() = TextRef.Res(labelRes)` 过渡，但最终删除字符串 `label`，避免两套来源。
- 服务端返回的 `BiliCollectionEntity.label` 等内容数据不迁移。

### 7. 资源文件组织

不要把 700+ 条全部塞进现有 `strings.xml`。Android 会合并 `res/values/` 下的多个文件；保留第一批 key 不动，按域拆分：

| 文件 | 内容 |
| --- | --- |
| `strings.xml` | `app_name`、`common_*`、`a11y_*`、`channel_*`、底部导航 |
| `strings_errors.xml` | 通用错误、网络/登录/文件错误、持久化状态展示 |
| `strings_help.xml` | 听写/翻译/登录/验证长帮助 |
| `strings_library.xml` | 音频库、作品页、文件预览、导入、本地关联 |
| `strings_download.xml` | 下载任务、下载管理、任务状态、进度详情 |
| `strings_player.xml` | 播放器、队列、字幕、定时关闭 |
| `strings_settings.xml` | 设置、外观、模型、缓存、关于、更新 |
| `strings_online.xml` | asmr.one、Japanese ASMR |
| `strings_bilibili.xml` | B 站搜索、合集、分 P、收藏夹、登录 |
| `strings_youtube.xml` | YouTube 搜索、列表、账号、Cookie |
| `strings_playlist.xml` | 播放列表、队列相关 |
| `strings_import.xml` | 剪贴板导入 |
| `strings_notifications.xml` | 三个前台服务的通知标题、正文、动作 |
| `strings_progress.xml` | 阶段、结果、进度详情 |

Key 使用 `screen_component_purpose`，例如：

```xml
<string name="library_empty_root_title">还没有音频</string>
<string name="library_empty_root_message">点右下角导入本地文件或文件夹；也可以复制 BV 号、RJ 号或视频链接，用剪贴板导入。</string>
<string name="download_error_youtube_audio">YouTube 音轨下载失败，请重试</string>
<string name="player_queue_empty_title">播放队列是空的</string>
<string name="settings_whisper_help_usage">用法：播放时点「生成字幕」…</string>
```

规则：

- 长帮助拆成 `_intro` / `_usage` / `_note`，不要整段塞进一个 key。
- 占位符统一 `%1$s` / `%1$d`；`%` 写 `%%`；单引号写 `\'`；双引号写 `\"`；换行写 `\n`；`&` 写 `&amp;`。
- 不使用裸 `%s`，不与位置参数混用。
- 不为只出现一次、语义又很窄的文案强行造抽象 key；按页面命名即可。

## 实施顺序

每个工作包结束后运行编译和单元测试，避免大批量改动后集中排错。

### WP0：基础设施与消费者迁移（先做，单独验证）

1. 新增 `text/TextRef.kt`、`text/TextRefAndroid.kt`、`text/UserFacingError.kt`、`text/PersistedErrors.kt`。
2. 为全部自定义异常加 `UserFacingError` 和次级 `String` 构造器；文本仍是 `Raw`，行为不变。
3. `AppGraph.messages` 改为 `MutableStateFlow<TextRef?>`；新增 `show`/`showRaw`；把现有赋值机械化改成 `showRaw`。
4. 先改消费者，再改生产者（关键顺序）：
   - `MainActivity` 解析 Snackbar。
   - `DownloadScreen`、`StoredFilesScreen` 的错误展示改走 `persistedErrorText`。
   - UI 中所有 `failure.message`、`error.message` 改为 `userTextOr(...)`。
   - `WhisperCenter`/`TranslateCenter`/`DownloadCenter` 的持久化写入改为 `userTextOr(...)` 后解析。
   - `ClipboardImportController` 的 `message`/`state.error` 改为 `TextRef`。
5. 新增 `TextRefTest`、`PersistedErrorsTest`；编译、单测通过后再迁 throw 点。
6. 此阶段结束时应保持 UI 文案完全不变，只是通道类型变化。

### WP1：第二批 A —— 空状态、长帮助、对话框

重点文件：

- `ui/components/States.kt`：`LoadingState`/`ErrorState` 默认文案改为 `stringResource`。
- `ui/library/LibraryScreen.kt`、`LibraryWorkScreen.kt`、`FilePreviewScreen.kt`：空状态、关联作品说明、文件夹导入说明。
- `ui/download/DownloadScreen.kt`、`StoredFilesScreen.kt`：任务/文件空状态、删除任务说明。
- `ui/player/PlayerChrome.kt`、`PlaylistScreens.kt`：队列/字幕/播放列表空状态、定时关闭。
- `ui/asmr/OnlineScreens.kt`、`JapaneseAsmrVerificationScreen.kt`：搜索空状态、登录帮助、验证说明。
- `ui/settings/ModelSettingsScreen.kt`：听写/翻译帮助拆分。
- `ui/components/DeleteAudioDialog.kt`、`DlsiteDownloadDialog.kt`、`BiliImportDialog.kt`、`LibraryRows.kt` 的下载方式/缓存提示对话框。
- `ui/bilibili/BiliCollectionScreen.kt`、`CollectionCatalogue.kt`、`ui/youtube/YouTubeScreens.kt`：合集/列表空状态。

做法：逐屏替换 `Text("…")`、`EmptyState(title/message/actionLabel)`、`AlertDialog` 的标题/正文/按钮。空状态按评审 2.3 补齐下一步；长帮助按段落拆分。

### WP2：第二批 B —— 数据层错误

按簇迁移，每簇先改消费者（WP0 已做），再改 throw 点。

**E1：听写/翻译**

- `data/WhisperClient.kt`、`QwenFileTranscriber.kt`、`QwenCues.kt`、`WhisperModels.kt`、`OfflineWhisperModelManager.kt`、`OfflineWhisperTranscriber.kt`、`WhisperCenter.kt`、`TranslateClient.kt`、`TranslateCenter.kt`、`TranslationExecution.kt`。
- 替换 `WhisperClient` 中按 `error.message` 的分支为 `error.userText.resIdOrNull()` 或异常类型判断。

**E2：下载/音频库/更新/本地文件**

- `data/DownloadCenter.kt`、`ExternalFileSession.kt`、`SubtitleSource.kt`、`LibraryRepository.kt`、`WorkFolderMatch.kt`、`JaWorkFiles.kt`、`AppUpdate.kt`、`LibraryRemovalPolicy.kt`、`StoredFiles.kt`、`LocalAudio.kt`、`DlsiteOriginalStore.kt`、`playback/BiliPlaybackDataSource.kt`、`playback/PlaybackService.kt`。
- 约 97 处 `error/check/require` 改为 `UserFacingException`；哨兵值走 `PersistedErrors`。
- `recognizeNotice` 返回 `TextRef`；`LibraryRepository` 在返回 `String` 的边界解析。
- 先替换这些按文案判断的分支，再迁 throw 点：
  - `WhisperClient.kt:117` 的 `when (error.message)`
  - `AsmrClient.kt:171` 的 `when (error.message)`
  - `DlsiteClient.kt:406`、`LibraryRepository.kt:1296,1312,1322` 的 `contains("登录")` / `contains("无法连接")`
  - `LibraryViewModel.kt:358` 的 `== "未找到该作品"`
  - `DownloadCenter.kt:1210,1214,1216` 的哨兵/回退集合保持原样
- 审计所有 DAO 错误写入入口（`downloads().fail*`、`stopActive`、`whisper().fail*`、`translations().fail*`）：每个写入要么传入已解析的显示文本，要么传 `PersistedErrors` 哨兵；没有 Context 的写入保留原值，由 `persistedErrorText` 在读取时映射。

**E3：在线客户端**

- `bilibili/BilibiliClient.kt`、`BiliSearch.kt`、`BiliCollections.kt`
- `youtube/YouTubeClient.kt`、`YouTubeModels.kt`、`YouTubeSolver.kt`
- `dlsite/DlsiteClient.kt`、`DlsiteArchiveClient.kt`、`DlsiteArchiveExtractor.kt`
- `asmr/AsmrClient.kt`
- `japaneseasmr/JapaneseAsmrParse.kt`、`JaWorkClient.kt`、`JapaneseAsmrClient.kt`、`JapaneseAsmrHls.kt`、`JapaneseAsmrHlsDownload.kt`、`JaMega.kt`

做法：

- 动态服务端文本用 `TextRef.Raw(serverText)`，并加注释说明不可翻译；用户兜底句用 `Res`。
- `JaFailure` 改为 `messageRes`；`JapaneseAsmrException` 委托 `failure.userText`。
- `JA_WORK_FILE_MISSING`、`JA_UNSUPPORTED_PLAYLIST` 等控制流哨兵留在 `PersistedErrors` 或原常量位置，UI 展示时映射。

### WP3：第二条 C —— 通知

- `data/AudioDownloadService.kt`：通知标题 `正在下载音频`、Japanese ASMR 验证提示、整部作品数量文案。
- `data/SubtitleTaskService.kt`：`正在处理字幕`、排队/等待音频/准备任务、停止原因；`SubtitleTask.label` 改 `labelRes`。
- `data/OfflineModelDownloadService.kt`：`正在下载听写模型`、`正在校验和安装`、取消按钮、失败提示。
- `TaskProgressDisplay.audioNotificationState` 的默认值改为资源。
- Service 内用 `getString(R.string...)`；需要参数时用 `getString(id, args)`。
- 通知渠道 ID 和第一批渠道名保持不变。

### WP4：第三批 —— 其余一次性文案

按以下子包处理，每个子包独立编译/测试：

**B3.1 应用框架**

- `MainActivity.kt`：底部导航 5 个标签、页面标题、剪贴板提示、`"查找作品"`、定时关闭提示。
- `ui/components/Bars.kt`：`SelectionAction.label` 改 `labelRes`，选择栏动作。
- 复用第一批的 `common_*`/`a11y_*`，不要重复造 key。

**B3.2 设置、外观、关于、缓存、播放器**

- `ui/settings/SettingsScreen.kt`、`AppearanceScreen.kt`、`CacheScreen.kt`、`AboutScreen.kt`、`ModelSettingsScreen.kt`。
- 动态格式：缓存用量 `已用 %1$s / 上限 %2$s`、版本 `%1$s · %2$s`、模型大小 `下载 %1$s · 安装后占用 %2$s`、下载百分比 `%1$s · %2$d%% · %3$d / %4$d MiB`。
- `ui/theme/Accent.kt`：`AccentPreset.name` 改 `nameRes`；`ThemeMode.label` 改 `labelRes`。
- `ui/player/PlayerScreens.kt`、`PlayerChrome.kt`、`playback/DesktopSubtitleWindow.kt`、`playback/PlaybackService.kt`。
- `DesktopSubtitleWindow` 是普通 View，用 `context.getString`。

**B3.3 在线、B 站、YouTube、播放列表、剪贴板**

- `ui/asmr/OnlineScreens.kt`（约 138 条）、`JapaneseAsmrVerificationScreen.kt`、`WorkFactsBlock.kt`。
- `ui/bilibili/BiliBrowser.kt`、`BiliCollectionScreen.kt`、`BiliVideoScreen.kt`、`CollectionCatalogue.kt`、`BiliImportDialog.kt`、`BiliChoices.kt`、`BiliCollectionOrder.kt`。
- `ui/youtube/YouTubeScreens.kt`。
- `ui/playlist/PlaylistScreens.kt`。
- `ui/importing/ClipboardImportDialog.kt`、`ClipboardImportViewModel.kt`。
- `data/ClipboardImportInput.kt`：`ClipboardSource.label` 改 `labelRes`（品牌名也由资源控制）。
- `bilibili/BiliSearch.kt` 的排序/时长枚举、`ui/asmr/OnlineScreens.kt` 的 `OnlineSort`/`PieceKind`、`BiliCollectionOrder.CollectionSort`、`BiliChoices.BiliAction` 改 `labelRes`。

**B3.4 ViewModel 提示与通知**

- 22 个 `AndroidViewModel`；把 `graph.messages.value = "…"` 改为 `graph.show(R.string.x)`，动态计数用 `graph.show(R.string.x, n)`。
- 代表文件：`LibraryViewModel.kt`（约 28 处）、`OnlineScreens.kt`（约 13 处）、`PlayerScreens.kt`（约 9 处）、`DownloadScreen.kt`（约 7 处）、`FavoriteScreen.kt`（约 6 处）、`BiliBrowserViewModel.kt`、`BiliVideoScreen.kt`、`YouTubeScreens.kt`、`BiliCollectionViewModel.kt`、`PlaylistViewModel.kt`、`CacheScreen.kt`、`SettingsScreen.kt`、`ClipboardImportViewModel.kt`。
- 通知正文已在 WP3 处理；此处只处理 UI ViewModel。

**B3.5 进度、状态与动态格式**

- `data/TaskProgress.kt`：`ProgressResult` / `ProgressPhase` 的 `label` 改 `labelRes`。
- `data/TaskProgressDisplay.kt`：
  - `progressDuration` 的“时/分/秒”改为资源或 `TextRef`；
  - `commonText`、`detailLines`、`progressPartLabel`、`progressResultLabel`、`audioNotificationState` 返回 `TextRef`/`TextRef.Joined`；
  - `progressBytes`、`progressRate`、`fraction` 保持无中文的数值格式。
- `data/WorkFolderMatch.kt` 的 `recognizeNotice` 返回 `TextRef`。
- `data/TranslatePlatforms.kt`、`WhisperModels.kt` 的平台/模型显示名和默认值说明改为资源。
- `data/BiliImportRequests.kt` 的“请先完成当前的入库选择”改为 `TextRef` 或 `getString`。
- 计数模板：`已导入 %1$d 首`、`已加入 %1$d 个下载任务`、`已选 %1$d 项`、`%1$s · %2$d 个视频`、`排序：%1$s` 等。

同时落地 6.1–6.3：

- 任务页标题「下载任务」，分段「下载 / 字幕任务」；文件页「已下载文件」；设置入口同步。
- 「收藏在线列表」→「收藏到播放列表」；取消对话框「移出播放列表」，按钮「移出」/「保留」。
- 「SESSDATA 登录」→「粘贴登录信息」；对话框「粘贴 B 站登录信息」；字段保留「SESSDATA 或 Cookie」。

### WP5：收尾与验收

1. 全局搜索残留中文，逐项确认属于白名单。
2. 检查 XML 占位符数量、重复 key、`$` 模板残留。
3. 运行完整验证和 `lintDebug`。
4. 更新 `Android/docs/ui-copy-review-v1.4.md` 的实施记录，注明 P4 第二、三批完成情况和未执行项。

## 测试策略

### JVM 单元测试

- 新增 `TextRefTest`、`PersistedErrorsTest`。
- 断言异常文案的测试改为 `UserFacingError.userText` 资源 ID：
  - `data/AppUpdateTest.kt`
  - `data/ExternalFileDownloadTest.kt`
  - `dlsite/DlsiteArchiveTest.kt`
  - `dlsite/DlsiteArchiveRoutingTest.kt`
  - `youtube/YouTubeClientTest.kt`
  - `japaneseasmr/JaWorkDownloadTest.kt`、`JapaneseAsmrTest.kt`
  - 其他文案断言：
  - `data/ClipboardImportTest.kt`：`memory.messages` 改为 `List<TextRef>`，断言 `resIdOrNull()`。
  - `data/WorkFolderMatchTest.kt`
  - `data/TranslationReplyTest.kt`
- `TaskProgressTest.kt` 的 `commonText().contains("8.0×")` 改为 `debugString()` 或关键资源 ID。
- 保留 `IllegalStateException` 类型断言（服务清理依赖该类型）。

### 仪器测试

- 在测试工具中提供：
  ```kotlin
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)
  ```
- `onNodeWithText` / `onNodeWithContentDescription` 改为 `text(R.string...)`，避免测试里再复制一套中文。
- `createComposeRule()` 测试没有 Activity，使用 `InstrumentationRegistry.getInstrumentation().targetContext` 取资源，不要用 `activity.getString`。
- 代表文件：`AboutAndroidTest`、`LocalRemovalDialogAndroidTest`、`BiliCollectionUiAndroidTest`、`BiliImportDialogAndroidTest`、`ClipboardImportAndroidTest`、`BiliVideoRowAndroidTest`。
- 持久化哨兵断言保持不变（`"已中止"` 等）；它们测试数据库语义，不是 UI 文案。
- Compose UI 仪器测试仍受旧版 Espresso 反射限制；只要求 `compileDebugAndroidTestKotlin` 通过，不把运行失败当作回归。

## 验证

### Gradle

```sh
cd Android
export GRADLE_USER_HOME="$HOME/.gradle"
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-configuration-cache
./gradlew :app:lintDebug --no-configuration-cache
git diff --check
```

每个 WP 后至少运行 `compileDebugKotlin` + `testDebugUnitTest`；WP2/WP4 结束再跑完整命令。

### 静态检查

```sh
# UI 中不应再有中文字面量
LC_ALL=C rg -n '"[^"]*\p{Han}[^"]*"' app/src/main/java/com/nanahoshi/audioplayer/ui -g '*.kt'

# 数据层只允许白名单
LC_ALL=C rg -n '"[^"]*\p{Han}[^"]*"' app/src/main/java/com/nanahoshi/audioplayer -g '*.kt'

# 不应在 data/playback 中使用 stringResource
LC_ALL=C rg -n 'stringResource\(' app/src/main/java/com/nanahoshi/audioplayer/data app/src/main/java/com/nanahoshi/audioplayer/playback

# UI 中不应再有异常 message 直读
LC_ALL=C rg -n '\.message' app/src/main/java/com/nanahoshi/audioplayer/ui app/src/main/java/com/nanahoshi/audioplayer/playback

# 禁用词
LC_ALL=C rg -n '口令|普通播放列表|自建列表|整作|挂在其他曲目|匹配成功|JSON 对象|source 编号|HTTP [0-9]' app/src/main/java -g '*.kt'

# XML 中没有 $ 模板
LC_ALL=C rg -n '\$[A-Za-z{]' app/src/main/res/values -g '*.xml'

# 重复 key
rg -o 'name="[^"]+"' app/src/main/res/values -g '*.xml' | sed 's/.*name="//;s/"//' | sort | uniq -d
```

白名单只允许：`DlsiteGenres.kt`、`PersistedErrors.kt` 与 `Daos.kt` 中的数据库状态值、日志/注释、`testTag`、服务端内容字段。

### 手工检查

- 360 dp 宽、字体 1.0/1.3/2.0 下检查顶栏、徽章、FlowRow、对话框按钮不截断。
- TalkBack 抽查「已选 N 项」「下载与字幕任务，N 项进行中」、对象化「更多」、播放/暂停。
- Snackbar 文案长度与 `Long` 时长。
- 安装到真机或模拟器需要用户明确授权；未授权时只报告编译和单元测试结果。

### 验收标准

- 第二批、第三批范围内没有残留的用户可见中文字面量；`rg` 结果只剩白名单。
- 所有空状态都有下一步说明；所有失败状态都有原因和下一步；没有只写「失败」的页面。
- 用户文案中没有 HTTP 状态码、`JSON 对象`、`source 编号`、`整作`、`口令` 等禁用词。
- XML 中没有 `$` 模板；`%1$s`/`%1$d` 参数数量与调用一致；没有重复 key。
- `DlsiteGenres.kt`、Room schema、数据库版本、SQL 状态值未改；`"已中止"` 等持久化比较仍正常。
- `compileDebugKotlin`、`compileDebugAndroidTestKotlin`、`assembleDebug` 通过；单元测试全部通过（原 449 个，按新增测试相应增加）。
- `git diff --check` 通过；未提交、未推送。

## 风险与处理

| 风险 | 处理 |
| --- | --- |
| 生产者已改 `Res`、消费者仍读 `.message`，把 `res:#123` 显示或写库 | WP0 强制消费者先行；`rg '\.message'` 作为收尾门禁。 |
| `graph.messages` 类型波及约 102 处赋值 | 先机械化 `showRaw`，再逐簇迁移；每组编译。 |
| `"已中止"`、`"需要验证后继续"` 等比较被文案替换破坏 | 集中到 `PersistedErrors`；先替换比较再迁移展示文案；SQL 字面量不动。 |
| `error/check/require` 的异常类型被改变，Service 清理 catch 失效 | 统一用继承 `IllegalStateException` 的 `UserFacingException`。 |
| 按文案内容判断的分支（`contains("登录")`、`== "未找到该作品"`） | 改为异常类型、`resIdOrNull()` 或 `userText` 相等比较。 |
| Room 中旧错误文本不会随语言更新 | 本轮无 `values-en`，可接受；未来加英文时再考虑错误码列。 |
| 占位符/转义错误 | 每个键落地时同步调用点；收尾跑 `lintDebug`、占位符检查、重复 key 检查。 |
| 资源 key 冲突或语义过窄 | 按域拆文件；key 用 `screen_component_purpose`；一次性文案按页面命名。 |
| 测试大面积失败 | 按 WP 分批改、分批跑；先改异常定义，再改调用点，最后改断言。 |

## 实施顺序总览

1. WP0：`TextRef`、`PersistedErrors`、异常基类、消息通道、消费者迁移（单独验证，行为不变）。
2. WP1：空状态、长帮助、对话框（纯 Compose，风险最低）。
3. WP2：数据层错误 E1/E2/E3（核心改造）。
4. WP3：通知正文。
5. WP4：第三批一次性文案 + 6.1–6.3 命名。
6. WP5：静态检查、完整编译/测试、文档记录。