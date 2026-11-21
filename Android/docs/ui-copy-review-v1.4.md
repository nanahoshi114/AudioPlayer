# Android 界面文案审查 v1.4

审查日期：2026-11-20

审查对象：`Android/` 工程当前工作区（含未提交改动）。

对照文档：`Android/docs/ui-copy-review.md`（2026-11-07）。

说明：本文只记录审阅结果，不修改代码。行号以本次审查时的工作区为准；后续改动会使行号漂移，定位时优先搜索文案原文。路径默认省略前缀 `app/src/main/java/com/nanahoshi/audioplayer/`。

> 实施记录（2026-11-20）：P0 的 12 项和 0.4 的两项术语调整已落到代码；P1 已按 1.1 词表补齐遗留术语，并统一 `contentDescription`（含徽章数量、排序、播放列表播放、对象化「更多」）；P2 已按 2.2 表改写错误文案、按 2.3 补齐空状态下一步，并对长结果使用 `SnackbarDuration.Long`；P3 已处理 4.1–4.3 的静态项（空格、徽章/标题截断、错误详情、对话框按钮长度、状态 `liveRegion`）；P4 第一批已迁移通用动作/状态、通知渠道名和无障碍描述。P4 第二批/第三批已建立 `text/TextRef`（资源 ID + 参数、`Raw`、`Joined`）、`UserFacingError`、`UserFacingException`、`PersistedErrors` 和 `persistedErrorText`，并完成以下范围：`ui/` 全部用户可见中文字面量已清零，按域拆分为 `strings_errors/help/library/download/player/settings/online/bilibili/youtube/playlist/import/notifications/progress.xml`；所有页面、对话框、空状态、长帮助、ViewModel 提示、标签枚举、进度阶段/结果、通知与剪贴板导入的界面文案均改用 `stringResource` 或 `getString`；数据层已迁移 `WhisperClient`、`QwenCues`、`QwenFileTranscriber`、`WorkFolderMatch`、`TaskProgress`/`TaskProgressDisplay`、`DownloadCenter`、`LibraryRepository`、`WhisperCenter`、`TranslateCenter`、`ExternalFileSession`、`AppUpdate`、`ClipboardImportController`、`YouTubeClient`、`BilibiliClient`/`BiliSearch` 及部分服务文案。`compileDebugKotlin`、`compileDebugAndroidTestKotlin`、`assembleDebug` 与 453 个 Debug 单元测试通过。

> 实施记录（2026-11-21）。P4 第二、三批的代码和静态验收已完成，覆盖翻译与听写、DLsite、asmr.one、Japanese ASMR、播放、桌面字幕、通知及在线合集。下载中断、任务取消与超时、等待验证等状态使用固定入库值，展示时解析字符串资源。新增 `strings_dlsite.xml` 和 `strings_japaneseasmr.xml`。本轮静态扫描在 `ui/` 中无中文字符串命中，其他命中为内容数据、固定入库值、内部参数校验及模型提示词等白名单项。Debug 编译、设备测试编译和 APK 构建通过，460 个 Debug 单元测试全部通过。设备测试实跑及 4.4 的 360 dp 截图复核待安装授权。详见[剩余迁移执行记录](plans/2026-11-20-p4-copy-resource-migration-remaining.md#执行记录2026-11-21)。


> P0–P2 补查记录（2026-11-21）。补齐混选删除、收藏夹重试、整部作品下载摘要、设置、播放列表、剪贴板、视频多选与菜单、播放器、在线作品和文件页的遗留提示。整部作品摘要按实际文件区分新增、已有、保留原版本、未能保存和远端清单中已无的文件。听写地址格式提示与关联失败详情可以显示到用户界面。任务列表区分中断、翻译和润色，超长听写出错信息可打开完整详情；翻译详情保留具体结构原因、服务状态码及清洗后的模型片段。
> Debug 编译、设备测试编译和 APK 构建通过，461 个 Debug 单元测试通过。本机 Android 16 模拟器的设备实跑与截图结果见 4.4。TalkBack 播报和真机验证待执行。

## 0. 摘要

### 0.1 扫描结果

- `res/values/strings.xml` 只有一条 `app_name`；`app/src/main/java` 中没有 `stringResource`，也没有 `getString(R.string…)`。界面文案全部硬编码。
- 含汉字的 Kotlin 文件 136 个，汉字字符串字面量 2 079 条。
  - 界面层（`ui/` 与 `MainActivity.kt`）925 条，分布在 45 个文件。
  - 数据／播放层 438 条（`data/` 398 条、`playback/` 40 条）。
  - 在线客户端层 251 条（不含 `dlsite/DlsiteGenres.kt` 的 320 条标签数据）。
  - 其余为主题色名、模型名、数据库语句等。
- 高频字面量：`"取消"` 35、`"生成失败"` 22、`"已中止"` 17、`"更多"` 14、`"重试"` 10、`"加入音频库"` 10。重复本身不一定是问题，但 `"生成失败"`、`"操作失败，请重试"` 这类兜底文案缺少上下文。
- `DlsiteGenres.kt` 的标签属于内容数据，不应迁入界面资源；通知渠道名、无障碍描述则属于界面文案，应一并纳入。

### 0.2 总体判断

已经做好的部分：

- 核心动作词明显收敛：「加入音频库」「下载」「从音频库移除」「取消本地关联」基本各司其职。
- 排序项已经写明方向，例如「发售日（新→旧）」「价格（低→高）」，不再要求用户猜。
- B 站手动粘贴登录已区分「已保存」和「已登录」。
- 关于页优先显示版本名，不再把 `android-1.3` 原样摆在主位置。
- 下载任务页标题已从「下载」改为「下载与任务」。
- `DeleteAudioDialog` 提到的「设置 → 下载管理 → 字幕」入口现在真实存在。

仍然突出的问题：

1. 术语仍有五组混用：口令／API Key、普通播放列表／自建列表、在线列表／收藏、整作／整部作品、缓存／临时文件。
2. 错误文案仍大量使用开发语言：JSON、`source` 编号、HTTP 状态码、复用、关联冲突、匹配成功、挂在其他曲目上。
3. 听写任务失败时，完整错误被塞进单行 `StatusBadge`，在窄屏会被省略号截断。
4. 多段式操作结果通过默认时长的 Snackbar 展示，用户来不及读完。
5. 空状态和错误提示仍有不少「死胡同」：只说明失败，不说明下一步。
6. 设置里的选择弹窗用「取消」表示关闭，但选项点击即生效。
7. 中英混排和数字单位空格不统一。
8. 无障碍描述过度使用「更多」。

### 0.3 最值得先改的 12 处

#### 1. 听写失败原因被单行徽章截断

位置：`ui/download/DownloadScreen.kt:379`，`ui/components/Badges.kt:51-68,167-176`。

当前：

```kotlin
DownloadStatus.FAILED -> job.error ?: "失败"
```

`job.error` 直接作为 `StatusBadge` 的文本；`StatusBadge` 最终调用 `LabelBadge`，而 `LabelBadge` 设置了 `maxLines = 1`、`softWrap = false`、`TextOverflow.Ellipsis`。错误可能是「无法连接听写服务」「该听写模型已移除，请换用其他模型重新生成」这类完整句子，在窄屏会显示成「无法连接听…」。

建议：徽章固定显示「失败」；在徽章下方用 `bodySmall` 显示 `job.error`，与 `DownloadRow` 的 `DownloadScreen.kt:322-324` 保持一致。听写完成但翻译失败时，徽章用「翻译失败」，错误详情另起一行。`TranslationJobRow` 已经采用「失败 + 详情」模式，听写行应与其一致。

#### 2. 混合删除对话框的「确认」和「删除音频和字幕」含义不完整

位置：`ui/components/DeleteAudioDialog.kt:21-45`，`ui/library/LibraryViewModel.kt:536-541`，`ui/library/LibraryScreen.kt:188-190`。

当前组合：

- 只有下载副本：标题「删除文件」，确认「删除」。
- 下载副本 + 本地关联：标题「删除副本并取消本地关联」，确认「确认」。
- 下载副本 + 生成字幕：标题「音频带有生成字幕」，确认「删除音频和字幕」，次要「只删除音频」。
- 下载副本 + 关联 + 字幕：确认仍是「删除音频和字幕」，没有提到取消关联；次要仍是「保留生成字幕」。

问题：

- 「确认」没有说明会做什么，是高风险操作里最弱的按钮文案。
- 正文第一句说「将删除 N 个下载副本，并取消 M 首曲目的本地关联」，第二句却说「待删除的 M 个文件中」，把「删除副本」和「取消关联」混成同一类。
- 混选时，「删除音频和字幕」没有覆盖「取消本地关联」。

建议：

- 动作标签（`LibraryViewModel.kt:540`）按内容动态显示：只有下载副本「删除已下载文件」；只有关联「取消本地关联」；两者都有「删除副本并取消关联」。
- 对话框标题与动作标签一致。
- 正文拆成两条：
  - 「将删除 N 个 App 内下载副本，并取消 M 首曲目的本地关联。手机上的原文件会保留。」
  - 「其中 K 个音频带有生成字幕。保留字幕后，重新下载同一音频时会自动匹配。」
- 按钮：有字幕时用「全部删除」/「保留字幕」/「取消」；无字幕时用「删除副本并取消关联」/「取消」。不要再用「确认」。
- `DeleteAudioDialog` 在 `StoredFilesScreen` 中复用时，正文里的「生成的字幕文件也可在『设置 → 下载管理 → 字幕』中单独删除」会变成指回当前页。建议改为中性句：「也可以只删除生成的字幕，音频会保留。」

#### 3. 「口令」与「API Key」混用

位置：UI 标签在 `ui/settings/ModelSettingsScreen.kt:129,161`；错误与帮助在 `data/WhisperClient.kt:72,89,90,118`、`data/WhisperCenter.kt:838`、`data/TranslateClient.kt:335`、`data/QwenFileTranscriber.kt:219`、`ui/settings/ModelSettingsScreen.kt:529`。

当前页面让用户填写「API Key」，任务失败时却说「没有听写口令」，本机模型说明又说「不需要填写地址和口令」。同一个东西有三种叫法。

建议全部改为「API Key」：

- 「还没有填写听写 API Key，请到『设置 → 听写和翻译』填写后重试。」
- 「还没有填写翻译 API Key，请到『设置 → 听写和翻译』填写后重试。」
- 「模型已下载，在本机运行，不需要填写地址和 API Key。」

#### 4. 「普通播放列表」与「自建列表」混用

位置：`ui/bilibili/BiliCollectionScreen.kt:139,218-219` 使用「复制为普通播放列表」；`data/LibraryRepository.kt:1148,1157,1172,1181` 使用「请先复制为自建列表」。

建议统一为「自建播放列表」：

- 对话框标题：「复制为自建播放列表」。
- 说明：「复制后不会随 B 站更新，可以在播放列表里自行编辑。」
- 错误：「在线列表不能直接编辑，请先复制为自建播放列表。」

#### 5. 「收藏在线列表」与播放列表的关系不清楚

位置：`ui/bilibili/BiliCollectionScreen.kt:79-80,149-152`。

当前：「收藏在线列表」/「已收藏在线列表」/「取消收藏在线列表」。这些条目实际出现在「播放列表」页；「收藏」又与 B 站收藏夹同词，用户会误以为进入 B 站收藏夹。

建议：

- 动作：「收藏到播放列表」。
- 状态：「已收藏到播放列表」。
- 取消对话框标题：「移出播放列表」；正文「只会移除这份在线列表和本地目录缓存；已加入音频库的曲目、下载文件和当前队列会保留。」按钮「移出」/「保留」。

#### 6. 设置选择弹窗的「取消」实际不会撤销

位置：`ui/settings/CacheScreen.kt:102-104`，`ui/settings/SettingsScreen.kt:208`。

缓存上限和同时下载数量都是点击选项立即写入并关闭对话框，底部按钮却写「取消」。用户会以为点「取消」能撤销刚才的选择。

建议：改为「关闭」。如果产品希望可撤销，应改成「保存」/「取消」，并在确认时才写入；当前实现不适合「取消」语义。

#### 7. YouTube 的「未保存」和批量加入提示会误导

位置：`ui/youtube/YouTubeScreens.kt:102-103,176`。

- 列表行对未入库、未下载的视频显示「未保存」。「保存」在别处表示保存设置或 Cookie，用户不会理解为「加入音频库」。建议「未加入音频库」或直接隐藏这行状态。
- `saveSelection()` 固定提示「已加入音频库」，即使所选视频全部已在库中。建议统计实际新增：「已加入 N 个视频；M 个已在音频库。」如果保存接口不能返回新增数，至少改成「已处理所选视频，可在音频库查看」。

#### 8. B 站收藏夹空状态把原因猜死了

位置：`ui/library/FavoriteScreen.kt:191-195`。

当前：

> 没有 B 站收藏夹
> 登录信息可能还没生效，请返回后重新进入收藏夹。

问题：账号本来没有收藏夹时也会看到这段；「返回后重新进入」不是可执行的操作，也没有说明去哪里检查登录。

建议：

- 标题：「没有可显示的收藏夹」。
- 正文：「如果账号里有收藏夹，可能是登录信息未生效。请到『我的』确认已登录后重试。」
- 若路由允许，增加按钮「去登录」；否则提供「重试」按钮。

#### 9. 文件预览的「还没有下载到本机」是死胡同

位置：`ui/library/FilePreviewScreen.kt:149,276,302-304`。

当前：`"这个文件还没有下载到本机"`，没有说明去哪里下载。用户只能退出。

建议：

- 「这个文件还没有下载到本机。请到作品页下载完整原始包后再打开。」
- 「此文件类型暂不支持应用内预览」下方补一句：「可以交给其他应用打开。」
- 「没有可打开此文件的应用」→「没有找到能打开这种文件的应用，请安装后重试。」
- 「无法用其他应用打开这个文件」→「无法用其他应用打开，文件可能已损坏或被移动。」

#### 10. 整作下载完成提示是开发统计

位置：`data/JaWorkFiles.kt:348`。

当前：

> 整作下载完成，复用 3 项，跳过 12 项，保留 1 项旧文件

「复用」「跳过」「保留旧文件」是内部流程词，用户不知道哪些文件最终可用。

建议：

> 整部作品已下载完成：新增 3 个文件，跳过 12 个已存在的文件，保留 1 个未在清单中的旧文件。

同时把面向用户的「整作」统一改为「整部作品」；数据层只返回结构化计数，展示句放到 UI 层更容易维护。

#### 11. 「下载准备已结束」

位置：`MainActivity.kt:751`。

当前：用户取消 Japanese ASMR 下载验证流程时提示「下载准备已结束」。这句话既不像成功，也不像取消。

建议：改为「已取消下载」。如果之后自然完成，用「下载准备已完成」。

#### 12. 数字单位与中英混排空格不统一

位置与当前：

- `data/CacheStore.kt:65-71` 返回「100MB」「200MB」「500MB」「1GB」「2GB」，而 `formatCacheBytes` 返回「12.3 MB」。设置摘要同一行出现「已用 12.3 MB / 上限 200MB」。
- `data/QwenFileTranscriber.kt:43`「这首超过 1GB，千问接不了」。
- `data/WorkFolderMatch.kt:62-65`「DLsite匹配成功」「asmr.one匹配成功」「DLsite未连接且asmr.one匹配失败」「DLsite/asmr.one均匹配失败」。
- `data/ClipboardImportController.kt:123`「asmr.one/DLsite连接失败，请检查网络」。
- `ui/settings/ModelSettingsScreen.kt:513` 使用全角空格分隔来源、百分比和大小。

建议：

- 统一「100 MB」「200 MB」「500 MB」「1 GB」「2 GB」。
- 「1 GB」。
- 「DLsite 匹配成功」「asmr.one 匹配成功」「无法连接 DLsite，asmr.one 也未匹配」「DLsite 和 asmr.one 均未匹配」。
- 「无法连接 asmr.one 或 DLsite，请检查网络后重试」。
- 全角空格改为「 · 」：「GitHub · 42% · 128 / 512 MiB」。
---

### 0.4 本轮采纳的两项调整

以下两项由产品侧确认，直接作为后续改文案的基准。

#### A. 「下载到本地」改为「下载整部作品」

位置：`ui/library/LibraryRows.kt:299,448`、`ui/library/LibraryWorkScreen.kt:185`。

实际行为：这三个入口都调用 `LibraryViewModel.cacheFolder()`，再进入 `cacheWork()`。`cacheWork()` 会先通过 `workRootOf()` 找到作品根目录，再按来源下载整个作品：DLsite 下载完整原始包，asmr.one 下载作品文件，Japanese ASMR 下载全部或所选篇目。即使在作品的子文件夹上点这个菜单，也会下载整部作品，而不是只下载当前文件夹。

因此「下载到本地」会让人以为是“下载当前看到的文件”，应统一改为「下载整部作品」。单个曲目仍用「下载音轨」；多选栏的「下载」建议改为「下载所选」，避免和整部作品混淆。

同步影响：

- `Android/README.md:63,77,81,246,299` 和 `Android/docs/japaneseasmr-api.md:289` 仍写「下载到本地」，改代码时一并同步。
- 选择来源的后续弹窗保持「选择下载来源」；DLsite 选项仍叫「下载完整原始包」，因为它是整部作品的一种下载方式，不是另一个层级的动作。
- Japanese ASMR 有多个篇目时，后续弹窗应说明「这部作品有多个篇目，可以下载全部，或只选一篇」，避免「整部作品」与只选一篇产生歧义。

#### B. Japanese ASMR 的「文章」改为「篇目」

「文章」在中文里首先联想到文字内容，而这些页面实际是含有音轨、封面和作品资料的站内发布页；用户看到「下载哪些文章」「刷新文章」会以为是在处理文本。建议统一用「篇目」：

- 「同一作品在 Japanese ASMR 有多个篇目。」
- 「下载哪些篇目？」「全部篇目」「刷新JA篇目」。
- 「这篇需要验证」「无法读取这个篇目的音轨」「所选篇目提供了不同的下载入口」。

「刷新篇目」单独出现时，用户不知道刷新的是哪个来源，因此按钮统一写「刷新JA篇目」，对话框标题写「刷新哪些JA篇目？」。JA 是 Japanese ASMR 的缩写，按钮空间有限时使用；如果后续发现用户不熟悉，可展开为「刷新 Japanese ASMR 篇目」。按 4.1 的中英混排规范，最终 UI 也可显示为「刷新 JA 篇目」；这里保留你指定的连写形式。

选择「篇目」的理由：

- 它准确表达“同一部作品下的多次发布／多篇内容”，与当前按 RJ 归并、一个作品可关联多个站内页面的数据模型一致。
- 它不引入文字／网页的暗示，也能覆盖页面上可能出现的非音频附件。
- 它在项目里已有先例：`Android/README.md:191` 和 `docs/japaneseasmr-api.md:281` 已经使用「篇目」。
- 单字「篇」可作为指代：「这篇需要验证」「这篇的音轨」，口语自然。

不推荐的替代：

- 「章节」：会和播放页的 HLS 章节冲突。
- 「页面」：会和 App 内的页面导航混淆，也没有说明内容是什么。
- 「音声页」：对不熟悉日系术语的用户反而增加理解成本。

建议映射：

| 当前 | 建议 |
| --- | --- |
| 文章（作为内容实体） | 篇目 |
| 这篇文章 / 该文章 | 这篇 / 这个篇目 |
| 文章原网页 | 原网页 |
| 文章播放器 | 页面播放器 |
| 文章音轨 | 这篇的音轨 |
| 文章需要验证 | 这篇需要验证 |
| 刷新文章 | 刷新JA篇目 |
| 下载哪些文章 / 全部文章 | 下载哪些篇目 / 全部篇目 |
| 打开文章 | 打开原网页 |
| 在 App 内验证文章 | 在 App 内验证当前篇目 |
| 文章已从音频库移除 | 篇目已从音频库移除 |

涉及位置：

- `ui/player/PlayerChrome.kt:391`
- `ui/importing/ClipboardImportDialog.kt:63`
- `ui/download/DownloadScreen.kt:321`
- `ui/download/StoredFilesScreen.kt:245,408`
- `ui/asmr/OnlineScreens.kt:717,1911,1914`
- `ui/library/LibraryWorkScreen.kt:187`
- `ui/library/LibraryRows.kt:601-603,614`
- `ui/library/LibraryViewModel.kt:710`
- `ui/components/JapaneseAsmrBrowser.kt:12`
- `ui/asmr/JapaneseAsmrVerificationScreen.kt:334,348,358,381,385`
- `data/ClipboardImportController.kt:122`
- `japaneseasmr/JapaneseAsmrParse.kt:60,62,66,68`

实现注意：`data/DownloadCenter.kt`、`data/LibraryRepository.kt` 和 `data/db/Daos.kt:225` 里有持久化的「文章已从音频库移除」等字符串，同时被 SQL、错误比较和 UI 直接展示。它们要么整体改为「篇目」并同步所有比较与迁移，要么保留为内部状态值、在 UI 层映射为「篇目」。不要只改 UI 文案而留下数据层另一套说法。

文档同步：`Android/README.md`、`Android/docs/japaneseasmr-api.md` 面向用户的叙述建议改为「篇目」；接口文档首次出现时可写「篇目（站内文章）」，只在 WordPress 接口层面保留「文章」。

---

## 1. 术语与动作统一

### 1.1 建议统一的词表

| 概念 | 当前写法 | 建议 | 说明 |
| --- | --- | --- | --- |
| 把内容登记到音频库 | 「加入音频库」「保存」「导入」「已导入」 | 在线内容统一「加入音频库」；本地文件保留「导入」 | 「保存」只用于设置、Cookie 和文件 |
| 用户主动保存的音频 | 「下载」「下载到本地」「下载音轨」「缓存」 | 作品用「下载整部作品」；单轨用「下载音轨」；多选用「下载所选」；不写「下载到本地」 | 与 AGENTS 的「下载／缓存」约定一致 |
| 自动清理的临时文件 | 「缓存」「临时缓存」「临时文件」 | 保留「缓存」，首次出现时解释 | 「缓存上限」必须说明会自动清理 |
| 从音频库移除条目 | 「移除」「删除」「取消识别」 | 一律「从音频库移除」 | 不清磁盘文件，别用「删除」 |
| 删除 App 内副本 | 「删除已下载文件」「删除已下载的文件」 | 「删除已下载文件」 | 与「取消本地关联」成对出现 |
| 解除用户文件关联 | 「取消本地关联」「取消识别」 | 「取消本地关联」 | 「识别」是动作，「关联」是结果 |
| 在线收藏的 B 站合集／系列 | 「在线列表」「在线播放列表」「收藏在线列表」 | 动作「收藏到播放列表」；对象「在线列表」 | 它出现在「播放列表」页 |
| 用户可编辑的播放列表 | 「自建列表」「普通播放列表」「播放列表」 | 「自建播放列表」 | 与「在线列表」相对 |
| DLsite 完整包 | 「完整原始包」「完整原始压缩包」「压缩包」 | 「完整原始包」 | 首次出现可在帮助里解释 |
| 一部 Japanese ASMR 作品的全部文件 | 「整作」「整部作品」「整包」 | 面向用户统一「整部作品」 | 「整作」是内部简写 |
| Japanese ASMR 的站内发布页 | 「文章」 | 「篇目」；指代时用「这篇」 | 避免让用户以为只有文字内容；见 0.4-B |
| 听写／翻译服务的密钥 | 「口令」「API Key」「Token」 | 「API Key」 | 与设置页标签一致 |
| 任务结束 | 「已中止」「中止」「失败」「中断」 | 用户主动停止「已中止」；系统停止「已中断」；出错「失败」 | 目前基本正确，不要混用 |
| 网页验证 | 「验证」「网页验证」「在 App 内验证」「站点验证」 | 保留「验证」，按范围加限定 | Japanese ASMR 验证页可用「网页验证」 |
| 试听／预览 | 「试听」「预览」「查看」 | 音频用「试听」；图片／文本用「预览」 | 当前基本一致 |

### 1.2 仍需要处理的具体冲突

1. **「口令」6 处**：`data/WhisperClient.kt:72,118`、`data/WhisperCenter.kt:838`、`data/TranslateClient.kt:335`、`data/QwenFileTranscriber.kt:219`、`ui/settings/ModelSettingsScreen.kt:529`。按第 0.3 节第 3 条统一为「API Key」。
2. **「普通播放列表」3 处 / 「自建列表」4 处**：`ui/bilibili/BiliCollectionScreen.kt:139,218-219` 与 `data/LibraryRepository.kt:1148,1157,1172,1181`。统一为「自建播放列表」。
3. **「整作」19 处**：包括「整作下载失败」13 次、「整作下载」「整作文件已在本机」「整作下载完成」等。建议面向用户全部改为「整部作品」，任务标题可写「《RJ…》整部作品下载」。内部代码字段名不必改。
4. **「在线列表」5 处**：集中在 `ui/bilibili/BiliCollectionScreen.kt`。按第 0.3 节第 5 条改为「收藏到播放列表 / 移出播放列表」，正文再说明对象是在线合集或系列。
5. **「保存」56 处**：多数是设置页的「保存」「已保存」，合理。但 `data/ClipboardImportController.kt`、`ui/importing/ClipboardImportViewModel.kt` 里的「已加入音频库」与设置页的「已保存」不要互相替代；前者指入库，后者指配置已写入。
6. **「本地」的双重含义**：筛选里的「本地」指本机文件；「下载整部作品」指把作品保存到手机。两者都合理，但按钮上不要只写「本地」；当前没有这个问题，保持即可。
7. **「文章」与「篇目」**：`ui/` 中约 20 处、数据层约 20 处用「文章」指代 Japanese ASMR 的站内发布页（含 SQL 和错误比较）。按 0.4-B 统一为「篇目」，动作里的「打开文章」改为「打开原网页」。

### 1.3 来源名与空格

- 「B 站」全部带空格，当前没有发现「B站」，保持。
- 「YouTube」「DLsite」「asmr.one」「Japanese ASMR」前后应有半角空格。当前大部分正确，`data/WorkFolderMatch.kt` 和 `data/ClipboardImportController.kt:123` 例外，见第 0.3 节第 12 条。
- 「UP 主」带空格，正确。
- `data/ClipboardImportController.kt:124` 的模板 `"${source.label} 视频读取失败"` 对 YouTube 正确（「YouTube 视频读取失败」），对 B 站会变成「B 站 视频读取失败」。建议改为按来源分支：`"B 站视频读取失败"` / `"YouTube 视频读取失败"`，或直接用「视频读取失败，请检查网络后重试」。

---

## 2. 错误提示、空状态与提示时长

### 2.1 统一写法

每条面向用户的错误至少回答两件事：**发生了什么**、**下一步怎么做**。能确定原因时再加**为什么**。不要写「操作失败」「生成失败」就结束。

建议的四种模板：

- 网络：「无法连接听写服务，请检查网络后重试。」
- 鉴权：「登录已失效，请到『我的』重新登录。」
- 内容：「这个视频需要登录或验证，请更新 Cookie 后重试。」
- 本地文件：「文件不存在，可能已被移动或删除。请重新下载或重新关联。」

目前 `data` 层大量抛出的是「原因片段」，不是完整句子。建议在数据层保留简洁原因，在 `ui` 层组装「原因 + 下一步」；至少不要把只有开发人员能理解的异常直接塞进 `graph.messages` 或任务 `error`。

### 2.2 需要改写的错误文案

| 位置 | 当前 | 问题 | 建议 |
| --- | --- | --- | --- |
| `data/QwenFileTranscriber.kt:43` | 这首超过 1GB，千问接不了 | 口语化、缺少替代方案、单位无空格 | 音频超过 1 GB，qwen-audio-3.1-asr 无法处理。请换用其他听写模型。 |
| `data/WhisperClient.kt:72,89,90` / `data/WhisperCenter.kt:833,838` | 没有听写口令 / 没有听写地址 / 听写地址不正确 | 「口令」与 UI 不一致；「地址不正确」没有说明格式 | 还没有填写听写 API Key / 还没有填写听写服务地址 / 服务地址格式不正确，请检查是否以 http:// 或 https:// 开头 |
| `data/TranslateClient.kt:335` | 没有翻译口令 | 同上 | 还没有填写翻译 API Key |
| `data/QwenCues.kt:10,25,31` | 生成失败 / 没有分段时间 | 过于笼统；时间信息是技术细节 | 听写失败，请重试 / 服务返回的字幕没有时间信息，请重试 |
| `data/TranslateClient.kt:117-155` | 返回不是 JSON 对象 / 缺少 captions 数组 / source 编号超出范围 / source 编号不连续或重复 / 日文为空 / 中文为空 | 开发语言；用户不知道 `source` 是什么 | 模型返回的内容无法解析 / 返回的字幕编号不正确 / 有句子没有日文 / 有句子没有译文。详细原因放在「错误详情」里。 |
| `data/TranslateClient.kt:371` | 翻译服务请求失败（HTTP 500） | 直接暴露状态码 | 翻译服务暂时不可用，请稍后重试（详情里保留状态码） |
| `data/TranslateClient.kt:381` | 翻译服务没有返回正文 | 技术化 | 翻译服务没有返回内容，请重试 |
| `data/TranslateClient.kt:385` | 翻译返回被截断 | 技术化 | 译文不完整，请重试 |
| `data/WhisperCenter.kt:762` | 该听写模型已移除，请换用其他模型重新生成 | 好，但可更完整 | 这个听写模型已被移除。请在『设置 → 听写和翻译』换用其他模型后重新生成。 |
| `data/WhisperCenter.kt:657-658` | 音频文件不可用 / 下载任务已不存在 | 「不可用」没有原因 | 音频文件不可用，请重新下载后重试 / 下载任务已不存在，请重新发起 |
| `data/WhisperCenter.kt:588` | 下载任务已不存在或音频文件不可用 | 两个原因混在一起 | 按实际分支分别提示；无法区分时用「听写前置的下载已不存在，请重新发起」 |
| `data/SubtitleSource.kt:76` | 生成的字幕文件损坏，可在「更多」里删除后重新生成 | 「更多」指代不明 | 生成的字幕文件已损坏。请在字幕全文页的「更多」中删除后重新生成。 |
| `data/SubtitleSource.kt:483` | B 站字幕需要登录后显示，可在「我的」重新登录 | 好 | 保持；可补「更新登录信息后重新打开播放页」 |
| `data/SubtitleSource.kt:484` | B 站字幕读取失败，可稍后再试 | 缺少原因 | B 站字幕读取失败，请稍后重试；如果一直失败，请检查登录信息 |
| `data/ExternalFileSession.kt:59` | 这个文件还没有下载到本机，请重新下载完整原始包 | 好 | 保持 |
| `data/ExternalFileSession.kt:84` | 无法准备这个文件，请检查网络、登录状态或剩余空间后重试 | 好，但三个原因太长 | 无法准备这个文件。请检查网络和登录状态，并确认手机剩余空间后重试。 |
| `data/ExternalFileSession.kt:127` | 服务器返回了网页，请检查登录状态后重试 | 技术化 | 服务器没有返回文件，登录可能已失效。请重新登录后重试。 |
| `data/ExternalFileSession.kt:140` | 无法读取文件或跳转次数过多 | 技术化 | 无法读取这个文件，请稍后重试 |
| `data/LibraryRepository.kt:612-614` | NAME 匹配成功，已关联 N 首；M 首的文件已经挂在其他曲目上，没有改动 | 「匹配」「挂在其他曲目上」是内部语言 | 「《NAME》已关联 N 首。另有 M 首的文件已属于其他曲目，未做改动；如需调整，请先取消那边的本地关联。」 |
| `data/LibraryRepository.kt:585` | 对不上，多出来的内容：path | 生硬 | 这个文件夹里有作品之外的内容：path。请移除后再关联。 |
| `data/LibraryRepository.kt:724,736,738,743,828,839` | 文章资料不完整／文章已从音频库移除／文章作品号已变化／文章音轨已失效 | 「文章」与篇目术语不一致 | 篇目资料不完整／篇目已从音频库移除／篇目作品号已变化／这个篇目的音轨已失效。注意：这些字符串有的会被 SQL 或比较逻辑复用，改文案时需同步数据层，见 0.4-B |
| `data/LibraryRepository.kt:850` / `japaneseasmr/JapaneseAsmrParse.kt:61` | 页面结构或内容不可用 | 技术化 | 这个页面的内容暂时读不出来，请稍后重试或打开原网页确认 |
| `data/LibraryRepository.kt:926` | 没有有效来源 | 与剪贴板弹窗的「没有有效来源，无法加入音频库」不一致 | 没有有效来源，无法加入音频库 |
| `data/DownloadCenter.kt:1020` | 下载正在中止，请稍后重试 | 语序别扭 | 正在中止下载，请稍后重试 |
| `data/DownloadCenter.kt:1117` | 音频已经不在库里 | 口语且缺主语 | 这首音频已不在音频库，请重新加入后下载 |
| `data/DownloadCenter.kt:409` | $site 整作下载需要验证后继续 | 「整作」内部词；`$site` 可能是 Buzzheavier | 这份作品下载需要网页验证。请在打开的网页中完成验证后继续。 |
| `data/DownloadCenter.kt:555` | 原始文件已解压并加入作品；N 个文件已关联其他曲目，保持不变 | 「关联其他曲目」内部词 | 原始文件已解压并加入作品。另有 N 个文件已属于其他曲目，未做改动。 |
| `data/DownloadCenter.kt:1213` | 完整原始包处理失败，请重试 | 可以，但可说明阶段 | 完整原始包下载或解压失败，请重试 |
| `data/DownloadCenter.kt:1216` | 整作下载失败，请重试 | 「整作」 | 整部作品下载失败，请重试 |
| `data/TaskProgress.kt:28` | 获取上传凭证 | 内部流程 | 准备上传 |
| `data/TaskProgress.kt:30` | 服务端状态暂不可确认 | 技术化 | 暂时无法确认服务端进度 |
| `data/TaskProgress.kt:30` | 恢复远端任务 | 「远端」技术化 | 恢复服务端任务 |
| `data/TaskProgressDisplay.kt:78` | 统计仅包含本进程恢复后的运行 | 技术化 | 以下数据从本次恢复后开始统计 |
| `data/TaskProgressDisplay.kt:99` | 内部重试：N 次 | 「内部」不必要 | 自动重试 N 次 |
| `data/TaskProgressDisplay.kt:98` | 关联冲突跳过：N 项 | 内部词 | 因文件已属于其他曲目，跳过 N 项 |
| `data/JaWorkClient.kt:567` | MEGA 需要网页会话，暂不支持应用内整作下载 | 技术化 | 暂不支持在 App 内下载 MEGA 文件，请使用浏览器打开 |
| `data/JaWorkClient.kt:585` | 无法读取整作下载文件 | 缺下一步 | 无法读取整部作品的下载文件，请重新打开篇目后重试 |
| `data/JapaneseAsmrHlsDownload.kt:212,232` | 该流编码暂不支持下载，仍可在线播放 | 「流编码」技术化 | 这个音轨的格式暂不支持下载，仍可在线播放 |
| `data/JapaneseAsmrHlsDownload.kt:178` | 该流含多条音轨，暂不支持下载 | 技术化 | 这个文件包含多条音轨，暂不支持下载 |
| `data/JapaneseAsmrHlsDownload.kt:80` | 封装后的音频不完整，请重试 | 「封装」技术化 | 保存的音频不完整，请重试 |
| `data/JapaneseAsmrHlsDownload.kt:256` | 音频分片时间轴不连续，暂不支持下载 | 技术化 | 这个音轨的分段不连续，暂不支持下载 |
| `bilibili/BilibiliClient.kt:453` | 没有可播放的音轨，可能需要登录或大会员 | 「或大会员」语法不通 | 没有可播放的音轨。可能需要登录，或开通大会员。 |
| `bilibili/BilibiliClient.kt:458` | B 站没有返回可播放的音轨 | 缺下一步 | B 站暂时没有返回可播放的音轨，请稍后重试 |
| `bilibili/BilibiliClient.kt:194,205` | 登录状态没有返回 / 登录没有返回有效信息 | 技术化 | 登录没有返回有效结果，请重试 |
| `bilibili/BilibiliClient.kt:210` | 扫码登录失败 | 缺下一步 | 扫码登录失败，请刷新二维码后重试 |
| `bilibili/BilibiliClient.kt:226` | 登录有效，但没有找到收藏夹 | 缺下一步 | 登录有效，但这个账号没有收藏夹。可以先去 B 站创建或收藏内容。 |
| `bilibili/BilibiliClient.kt:691` | 网络请求失败，请重试 | 过于笼统 | 无法连接 B 站，请检查网络后重试 |
| `bilibili/BilibiliClient.kt:880,885` | B 站视频详情无法读取，请重试 / B 站返回的视频信息不完整，请重试 | 技术化 | B 站视频信息读取失败，请稍后重试 |
| `bilibili/BiliSearch.kt:45` | 请输入关键词、BV 号或 UP 主 | 视频模式和 UP 主模式共用，语义不完整 | 视频模式：「请输入关键词、BV 号或视频链接」；UP 主模式：「请输入昵称、UID 或主页链接」 |
| `bilibili/BiliSearch.kt:79` | B 站请求验证失败，请重试 | 「请求验证」歧义 | B 站要求完成验证，请稍后重试 |
| `bilibili/BiliSearch.kt:93-97` | B 站返回的数据无法读取，请重试 | 技术化 | B 站搜索失败，请稍后重试 |
| `youtube/YouTubeClient.kt:46` | YouTube 媒体地址无效 | 技术化 | YouTube 返回的音频地址无效，请重试 |
| `youtube/YouTubeClient.kt:182` | 这个视频不可播放，可能需要登录或验证 | 缺下一步 | 这个视频暂时不可播放。请换一个视频，或更新 Cookie 后重试。 |
| `youtube/YouTubeClient.kt:216,369` | 连不上 YouTube | 缺下一步 | 无法连接 YouTube，请检查网络后重试 |
| `youtube/YouTubeClient.kt:235` | YouTube 音轨下载失败 | 缺下一步 | YouTube 音轨下载失败，请重试 |
| `youtube/YouTubeClient.kt:147` | YouTube 列表读取失败 | 缺下一步 | YouTube 列表读取失败，请稍后重试 |
| `dlsite/DlsiteClient.kt:253` | 请粘贴 Cookie | 缺少对象 | 请粘贴 DLsite Cookie |
| `dlsite/DlsiteClient.kt:601` | 没有作品编号 | 缺主语 | 这个作品缺少编号 |
| `dlsite/DlsiteClient.kt:410,426` | 没有可播放的地址 | 缺下一步 | 没有可播放的地址，请刷新后重试 |
| `dlsite/DlsiteArchiveClient.kt:66` | DLsite 账号已变化，请重试 | 技术化 | 登录账号已变化，请重新登录后重试 |
| `dlsite/DlsiteArchiveClient.kt:171` | DLsite $stage 拒绝请求（HTTP 403） | 技术化 + 状态码 | DLsite 的文件服务器拒绝了下载请求。请重新登录，或稍后重试。 |
| `dlsite/DlsiteArchiveClient.kt:238-248` | 无法继续上次的下载，请重试 | 没有说明是续传 | 无法继续上次的下载，请重新下载 |
| `dlsite/DlsiteArchiveClient.kt:262` | 下载断点无效，请重试 | 「断点」技术化 | 续传位置无效，请重新下载 |
| `dlsite/DlsiteArchiveClient.kt:444-447` | DLsite 返回了网站验证页面… / 商店登录尚未生效… | 可理解，但偏长 | 按「发生了什么 + 下一步」压缩；保留「网页登录」入口名 |
| `dlsite/DlsiteArchiveExtractor.kt:86` | 完整原始包解压失败，文件可能损坏、缺少分卷或格式不受支持 | 好 | 保持 |
| `dlsite/DlsiteArchiveExtractor.kt:98` | 压缩包里有不支持或已加密的文件 | 好，但「暂不支持」更准确 | 压缩包里有暂不支持或已加密的文件 |
| `japaneseasmr/JapaneseAsmrParse.kt:64` | 站点服务错误 | 技术化 | Japanese ASMR 站点暂时不可用，请稍后重试 |
| `japaneseasmr/JapaneseAsmrParse.kt:65` | 媒体地址不可用 | 技术化 | 音轨地址已失效，请重新打开篇目 |
| `japaneseasmr/JapaneseAsmrParse.kt:63` | 页面结构暂不支持 | 技术化 | 这个页面的格式暂不支持 |
| `japaneseasmr/JaWorkClient.kt:249` | 下载入口对应的作品号已变化 | 技术化 | 这个下载入口不属于当前作品，请重新选择入口 |
| `japaneseasmr/JaWorkClient.kt:285` | Buzzheavier 验证后仍未取得下载地址，请改用播放器音轨下载 | 长但可操作 | 验证后仍没有取得下载地址。可以改用播放器音轨下载。 |
| `playback/BiliPlaybackDataSource.kt:205,222,227` | 没有 YouTube 视频编号 / YouTube 媒体地址被拒绝… / 无法读取 YouTube 音轨 | 技术化 | 无法读取这首 YouTube 音频，请刷新后重试；需要登录时提示更新 Cookie |
| `playback/BiliPlaybackDataSource.kt:233-246` | 无法识别 B 站音频 / 无法识别音频 / 找不到音频 / 没有可播放的地址 | 同义反复 | 统一为「没有找到可播放的音频地址，请刷新后重试」 |
| `data/AppUpdate.kt:70,76,83` | 发布信息无法读取 / 没有找到 Android 发布 / 最新 Android 发布尚未提供 APK | 「发布」是开发词 | 无法读取更新信息 / 没有找到 Android 版本 / 这个版本还没有可下载的 APK |
| `data/AppUpdate.kt:109-111` | 检查更新次数过多，请稍后重试 / 未找到发布信息 / 检查更新失败 | 基本可用 | 保留；把「发布信息」改为「更新信息」 |
| `ui/library/FilePreviewScreen.kt:149` | 这个文件还没有下载到本机 | 死胡同 | 见第 0.3 节第 9 条 |
| `ui/download/StoredFilesScreen.kt:390,408` | 无法分享 / 无法打开文章 | 缺原因和下一步 | 无法分享这个文件，请稍后重试 / 无法打开原网页，请检查网络或重新打开作品 |

### 2.3 空状态

| 界面 | 当前 | 建议 |
| --- | --- | --- |
| 音频库根目录 | 还没有音频 + 「点右下角导入本地文件或文件夹；也可以复制 BV 号、RJ 号或视频链接，用剪贴板导入。」 | 好，保留 |
| 音频库文件夹 | 这个文件夹是空的 | 可补「点右下角导入，或返回上级选择其他文件夹。」 |
| 音频库搜索结果 | 没有符合的内容 | 加「换个关键词，或清除筛选后再试。」 |
| 下载与任务（音频） | 还没有下载 + 「在音频库选择要下载的音频。」 | 还没有下载任务 + 「在音频库、作品页或正在播放页下载音频后，任务会显示在这里。」 |
| 下载与任务（字幕） | 还没有字幕任务 + 「在播放页可以生成字幕或翻译字幕。」 | 好；可补「也可以在字幕全文页点『生成翻译』或『润色』。」 |
| 下载管理（音频） | 还没有下载的音频 | 补「下载的在线音频和 B 站音轨会显示在这里。」 |
| 下载管理（字幕） | 还没有字幕文件 | 补「生成的字幕，以及作品目录里的 lrc/srt/vtt 会显示在这里。」 |
| 播放队列 | 队列是空的 | 播放队列是空的 + 「从音频库或播放列表添加曲目。」 |
| 字幕全文页 | 这首没有字幕 | 这首还没有字幕 + 「回到播放页生成字幕后，可以在这里查看。」 |
| 播放列表首页 | 还没有播放列表 + 「点右下角新建。在音频库里多选曲目，再加入播放列表。」 | 好，保留 |
| 播放列表详情 | 这个列表还是空的 | 这个播放列表还是空的 + 「在音频库里多选曲目，再加入这个播放列表。」 |
| B 站搜索 | 搜索 B 站音频 / 没有找到视频 / 没有找到 UP 主 | 好，保留 |
| B 站合集目录 | 没有合集或系列 + 「可以刷新后重试。」 | 这位 UP 主还没有合集或系列。 |
| B 站合集详情 | 这个合集暂时没有视频 | 按类型显示：这个合集／系列暂时没有视频 |
| YouTube | 还没有视频 | 搜索 YouTube + 「输入关键词、视频编号或播放列表链接，查找后可播放或加入音频库。」 |
| 已购买作品 | 没有已购买作品 | 登录 DLsite 后，购买过的作品会显示在这里。若已登录仍为空，用「这个账号还没有已购买作品。」 |
| B 站收藏夹 | 没有 B 站收藏夹 + 「登录信息可能还没生效…」 | 见第 0.3 节第 8 条 |
| 收藏夹内 | 这个收藏夹里没有可导入的视频 | 好；可补「换一个收藏夹，或先去 B 站收藏视频。」 |
| 文件预览 | 找不到这个文件 / 这个文件还没有下载到本机 | 补「可能已被移动或删除，请返回刷新」/「请下载完整原始包后再打开」 |

### 2.4 Snackbar 的时长与长度

`MainActivity.kt:354-358` 用 `snackbar.showSnackbar(text)`，没有传 `duration`，默认是 `Short`。以下消息经常超过一屏或包含三个以上分句：

- `data/LibraryRepository.kt:612-614` 的关联结果。
- `ui/library/LibraryViewModel.kt:1155-1160` 的删除结果，最多三段。
- `data/JaWorkFiles.kt:348` 的整作下载结果。
- `data/DownloadCenter.kt:555` 的解压结果。

建议：

1. 超过约 30 个汉字、或包含两个以上数字结果的消息改用 `SnackbarDuration.Long`。
2. 更推荐在页面内用可停留的 `Card`／状态行展示操作结果，Snackbar 只用于「已完成」这类一句话。
3. 一条消息只保留一个结果和一个下一步；细节放进「任务详情」或「错误详情」。

---

## 3. 分界面审查

以下只列需要改或值得确认的文案；没有列出的条目表示已符合规范。行号供定位，不保证后续不变。

### 3.1 全局导航、顶栏与 Snackbar

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `MainActivity.kt:339-345,462-510` | 音频库／播放列表／在线搜索／我的／设置 | 好，保留 |
| `ui/components/Bars.kt:75` | 已选 $count | 已选 $count 项（混选文件夹和曲目时「项」比「个」合适） |
| `ui/components/Bars.kt:54,78,89` | 返回／取消选择／更多 | 「返回」「取消选择」好；「更多」改为具体对象，见 4.3 |
| `MainActivity.kt:430` | 下载任务 | 下载与字幕任务；角标包含听写和翻译，读屏时应说明「N 项进行中」 |
| `MainActivity.kt:354-358` | `snackbar.showSnackbar(text)` | 长消息传 `SnackbarDuration.Long`，或改用页面内状态行；见 2.4 |
| `MainActivity.kt:362-367` | 可能是作品编号／查找作品 | 好，保留；这是少数「问句 + 明确动作」的 Snackbar |
| `MainActivity.kt:371-380` | 定时关闭时间已到，播放已暂停／确定 | 按钮改「知道了」；若产品允许，可增加「继续播放」 |
| `MainActivity.kt:751` | 下载准备已结束 | 已取消下载（见 0.3-11） |
| `ui/components/AppTopBar`（`Bars.kt:50`） | `maxLines = 1` + Ellipsis | 标题过长会被截断；Japanese ASMR 验证页标题和右侧两个按钮尤其紧张，见 4.2 |

### 3.2 音频库与作品页

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `ui/library/LibraryScreen.kt:226` | 搜索名称、RJ 号或文件名 | 好；可改「搜索作品名、RJ 号或文件名」 |
| `ui/library/LibraryScreen.kt:242-246` | 全部／B 站／YouTube／在线作品／本地 | 好，保留 |
| `ui/library/LibraryScreen.kt:267-272` | 没有符合的内容／还没有音频／这个文件夹是空的 | 按 2.3 补下一步 |
| `ui/library/LibraryScreen.kt:178-192` | 接下来播放／加入播放队列／下载／加入播放列表／移除 | 选择动作改「下载所选」；作品入口用「下载整部作品」；单轨用「下载音轨」 |
| `ui/library/LibraryScreen.kt:188-190` | 删除已下载文件／取消本地关联 | 见 0.3-2；混选时标签应覆盖两种动作 |
| `ui/library/LibraryRows.kt:299,448` | 下载到本地 | 改「下载整部作品」。这两个入口都会先找到作品根目录再下载整部作品；即使从作品子文件夹进入，也不只下载当前文件夹 |
| `ui/library/LibraryRows.kt:302` | 关联为作品 | 好；「关联」是结果，用户能理解 |
| `ui/library/LibraryRows.kt:520-532` | 接下来播放／加入播放队列／下载音轨／删除已下载文件／取消本地关联／从音频库移除 | 好，保留；「接下来播放」比「下一首播放」更自然 |
| `ui/library/LibraryScreen.kt:421-443` | 关联为作品 + 长说明 | 说明可用更短的两句：「优先对照 DLsite 的文件；对不上或没买过时再对照 asmr.one。可以少文件，但不能包含作品里没有的内容。」 |
| `ui/library/LibraryScreen.kt:405-414` | asmr.one 查询结果／要加入音频库吗？ | 标题改「找到 asmr.one 作品」；正文「要加入音频库吗？」可保留 |
| `ui/library/LibraryScreen.kt:458-475` | 导入文件夹／保留文件夹结构／只导入音频 | 好；正文可补「之后可以在音频库里移动」 |
| `ui/library/LibraryScreen.kt:480-491` | 移除选中的内容？… | 加数量：「移除选中的 N 项？已下载文件和手机上的原文件都会保留。」 |
| `ui/library/LibraryRows.kt:590-596` | 从哪个来源下载？／请选择要下载的来源。 | 标题「选择下载来源」；正文「同一作品有多个来源，请选择这次使用的来源。」避免标题与正文重复 |
| `ui/library/LibraryRows.kt:636-639` | 下载哪种格式？…只下载你选的那种 | 好；「你」与全局人称需统一，见 6 |
| `ui/library/LibraryRows.kt:601-606` | 下载哪些文章？／刷新哪些文章？／同一作品有多篇 Japanese ASMR 文章。／全部文章 | 改「下载哪些篇目？」/「刷新哪些JA篇目？」/「同一作品在 Japanese ASMR 有多个篇目。」/「全部篇目」 |
| `ui/library/LibraryRows.kt:611-628` | 打开 Buzzheavier 托管页／文章需要验证／验证后读取下载文件 | 「托管页」是技术词但对这个流程必要；「文章需要验证」改「这篇需要验证」 |
| `ui/library/LibraryViewModel.kt:701-755` | 正在读取下载入口…／选择下载入口／整作文件已在本机／下载整作文件／确认下载／重新下载／替换／保留旧文件 | 「整作」统一为「整部作品」；「整作文件已在本机」改「作品文件已在本机」；「下载整作文件」改「下载整部作品」；「已有这个作品的整作下载任务」改「这个作品已有下载任务」；多篇目时说明「可以下载全部，或只选一篇」 |
| `ui/library/LibraryWorkScreen.kt:185-190` | 下载到本地／刷新文章／关联本地文件夹 | 改「下载整部作品」/「刷新JA篇目」/「关联本地文件夹」 |
| `ui/library/LibraryWorkScreen.kt:310` | 这个作品和里面的内容会从音频库移除。已下载文件和手机上的原文件都会保留。 | 好，保留 |
| `ui/library/LibraryViewModel.kt:485,490` | 已导入／已导入 N 首 | 本地导入用「已导入」合理；不需要改成「加入音频库」 |
| `ui/library/LibraryViewModel.kt:507` | 已加入下载 | 信息量不足：改「已加入 N 个下载任务」；`count == 0` 时「已在本地或正在下载」可保留 |
| `ui/library/LibraryViewModel.kt:1155-1160` | 已删除 N 个已下载文件／已取消 N 首曲目的本地关联／已跳过 N 首本地路径已变化或已移除的曲目 | 最后一句补下一步：「请重新关联后再试」；三段结果建议用 `Long` Snackbar 或页面内展示 |

### 3.3 播放器与字幕

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `ui/player/PlayerChrome.kt:372-377` | 当前没有正在播放的音频／从音频库点一首，或播放一个列表。 | 好，保留 |
| `ui/player/PlayerChrome.kt:391` | 在 App 内验证文章 | 在 App 内验证当前篇目 |
| `ui/player/PlayerChrome.kt:240-244` | 清除失效音频？／清除 | 改「从队列中清除无法播放的音频？」/「清除」；`message` 已有原因，不要把原因只放在正文 |
| `ui/player/PlayerChrome.kt:453-459` | 未检测到人声／删除字幕 | 「未检测到人声」本身准确，但缺少下一步。建议「没有检测到人声。可以删除字幕，或换用其他听写模型重试。」 |
| `ui/player/PlayerChrome.kt:483,492` | 生成字幕和翻译／生成字幕 | 好，保留；两者宽度接近，FlowRow 可换行 |
| `ui/player/PlayerChrome.kt:518-536` | 定时关闭／取消定时／队列／章节：… | 好，保留 |
| `ui/player/PlayerChrome.kt:595-623` | 播放队列／清空／队列是空的／拖动排序／从队列移除 | 「队列是空的」改「播放队列是空的」；其余好 |
| `ui/player/PlayerChrome.kt:774-795` | 字幕／润色／生成翻译／删除 | 菜单项「删除」改「删除字幕」；无障碍描述「更多」改「字幕操作」 |
| `ui/player/PlayerChrome.kt:814` | 这首没有字幕 | 补「回到播放页生成字幕后，可以在这里查看」 |
| `ui/player/PlayerChrome.kt:866-893` | 定时关闭／分／秒／开始倒计时／取消 | 好；可加「留空表示 0」的辅助说明 |
| `ui/player/PlayerScreens.kt:168` | 需要允许访问本地网络，才能连接你填写的听写服务 | 补路径：「需要允许访问本地网络才能连接听写服务。请在系统设置中允许后重试。」若权限弹窗已自动打开设置，可写「请在刚打开的系统设置中允许『本地网络』」。 |
| `ui/player/PlayerScreens.kt:199` | 无法创建字幕任务，请重试 | 好；可补「如果反复失败，请检查听写设置」 |
| `ui/player/PlayerScreens.kt:222,276` | 这首歌没有可用的音频文件，无法生成字幕 | 好，保留 |
| `ui/player/PlayerScreens.kt:227` | 请先加入音频库，再生成字幕 | 好，保留 |
| `ui/player/PlayerScreens.kt:270-271` | 已开始生成字幕和翻译／已开始生成字幕／已经在生成字幕 | 「已经在生成字幕」改「字幕正在生成中」；其余好 |
| `ui/player/PlayerScreens.kt:168` | 听写服务 | 与「生成字幕」的关系在设置帮助里说明；播放页错误用「听写服务」可以接受 |

### 3.4 下载与任务

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `ui/download/DownloadScreen.kt:219-224` | 下载与任务／音频／字幕任务 | 见 6.1；若保留标题，分段可改「下载」/「字幕任务」 |
| `ui/download/DownloadScreen.kt:232-233` | 还没有下载／在音频库选择要下载的音频。 | 改「还没有下载任务」+「在音频库、作品页或正在播放页下载音频后，任务会显示在这里。」 |
| `ui/download/DownloadScreen.kt:227` | 运行 N 项 · 排队 N 项 · 总速率 … | 好；这是概览信息，可保留 |
| `ui/download/DownloadScreen.kt:296-309` | 排队／解析下载地址／解压中／整理入库／下载中／完成／等待验证／已中止／失败 | 好，保留；「整理入库」比旧版「关联文件」清楚 |
| `ui/download/DownloadScreen.kt:329` | 附带视频字幕：… | 改「B 站视频字幕：…」；否则用户不知道是哪来的字幕 |
| `ui/download/DownloadScreen.kt:321` | 打开文章 | 打开原网页（Japanese ASMR 任务） |
| `ui/download/DownloadScreen.kt:338-342` | 中止／验证并继续／重试／删除 | 好，保留 |
| `ui/download/DownloadScreen.kt:349-354` | 删除下载任务 + 四种正文 | 好；「已解压并加入作品的音频不受影响」准确 |
| `ui/download/DownloadScreen.kt:371-379` | 已中止／等待翻译／翻译中／听写已完成，翻译失败／排队／听写中／完成／失败 | 徽章「失败」+ 下方详情，见 0.3-1；其余好 |
| `ui/download/DownloadScreen.kt:395,438` | 查看错误详情／错误详情 | 统一为「查看错误详情」 |
| `ui/download/DownloadScreen.kt:463-475` | ReplyDialog 无标题，直接显示原文 | 加标题「错误详情」；对模型返回的原始内容加「以下为服务返回的内容」说明 |
| `ui/download/DownloadScreen.kt:413` | 进行中 | 翻译任务可显示「翻译中」或「润色中」，与标题里的 `operation.label` 一致 |
| `ui/download/TaskProgressContent.kt:39,47,51` | 历史统计／前置下载：…／任务详情／收起详情 | 好；「前置下载」可改「先下载音频」更直白 |
| `data/TaskProgress.kt:27-33`、`TaskProgressDisplay.kt:65-103` | 获取上传凭证／恢复远端任务／服务端状态暂不可确认／内部重试／关联冲突跳过 | 仅在「任务详情」里出现，可按 2.2 改写；不要在列表徽章里显示这些长词 |

### 3.5 下载管理

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `ui/download/StoredFilesScreen.kt:261` | 下载管理 | 页面只列已落盘文件，建议「已下载文件」或保留「下载管理（文件）」；见 6.1 |
| `ui/download/StoredFilesScreen.kt:269,275` | 音频／字幕 | 好，保留 |
| `ui/download/StoredFilesScreen.kt:280` | 文件名、编号或作品名 | 好，保留 |
| `ui/download/StoredFilesScreen.kt:293-297` | 还没有下载的音频／还没有字幕文件／没有匹配的文件 | 按 2.3 补说明；搜索空结果补「清除搜索后再试」 |
| `ui/download/StoredFilesScreen.kt:310` | 长按文件可多选、分享或删除 | 点按也会播放；改「点按播放；长按可多选、分享或删除。」 |
| `ui/download/StoredFilesScreen.kt:327-328` | App 生成字幕／字幕文件 | 建议「应用生成字幕」；若品牌语气允许「App」，则全站统一，不要两处混用 |
| `ui/download/StoredFilesScreen.kt:386,390,408` | 分享／无法分享／无法打开文章 | 「无法分享」补「请稍后重试」；「无法打开文章」改「无法打开原网页」，并补「请检查网络或重新打开作品」 |
| `ui/download/StoredFilesScreen.kt:245` | 打开文章（多选动作） | 打开原网页 |
| `ui/components/DeleteAudioDialog` 在下载管理内复用 | 「生成的字幕文件也可在『设置 → 下载管理 → 字幕』中单独删除」 | 当前页就是下载管理，属于指回自己；改「也可以只删除生成的字幕，音频会保留。」 |

### 3.6 设置、缓存、外观与关于

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `ui/settings/SettingsScreen.kt:109-110` | 下载管理／手机中已下载或生成的文件，可搜索、分享、删除 | 标题若改「已下载文件」，摘要同步「下载的音频、B 站音轨和生成的字幕，可搜索、分享、删除」 |
| `ui/settings/SettingsScreen.kt:114-118` | 同时下载数量／最多 N 项 | 改「同时下载任务数」／「最多同时下载 N 个」；选项「N 项」改「N 个」 |
| `ui/settings/SettingsScreen.kt:121-125` | 清理缓存／已用 X / 上限 Y | 好；「上限」前的 `cacheLimitLabel` 需补空格，见 0.3-12 |
| `ui/settings/SettingsScreen.kt:143-144` | 桌面字幕／离开播放器后，在其他应用上层显示字幕 | 好；可补「需要悬浮窗权限」 |
| `ui/settings/SettingsScreen.kt:165-166` | 锁定桌面字幕／锁定后触摸会传给下方应用，回这里解锁 | 改「锁定后，触摸会穿透到下方应用。请回到本页解锁。」 |
| `ui/settings/SettingsScreen.kt:178-179` | 关于／版本 X · 检查更新 | 好，保留 |
| `ui/settings/SettingsScreen.kt:269` | 翻译已配置／翻译未配置 | 好，保留 |
| `ui/settings/SettingsScreen.kt:272` | 无法打开悬浮窗设置，可在系统设置中允许显示在其他应用上层 | 补路径：「请到系统设置 → 应用 → 特殊权限 → 显示在其他应用上层中允许。」 |
| `ui/settings/CacheScreen.kt:51-70` | 清理缓存／当前缓存占用／缓存上限／清理缓存／缓存已清理／只清理封面图片等临时缓存… | 「缓存已清理」改「临时文件已清理」；「缓存上限」补「超过后会自动清理最早的临时文件」；说明句可简化为「只清理封面等临时文件。下载的音频和字幕请到『下载管理』处理。」 |
| `ui/settings/CacheScreen.kt:102-104` | 取消 | 选择即时生效，改「关闭」；见 0.3-6 |
| `ui/settings/AboutScreen.kt:91-107` | 从 GitHub 检查最新正式版本／检查更新／在浏览器打开发布页／请在浏览器的发布页下载 APK 并安装。 | 好，保留 |
| `ui/settings/AboutScreen.kt:101` | 最新发布：X | 最新版本：X |
| `ui/settings/AboutScreen.kt:98` | 本机版本比发布版本新 | 本机版本高于已发布版本 |
| `ui/settings/AboutScreen.kt:71-72` | 没有可用的浏览器／无法打开浏览器，请稍后重试 | 前者补「请安装浏览器后重试」；后者保留 |
| `ui/settings/AppearanceScreen.kt:95-104` | 跟随系统取色／跟随当前播放封面／播放时随封面变化；封面没有明显颜色时保持上一次的颜色 | 好，保留 |
| `ui/settings/AppearanceScreen.kt:96` | 当前系统不支持，暂用默认靛蓝（需要 Android 12 及以上） | 好；可把括号内容移到次要说明 |

### 3.7 听写和翻译

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `ui/settings/ModelSettingsScreen.kt:91,149` | 听写／翻译 | 好，保留 |
| `ui/settings/ModelSettingsScreen.kt:129,161` | API Key | 好；错误文案统一到「API Key」 |
| `ui/settings/ModelSettingsScreen.kt:170` | 留空使用 qwen3.8-flash | 好，保留 |
| `ui/settings/ModelSettingsScreen.kt:178,185` | 深度思考／思考强度 | 好，保留 |
| `ui/settings/ModelSettingsScreen.kt:210` | 翻译后自动润色 | 好，保留 |
| `ui/settings/ModelSettingsScreen.kt:220,227` | 翻译调优 + 「日文合并超出条数、时长或字数限制…」 | 标签和说明都偏内部。建议标签「自动修正合并格式」；说明「开启后，如果模型把太多句子合并在一起，会要求它拆分一次再交付。每批最多多请求两次；关闭时直接使用模型返回的结果。」 |
| `ui/settings/ModelSettingsScreen.kt:247-251` | 有未保存的修改／已保存／未配置 | 好，保留 |
| `ui/settings/ModelSettingsScreen.kt:494` | 模型未下载。安装占用约 X MiB，安装后可离线听写。 | 模型尚未下载。下载并安装后约占用 X MiB，之后可以离线听写。 |
| `ui/settings/ModelSettingsScreen.kt:513` | 来源　百分比　大小（全角空格） | 改为「GitHub · 42% · 128 / 512 MiB」 |
| `ui/settings/ModelSettingsScreen.kt:519,529,534` | 取消下载／模型已下载，在本机运行，不需要填写地址和口令。／删除模型 | 「口令」改「API Key」；其余好 |
| `ui/settings/ModelSettingsScreen.kt:538-540` | 删除本机模型／删除 X 后，需要重新下载才能用它离线听写。 | 好，保留 |
| `ui/settings/ModelSettingsScreen.kt:568,584` | 选择模型下载来源／下载约 X MiB · 安装约 Y MiB | 改「下载 X MiB · 安装后占用 Y MiB」 |
| `ui/settings/ModelSettingsScreen.kt:604-618` | 听写帮助 | 内容准确；「服务不校验时可随便填」改「服务不校验时可以填任意非空值」；「首次访问要允许本地网络」可补「否则无法连接」 |
| `ui/settings/ModelSettingsScreen.kt:636-642` | 翻译帮助 | 好；「深度思考一般不用打开」可以保留；「翻译调优」如改名，帮助同步 |

### 3.8 在线搜索与作品详情

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `ui/asmr/OnlineScreens.kt:1374` | 搜索（空白摘要） | 搜索作品 |
| `ui/asmr/OnlineScreens.kt:193-203` | 发售日（新→旧）等排序 | 好，保留 |
| `ui/asmr/OnlineScreens.kt:1393,1398` | 有字幕／全年龄 | 好，保留 |
| `ui/asmr/OnlineScreens.kt:1418-1420` | 没有找到作品／修改搜索 | 好；可补「试试更短的关键词，或切换筛选」 |
| `ui/asmr/OnlineScreens.kt:1506` | asmr.one 未收录 | 作为徽章可保留；正文里用完整句「asmr.one 未收录这部作品」 |
| `ui/asmr/OnlineScreens.kt:1827` | 未找到 JA 收录／暂时无法确认 Japanese ASMR 收录 | 统一完整名称：「没有找到 Japanese ASMR 收录」/「暂时无法确认 Japanese ASMR 收录」 |
| `ui/asmr/OnlineScreens.kt:717` | 文章 $id（无标题时的兜底） | 篇目 $id |
| `ui/asmr/OnlineScreens.kt:1911,1914` | 刷新文章／正在读取文章… | 刷新JA篇目／正在读取篇目… |
| `ui/asmr/OnlineScreens.kt:1833` | 验证／重试 | 好，保留 |
| `ui/asmr/OnlineScreens.kt:1847` | 没有找到这部作品 | 好，保留 |
| `ui/asmr/OnlineScreens.kt:1929` | 下载（Japanese ASMR 单轨） | 好；未入库时不显示下载，建议补一行「加入音频库后可下载」的状态说明 |
| `ui/asmr/OnlineScreens.kt:2040,2048` | 已在音频库／加入音频库 | 好，保留 |
| `ui/asmr/OnlineScreens.kt:2169-2171` | 图库／加载图片（使用移动数据） | 好，保留 |
| `ui/asmr/OnlineScreens.kt:2201-2204` | 内容试听／暂无试听 | 好，保留 |
| `ui/asmr/OnlineScreens.kt:1257` | 保存失败，请重试 | 加入音频库失败，请重试 |
| `ui/asmr/OnlineScreens.kt:1046` | 已加入下载任务／没有需要下载的音频 | 已加入 N 个下载任务；后者好 |
| `ui/asmr/OnlineScreens.kt:2092-2095,2113-2116` | 图片／文本／文件 | 好，保留 |
| `ui/asmr/OnlineScreens.kt:2160-2161` | · 已下载 · 有字幕 | 好，保留 |
| `ui/asmr/OnlineScreens.kt:2718-2721` | 没有已购买作品 | 按 2.3 补登录说明 |

### 3.9 「我的」与登录

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `ui/asmr/OnlineScreens.kt:2508-2521` | 未登录／已保存／已登录；还没有登录／已保存登录信息／已扫码登录，会在需要时自动更新／自动续期失败，请重新登录 | 逻辑正确。文案可统一「还没有登录」为「未登录」；「已保存登录信息」与徽章「已保存」重复，可合并 |
| `ui/asmr/OnlineScreens.kt:2530-2537` | 查看 B 站收藏夹／扫码登录／SESSDATA 登录／退出登录 | 未登录时「查看 B 站收藏夹」会进入错误提示。建议未登录时改为「登录后查看收藏夹」并禁用，或把它放到登录按钮之后 |
| `ui/asmr/OnlineScreens.kt:2536,2630` | SESSDATA 登录 | 对普通用户是技术词。建议按钮「粘贴登录信息」，对话框标题「粘贴 B 站登录信息」，字段标签保留「SESSDATA 或 Cookie」 |
| `ui/asmr/OnlineScreens.kt:2597-2600` | 登录信息只保存在这台手机里…／扫码登录后… | 好，保留；「这台手机」在平板上可改「这台设备」，但不是必须 |
| `ui/asmr/OnlineScreens.kt:2543-2558` | 退出 DLsite 登录／退出 B 站登录 + 说明 | 好，保留 |
| `ui/asmr/OnlineScreens.kt:2464-2485` | 网页登录／使用 Cookie／使用 Cookie 登录 | 「使用 Cookie」是展开开关，「使用 Cookie 登录」是提交，两个标签过于接近。建议开关「改用 Cookie 登录」，提交「保存并登录」 |
| `ui/asmr/OnlineScreens.kt:2493-2495` | 查看已购买作品／重新登录／退出登录 | 好，保留 |
| `ui/asmr/DlsiteWebLogin.kt:108-126` | 登录 DLsite／完成／校验中…／登录完成后点右上角「完成」。保存时会去掉 Cloudflare 的验证 Cookie。 | 好；「Cloudflare 的验证 Cookie」偏技术，可改「保存时会自动忽略网站验证用的 Cookie」 |
| `ui/youtube/YouTubeScreens.kt:206-226` | 已保存登录信息／还没有保存登录信息／已保存，正在确认登录…／已保存，但连不上 YouTube／已退出登录 | 好，保留 |
| `ui/youtube/YouTubeScreens.kt:239-246` | 更新 Cookie／保存 Cookie／退出登录／使用浏览器里 youtube.com 的整段 Cookie… | 好，保留 |
| `ui/youtube/YouTubeScreens.kt:260` | 「喜欢的视频」和「稍后观看」需要重新保存 Cookie 后才能读取… | 好，保留 |

### 3.10 B 站

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `ui/bilibili/BiliBrowser.kt:72-76` | 视频／UP 主／关键词、BV 号或视频链接／UP 主昵称、UID 或主页链接 | 好，保留 |
| `ui/bilibili/BiliBrowser.kt:105` | N 个视频／N 位 UP 主 | 好，保留 |
| `ui/bilibili/BiliBrowser.kt:109` | 全选已加载／取消全选 | 好，保留了「已加载」的范围 |
| `ui/bilibili/BiliBrowser.kt:114-118` | 已选 N 个／取消／加入音频库 | 「已选 N 个视频」；「取消」改「取消选择」，与弹窗的「取消」区分 |
| `ui/bilibili/BiliBrowser.kt:127-129` | 搜索 B 站音频／没有找到视频／没有找到 UP 主 | 好，保留 |
| `ui/bilibili/BiliBrowser.kt:152-154` | 已加入 N 个分 P／已下载 N 个分 P／下载中 N 个分 P／B 站 · BV… | 好，保留 |
| `ui/bilibili/BiliBrowser.kt:182-206` | 选择分 P · 动作／全选／以下视频读取失败，其余内容可继续： | 好；「以下视频读取失败」可补「可重试或继续处理其余内容」 |
| `ui/bilibili/BiliCollectionScreen.kt:50` | 合集（fallback） | 系列也会走到这里；用 `collection?.label ?: "在线列表"` |
| `ui/bilibili/BiliCollectionScreen.kt:73` | 已读取 N 个视频 · 目录尚未完整 | 好，保留了不完整状态 |
| `ui/bilibili/BiliCollectionScreen.kt:79-81` | 收藏在线列表／已收藏在线列表／刷新 | 见 0.3-5；「刷新」可改「刷新目录」以区别于刷新播放状态 |
| `ui/bilibili/BiliCollectionScreen.kt:89` | 这个合集暂时没有视频 | 按类型：这个合集／系列暂时没有视频 |
| `ui/bilibili/BiliCollectionScreen.kt:94-104` | 视频／已选 N 个／全选已加载／播放／加入播放队列／加入音频库 | 「已选 N 个视频」；「播放」改「播放所选」，与 BiliVideo 的选择栏一致 |
| `ui/bilibili/BiliCollectionScreen.kt:119` | 加入音频库（行菜单） | 已在库时仍显示同一标签；可像 YouTube 一样显示「已在音频库」或「调整导入方式」 |
| `ui/bilibili/BiliCollectionScreen.kt:139-147` | 复制为普通播放列表／列表名称／下一步：选择内容 | 改「复制为自建播放列表」；其余好 |
| `ui/bilibili/BiliCollectionScreen.kt:149-152` | 取消收藏在线列表／取消收藏／保留收藏 | 见 0.3-5，改「移出播放列表」/「移出」/「保留」 |
| `ui/bilibili/BiliCollectionScreen.kt:161-165` | 无法打开这个合集／链接参数无效，请返回后重试 | 好，保留 |
| `ui/bilibili/BiliCollectionScreen.kt:178-185` | 视频读取失败／部分视频读取失败／继续处理其余 N 个视频／重试／取消 | 好；正文补「可以在上方重试，或继续处理其余视频」 |
| `ui/bilibili/BiliCollectionScreen.kt:192-202` | 按当前排序播放全部／排序：正序 | 好，保留 |
| `ui/bilibili/CollectionCatalogue.kt:28` | 没有合集或系列／可以刷新后重试。 | 改「这位 UP 主还没有合集或系列。」 |
| `ui/bilibili/BiliVideoScreen.kt:117-127` | 播放所选／接下来播放／加入播放队列／下载音轨／加入播放列表／删除已下载文件／从音频库移除 | 好，保留；选择栏的「删除已下载文件」很明确 |
| `ui/bilibili/BiliVideoScreen.kt:143-148` | 下载全部音轨／分 P／全选 | 好，保留 |
| `ui/bilibili/BiliVideoScreen.kt:165` | 移除整个视频及其分 P？已下载文件会保留。 | 好，保留 |
| `ui/bilibili/BiliVideoScreen.kt:71` | 已加入 N 个下载任务／已有本地文件或下载任务 | 好，保留 |
| `ui/bilibili/BiliImportDialog.kt:22-43` | 选择导入方式／更改导入方式？／分开导入／统一导入／全部 N 个分 P 合并为一个视频项 | 好；「统一导入」的帮助可以补一句「适合把整场直播或连续剧作为一个条目」 |
| `ui/bilibili/BiliImportDialog.kt:52-58` | 已选 N 个分 P · 取消全选／已选 N 个分 P · 全选 | 好，状态和动作写在同一按钮里；如果空间允许，拆成计数 + 全选按钮更清楚 |
| `ui/bilibili/BiliImportDialog.kt:75` | 以下视频读取失败，其余内容可继续： | 好；补「可重试或继续」 |
| `ui/bilibili/BiliImportDialog.kt:82` | 按此方式导入／确认更改并继续 | 好，保留 |
| `ui/bilibili/BiliChoices.kt:6` | 播放／加入音频库 | 好，保留 |

### 3.11 YouTube

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `ui/youtube/YouTubeScreens.kt:134` | 关键词、视频或播放列表链接 | 好；与空状态里的「视频编号」统一，可用「关键词、视频编号或播放列表链接」 |
| `ui/youtube/YouTubeScreens.kt:145-149` | N 个视频／查找／刷新／全选已加载／取消全选 | 好；「查找」可改「搜索」与在线搜索页统一，但不是必须 |
| `ui/youtube/YouTubeScreens.kt:154-156` | 已选 N 个／取消／加入音频库 | 「已选 N 个视频」；「取消」改「取消选择」 |
| `ui/youtube/YouTubeScreens.kt:163` | 还没有视频／输入关键词、视频编号或链接，查找后可播放或加入音频库。 | 标题改「搜索 YouTube」；正文与输入框标签统一 |
| `ui/youtube/YouTubeScreens.kt:176` | 已下载／下载中／已在音频库／未保存 | 「未保存」改「未加入音频库」；其余好 |
| `ui/youtube/YouTubeScreens.kt:187` | 加入音频库／已在音频库 | 好，保留 |
| `ui/youtube/YouTubeScreens.kt:102-107` | 已加入音频库 | 按 0.3-7 统计实际新增；单条保存可保留 |
| `ui/youtube/YouTubeScreens.kt:212-219` | 请粘贴 youtube.com 的整段 Cookie／已保存，正在确认登录…／已保存，但连不上 YouTube | 好；后者可补「请检查网络后重试」 |
| `ui/youtube/YouTubeScreens.kt:251-254` | YouTube 登录信息／整段 Cookie／保存／取消 | 好，保留 |
| `ui/youtube/YouTubeScreens.kt:259-262` | 退出 YouTube 登录 + 说明 | 好，保留 |
| `youtube/YouTubeClient.kt:153` | 没有找到相关视频／没有找到这个播放列表，请检查登录信息或列表是否可访问 | 好，保留 |

### 3.12 播放列表

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `ui/playlist/PlaylistScreens.kt:69-70` | 还没有播放列表／点右下角新建。在音频库里多选曲目，再加入播放列表。 | 好，保留 |
| `ui/playlist/PlaylistScreens.kt:81-82` | 新建／新建播放列表 | 好，保留 |
| `ui/playlist/PlaylistScreens.kt:149-150` | 播放／加入播放队列 | 「播放」改「播放全部」；「播放」会用列表替换当前队列，建议加无障碍说明「用这个播放列表替换当前播放队列」 |
| `ui/playlist/PlaylistScreens.kt:153` | 这个列表还是空的／在音频库里多选曲目，再加入播放列表。 | 改「这个播放列表还是空的」；正文「再加入这个播放列表」 |
| `ui/playlist/PlaylistScreens.kt:174-183` | 删除播放列表／删除「X」？音频文件和音频库中的内容会保留。／删除／取消 | 好，保留 |
| `ui/playlist/PlaylistScreens.kt:188-195` | 改名 | 好；确认按钮见下 |
| `ui/playlist/PlaylistScreens.kt:273` | 确定 | 新建时「创建」，改名时「保存名称」；不要用「确定」 |
| `ui/playlist/PlaylistScreens.kt:218,281` | N 首／B 站合集 · N 个视频 | 好，保留 |
| `ui/playlist/PlaylistScreens.kt:246,252` | 拖动排序／从播放列表移除 | 好，保留 |

### 3.13 剪贴板导入

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `ui/importing/ClipboardImportDialog.kt:40` | 导入这个内容？ | 加入音频库？ |
| `ui/importing/ClipboardImportDialog.kt:39` | 找到作品 | 找到作品，但没有可加入的来源（与实际「查看详情页」分支一致） |
| `ui/importing/ClipboardImportDialog.kt:52-53` | 已在音频库／已在音频库，可调整导入方式／没有有效来源，无法加入音频库 | 好，保留 |
| `ui/importing/ClipboardImportDialog.kt:56` | 正在导入… | 正在加入音频库…（与动作一致） |
| `ui/importing/ClipboardImportDialog.kt:69-72` | 选择导入方式／导入 | 多 P 保留「选择导入方式」；单条改「加入音频库」 |
| `ui/importing/ClipboardImportDialog.kt:78` | 关闭／取消 | 好，已在已保存时区分 |
| `ui/importing/ClipboardImportDialog.kt:63` | 打开文章原网页 | 打开原网页 |
| `ui/importing/ClipboardImportViewModel.kt:110,152` | 已加入音频库／已在音频库 | 好，保留 |
| `data/ClipboardImportController.kt:112` | 没有找到这部作品 | 好，保留 |
| `data/ClipboardImportController.kt:120` | DLsite 登录已失效，请重新登录 | 好，保留 |
| `data/ClipboardImportController.kt:122` | Japanese ASMR 文章读取失败，请重试 | 改「Japanese ASMR 篇目读取失败，请重试」；可补「请打开原网页确认篇目仍可访问」 |
| `data/ClipboardImportController.kt:123` | asmr.one/DLsite连接失败，请检查网络 | 无法连接 asmr.one 或 DLsite，请检查网络后重试 |
| `data/ClipboardImportController.kt:124` | ${source.label} 视频读取失败，请检查网络后重试 | 按来源分支，避免「B 站 视频」多一个空格 |
| `data/ClipboardImportController.kt:165` | 导入失败，请重试 | 加入音频库失败，请重试 |
| `MainActivity.kt:362-367` | 可能是作品编号／查找作品 | 好，保留 |

### 3.14 Japanese ASMR 验证

这一页的文案密度最高，用户又常在验证失败时打开，建议专门做一轮压缩。

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `ui/asmr/JapaneseAsmrVerificationScreen.kt:283` | Japanese ASMR 验证／Buzzheavier 托管文件页 | 标题后面还有「读取下载文件」「关闭」两个按钮，`AppTopBar` 单行标题在 360 dp 宽度下容易截断。建议「网页验证」/「Buzzheavier 文件页」 |
| `:377-386` | 已捕获下载地址…／完成验证后点「读取下载文件」；应用会尝试点击网页里的「Download File」… | 可压缩为两句：「完成验证后点『读取下载文件』。应用会尝试点击网页里的『Download File』；没有反应时，请直接在网页里点。」 |
| `:334,348` | 请返回原文章后读取音轨／请完成验证，等文章播放器显示后再读取音轨 | 改「请返回原网页后读取音轨」/「请完成验证，等页面显示播放器后再读取音轨」 |
| `:358` | 无法读取这篇文章的音轨 | 无法读取这个篇目的音轨 |
| `:381,385` | 完成文章验证后，点「读取下载文件」…／完成验证后打开一篇文章，然后点「读取音轨」。 | 完成篇目验证后…／完成验证后打开一个篇目，然后点「读取音轨」。 |
| `:199-203` | 未捕获下载地址（$info），请在网页里点「Download File」后再试 | 把 `$info` 放进次要行或错误详情，主句只保留动作 |
| `:320-321` | 请等托管页显示「Download File」后再读取／请完成验证，等下载文件显示后再读取 | 好；可统一为「等页面出现『Download File』后，再点『读取下载文件』」 |
| `:526` | Buzzheavier 托管页暂时不可用（HTTP 503） | 去掉状态码：Buzzheavier 暂时不可用，请稍后重试 |
| `:532` | 网页加载失败，请检查网络后重新打开 | 好，保留 |
| `:367-369` | 重试／读取音轨／读取下载文件 | 好，动作明确 |
| `:372` | 关闭 | 好；若流程未完成，可用「退出验证」更准确 |
| `ui/components/JapaneseAsmrBrowser.kt:12` | 手动完成验证后，可以读取文章音轨。 | 手动完成验证后，可以读取这篇的音轨。 |
| `japaneseasmr/JapaneseAsmrParse.kt:60,62,66,68` | 文章不存在或已删除／无法确认文章对应的 RJ 作品号／文章已移除这条音轨／重新打开文章 | 篇目不存在或已删除／无法确认这个篇目对应的 RJ 作品号／这个篇目已移除这条音轨／重新打开篇目 |

### 3.15 通知、服务、桌面字幕与文件预览

| 位置 | 当前 | 建议 / 说明 |
| --- | --- | --- |
| `data/AudioDownloadService.kt:105` | 音频下载 | 好；渠道名建议「下载任务」 |
| `data/SubtitleTaskService.kt:167` | 字幕任务 | 好，保留 |
| `data/OfflineModelDownloadService.kt:71` | 模型下载 | 好，保留 |
| `data/AudioDownloadService.kt:150` | 正在下载音频 | 好，保留 |
| `data/AudioDownloadService.kt:201-202` | Japanese ASMR 下载等待验证／N 个整作下载任务需要验证后继续 | 「Japanese ASMR 整部作品下载待验证」/「N 部作品需要验证，点按继续」 |
| `data/SubtitleTaskService.kt:251-254` | 正在处理字幕 + 详情 + 标题 | 好，保留 |
| `data/OfflineModelDownloadService.kt:163,181` | 正在下载听写模型／取消 $label | 好，保留 |
| `playback/PlaybackService.kt:133` | 无法显示桌面字幕，请检查悬浮窗权限 | 补路径：「无法显示桌面字幕。请到系统设置中允许『显示在其他应用上层』。」 |
| `playback/PlaybackService.kt:262` | 播放失败，请检查网络或作品是否还能打开 | 好；可补「稍后重试」 |
| `playback/DesktopSubtitleWindow.kt:65-95` | 桌面字幕／上一首／暂停／下一首／锁定桌面字幕／关闭桌面字幕 | 好，保留 |
| `ui/library/FilePreviewScreen.kt:149,276,302-304,323` | 这个文件还没有下载到本机／此文件类型暂不支持应用内预览／没有可打开此文件的应用／无法用其他应用打开这个文件／图片读取失败 | 按 0.3-9 补下一步；「图片读取失败」补「请检查网络后重试」 |

---

## 4. 排版、长度、无障碍

### 4.1 中英混排与数字

统一规则：

- 中文与拉丁字母、数字之间保留一个半角空格：「下载 3 个文件」「B 站」「1 GB」「API Key」。
- 数字与单位之间保留空格：「200 MB」「1 GB」「28 秒」。
- 中文标点使用全角；界面路径用「设置 → 听写和翻译」。
- 并列项用「、」，分句用「；」，不要用英文逗号。
- 不混用全角空格和半角空格做分隔。

需要修的具体位置见 0.3-12。另有两处容易遗漏：

- `ui/settings/ModelSettingsScreen.kt:513` 使用 `　`（U+3000）分隔，改为「 · 」。
- `data/ClipboardImportController.kt:124` 的 `"${source.label} 视频读取失败"` 对「B 站」会多出空格，按来源分支。

### 4.2 控件宽度与截断

当前布局里有三类容易截断的位置：

1. **单行顶栏标题**：`AppTopBar` 的标题是 `maxLines = 1` + Ellipsis（`ui/components/Bars.kt:50`）。Japanese ASMR 验证页同时有「读取下载文件」「关闭」两个右侧按钮，标题「Buzzheavier 托管文件页」在 360 dp 宽度下会被截断。建议缩短标题，或把「关闭」改为返回箭头。
2. **单行徽章**：`LabelBadge`/`StatusBadge` 是 `maxLines = 1`、`softWrap = false`（`ui/components/Badges.kt:51-68`）。徽章只放 2–6 个字的状态词，不要放完整错误、完整标题或带数字的长句。当前 `DownloadScreen.kt:379` 违反了这一点。
3. **按钮文案**：`FlowRow` 里的「生成字幕和翻译」可以换行；`SelectionTopBar` 的动作只显示图标，靠 `contentDescription` 表达，问题不大。但对话框按钮「删除副本并取消关联」「删除音频和字幕」在窄屏可能挤成两行。建议对话框主按钮控制在 6 个字以内：「全部删除」「删除」「移出」「保留」。

其余建议：

- `DownloadRow` 的 `headlineContent = { Text(job.title) }` 没有限制行数。任务标题可能包含作品名和路径，建议 `maxLines = 2` + Ellipsis，完整标题放进详情。
- `DownloadRow`/`TranslationJobRow` 的错误正文没有限制行数。建议 `maxLines = 3` + Ellipsis，并提供「查看错误详情」。
- `DeleteAudioDialog` 的正文只有 `Text(message)`。如果三个计数都很大，正文会变长；建议加 `heightIn(max = 320.dp)` + `verticalScroll`，或把细节收进「查看详情」。
- `EmptyState` 的 message 没有宽度限制，但外层有 32 dp 横向内边距；当前最长文案（YouTube 空状态）可以接受。
- `DlsiteDownloadDialog` 已有 `verticalScroll`，长正文安全。
- 听写和翻译帮助弹窗已有 `heightIn(max = 420.dp)` + `verticalScroll`，安全。

### 4.3 无障碍

| 位置 | 问题 | 建议 |
| --- | --- | --- |
| 多处 | `contentDescription = "更多"` 出现 14 次，读屏用户无法知道是哪个对象的更多操作 | 按对象命名：「曲目操作」「文件夹操作」「作品操作」「字幕操作」「播放列表操作」「视频操作」 |
| `MainActivity.kt:430` | 「下载任务」角标同时包含听写、翻译和 B 站字幕任务 | 「下载与字幕任务」；有数量时在读屏文案里带上数量 |
| `ui/asmr/OnlineScreens.kt:1589` | 搜索条件芯片的删除图标读作「删除」 | 「删除这个搜索条件」 |
| `ui/components/Bars.kt:75` | 「已选 N」没有单位 | 「已选 N 项」 |
| `ui/playlist/PlaylistScreens.kt:149` | 「播放」会替换当前队列，视觉上无法看出 | `contentDescription = "用这个播放列表替换当前播放队列"` |
| `ui/download/DownloadScreen.kt:317,421` | 状态变化只通过颜色和文字体现，读屏不会主动播报 | 给状态容器加 `liveRegion = LiveRegionMode.Polite`；失败时同时把错误正文纳入语义 |
| `ui/library/LibraryScreen.kt:537` | 排序图标描述「按名称/按加入时间」描述的是当前排序，不是动作 | 「排序：按名称」「排序：按加入时间」 |
| `data/DesktopSubtitleWindow.kt` | 「暂停」按钮的描述随状态在「暂停/播放」之间切换，正确 | 保持 |

### 4.4 字体缩放与长文本

建议在以下条件各截一次图：

- 360 dp 宽 × 字体 1.0。
- 360 dp 宽 × 字体 1.3。
- 360 dp 宽 × 字体 2.0。

重点看：

- `SelectionTopBar`（已选数量 + 3 个固定图标 + 溢出菜单）。
- `DownloadScreen` 的状态徽章和两行标题。
- `PlayerChrome` 的 `FlowRow`（定时关闭/取消定时/队列/章节/合集）。
- `BiliCollectionScreen` 的 `FlowRow`（播放/加入播放队列/加入音频库）。
- `PlaylistDetailScreen` 的「播放 / 加入播放队列」两个等宽按钮。
- `DlsiteDownloadDialog` 的三个全宽按钮。
- Japanese ASMR 验证页的顶栏。

**模拟器复核（2026-11-21）**

- 在 Pixel 6、Android 16 / API 36 上使用 360 dp 宽度，分别检查系统字体缩放 1.0、1.3 和 2.0。界面截图使用临时用户中的本地音频和测试数据。
- 多选顶栏同时显示三个固定按钮和溢出菜单时，默认字号的「已选 128 项」已被省略，2.0 倍时数量不可见。
- 2.0 倍时，Japanese ASMR「网页验证」和「托管文件页」分别显示为「网页…」和「托管…」。Japanese ASMR 播放页的「正在播放」也被压成省略号。定时、队列、章节和合集操作能换行显示。网页验证页在离线错误状态下截图，实际站点验证流程待验证。
- 下载任务的状态徽章完整。较长任务标题按两行省略，右侧「验证并继续」占用较多宽度。B 站合集动作和自建播放列表按钮可换行，DLsite 对话框的三个按钮均完整可见。
- 播放损坏的音频文件时，错误提示显示英文解析器列表。2.0 倍字号下提示延伸至状态栏并遮挡界面。该次提示未包含完整地址或 `res:#`。
- 设备测试实际执行 41 例，39 例通过。真实下载用例确认了进度、前台通知和完成状态。两例失败分别为 `TaskProgressAndroidTest` 的迁移断言仍期望数据库版本 21（实际为 22），以及 `BiliCollectionAndroidTest` 的模拟目录缺少 `series_list`，在进入回退标题断言前失败。

### 4.5 语气与人称

- 当前大部分句子省略主语，按钮只写动作，这是正确的。
- 少数说明句使用「你」：「才能连接你填写的听写服务」「只下载你选的那种」。建议只在需要强调「这是你的设置/你的选择」时使用「你」，其余保持省略；不要使用「您」。
- 「请」在错误提示里出现频率较高。可以保留在需要用户操作的句子中；纯状态说明不需要「请」。
- 不使用感叹号；当前没有，保持。
- 不指责用户：当前没有「你操作有误」这类表述，保持。

---

## 5. 硬编码与 strings.xml 迁移

### 5.1 现状

| 层 | 文件数 | 中文字面量 | 说明 |
| --- | ---: | ---: | --- |
| `ui/` + `MainActivity.kt` | 45 | 925 | 主要界面文案；包含 `contentDescription` |
| `data/` | 34 | 398 | 任务、下载、听写、翻译、缓存、存储 |
| `playback/` | 7 | 40 | 播放错误、桌面字幕、字幕来源 |
| `bilibili/`、`youtube/`、`dlsite/`、`asmr/`、`japaneseasmr/` | 17 | 251 | 不含 `DlsiteGenres.kt` 的 320 条标签数据 |
| `ui/theme/` | 3 | 11 | 主题色名 |

`strings.xml` 目前只有：

```xml
<resources>
    <string name="app_name">音频播放器</string>
</resources>
```

没有任何 `stringResource` 或 `getString(R.string…)` 调用。这意味着：

- 文案无法统一审阅、无法做本地化、无法用资源工具查重。
- 同一个词在不同文件里容易漂移（本次已发现五组）。
- 通知渠道名、无障碍描述也硬编码，后续做多语言会漏。

### 5.2 迁移顺序

不建议一次性迁移 900 多条。按收益排序：

1. **重复出现的标准动作与状态**：取消、关闭、重试、删除、移除、中止、完成、失败、加入音频库、下载、返回、更多。
2. **长说明与帮助**：听写帮助、翻译帮助、B 站登录说明、Japanese ASMR 验证说明、DLsite 下载方式说明。
3. **错误提示**：尤其是带数字、带来源名和带下一步的句子。
4. **空状态**：每个页面 1–2 条，数量不大，但用户经常看到。
5. **无障碍描述**：数量少，但迁移后便于统一检查。
6. **一次性标签**：最后处理；不要为了「全部资源化」引入大量只出现一次、语义又很窄的 key。

### 5.3 key 命名

建议 `screen_component_purpose`，全小写、下划线分隔：

```text
common_cancel
common_close
common_retry
library_import_file
library_import_folder
library_remove_from_library
download_status_failed
download_delete_task_message
settings_cache_limit
settings_cache_clear
model_settings_whisper_token
model_settings_translate_effort
youtube_save_cookie
```

不要使用 `string1`、`text2` 这类无语义 key；也不要把整段帮助塞进一个 key，按段落拆成 `..._intro`、`..._usage`、`..._note`。

### 5.4 占位符转换

迁移时最容易出错的是 Kotlin 字符串模板。示例：

| 当前 Kotlin | 资源写法 | 调用 |
| --- | --- | --- |
| `"已导入 $count 首"` | `<string name="library_imported_count">已导入 %1$d 首</string>` | `stringResource(R.string.library_imported_count, count)` |
| `"已用 ${formatCacheBytes(used)} / 上限 ${formatCacheBytes(limit)}"` | `<string name="settings_cache_usage">已用 %1$s / 上限 %2$s</string>` | `stringResource(..., usedText, limitText)` |
| `"${viewModel.videos.size} 个视频"` | `<string name="youtube_video_count">%1$d 个视频</string>` | `stringResource(..., viewModel.videos.size)` |
| `"已选 ${viewModel.selected.size} 个"` | `<string name="common_selected_count">已选 %1$d 项</string>` | `stringResource(..., size)` |

规则：

- `$name` / `${expr}` 改成 `%1$s`（字符串）或 `%1$d`（整数），并在调用处传参。
- 需要显示百分号时写 `%%`，不要写单个 `%`。
- 单引号写 `\'`，双引号写 `\"`，换行写 `\n`。
- 当前没有 HTML/CDATA；如果以后需要加粗，使用 `<b>` 并配合 `AnnotatedString` 或 `HtmlCompat`，不要直接把标签塞进普通 `Text`。
- 不要在一个字符串里同时使用位置参数和普通 `%s`；统一用 `%1$s`、`%2$s`。
- 中文没有复数变化，`plurals` 可以只提供一个 `other`；如果未来加英文，再补 `one`。

### 5.5 技术约束

- **Compose**：在 Composable 中用 `stringResource`；在 `ViewModel`、Service、Repository 中不能直接用 `stringResource`。
- **数据层**：不要让纯数据类持有 `Context` 只为取文案。建议数据层返回错误码/参数，UI 层映射为文案；过渡期可以让 Service 通过 `applicationContext.getString` 取通知文案。
- **通知渠道名**：`NotificationChannel(CHANNEL_ID, getString(R.string.channel_downloads), ...)`；渠道名一旦创建，系统会保留旧名称，改名需要新渠道 ID 或接受旧名。
- **测试**：先搜索 `app/src/test` 与 `app/src/androidTest` 是否断言了中文文案。若有，迁移时同步更新测试，或改为断言状态码。
- **不要迁移**：`DlsiteGenres.kt` 的 320 条标签是内容数据，建议保留在代码或 JSON 资产中；它们会随站点标签变化，不属于界面 chrome。
- **不要迁移**：数据库 SQL 中的中文字符串（如 `"已中止"`、`"需要验证后继续"`）是持久化状态值，改动会影响历史数据匹配。若必须改，需同时提供迁移或兼容读取。

### 5.6 可自动化的检查

迁移前可先用脚本建立基线，迁移后用同样的规则验收：

```sh
# 仍含中文的 Kotlin 字符串
rg -n '"[^"]*\p{Han}[^"]*"' app/src/main/java -g '*.kt'

# 禁用词
rg -n '口令|普通播放列表|自建列表|整作|挂在其他曲目|匹配成功|JSON 对象|source 编号|HTTP [0-9]' app/src/main/java -g '*.kt'

# 数字与单位之间缺少空格
rg -n '[0-9](GB|MB|KB|MiB|GiB)' app/src/main/java -g '*.kt'

# 中文与 Latin 之间缺少空格（近似，需人工复核）
rg -n '[\p{Han}](DLsite|YouTube|asmr\.one|B站)|(DLsite|YouTube|asmr\.one)[\p{Han}]' app/src/main/java -g '*.kt'
```

注意：最后一条正则会命中 XML/URL 等非界面文本，需要人工筛选。

---

## 6. 需要你确认的产品决策

以下不是单纯的文案替换，涉及信息架构或习惯，先列我的建议，等你确认后再落到代码。

### 6.1 两个「下载」页面怎么命名

现状：

- 「下载与任务」：任务页，分段「音频 / 字幕任务」。
- 「下载管理」：文件页，分段「音频 / 字幕」。
- 顶栏下载图标读作「下载任务」，角标包含听写和翻译。

我的建议：

- 任务页标题：「任务」或「下载任务」；分段「下载 / 字幕任务」。
- 文件页标题：「已下载文件」；设置入口同步「已下载文件」。
- 顶栏图标读作「下载与字幕任务」。

这样「任务」和「文件」分开，用户不会在两个相似页面之间迷路。需要你确认是否接受改名。

### 6.2 「收藏在线列表」是否改成「收藏到播放列表」

我的建议：改成「收藏到播放列表 / 已收藏到播放列表 / 移出播放列表」。理由：这些条目实际出现在「播放列表」页，而「收藏」与 B 站收藏夹冲突。

如果你希望保留「在线列表」这个产品概念，也可以统一为「收藏在线列表 / 查看收藏的在线列表 / 取消收藏」，但需要在播放列表页给这些条目一个可见的「在线」标签。请选择一种。

### 6.3 「SESSDATA 登录」是否面向普通用户

我的建议：按钮改为「粘贴登录信息」，对话框标题「粘贴 B 站登录信息」，字段标签保留「SESSDATA 或 Cookie」。高级用户仍然知道要粘贴什么，普通用户不会被 `SESSDATA` 吓到。

如果你希望保留技术词以强调这是高级入口，也可以保持，但要在按钮旁加一句「适合从浏览器复制登录信息」。

### 6.4 strings.xml 迁移范围

我的建议：分三批。

1. 第一批：标准动作/状态 + 通知渠道 + 无障碍描述（约 100 条）。
2. 第二批：错误提示 + 空状态 + 长帮助（约 250 条）。
3. 第三批：其余一次性文案。

不建议一次全量迁移，原因是 `ViewModel`/Service 里取字符串需要改造，且中文单语环境下收益有限。需要你确认是否按批次做，以及是否要同时建立 `values-en`。

### 6.5 第二人称「你」的使用

我的建议：说明句可以用「你」，按钮和标签省略主语；全站不用「您」。当前只有少数句子用「你」，可以保留，不需要为了统一而全部改写。

---

## 7. 建议实施顺序与验收

### 7.1 顺序

**P0：先修误导和截断（纯文案 + 少量布局）**

1. 任务失败徽章截断（0.3-1）。
2. 混合删除对话框（0.3-2）。
3. 「口令」→「API Key」（0.3-3）。
4. 「普通播放列表」/「自建列表」（0.3-4）。
5. 「收藏在线列表」（0.3-5）。
6. 设置弹窗「取消」→「关闭」（0.3-6）。
7. YouTube「未保存」/批量提示（0.3-7）。
8. 收藏夹空状态（0.3-8）。
9. 文件预览死胡同（0.3-9）。
10. 整作下载结果（0.3-10）。
11. 「下载准备已结束」（0.3-11）。
12. 数字单位与空格（0.3-12）。

**P1：术语表落地**

- 按 1.1 的词表改 UI 文案；数据层只在用户可见的消息里改，不动数据库状态值。
- 统一 `contentDescription`。

**P2：错误与空状态**

- 按 2.2 的表逐条改写；优先改「生成失败」「操作失败，请重试」「无法打开这个文件」这类兜底。
- 按 2.3 补下一步。
- 长结果改用 `SnackbarDuration.Long` 或页面内展示。

**P3：排版与无障碍**

- 按 4.1–4.4 修空格、截断、读屏标签。
- 在 360 dp × 字体 2.0 下截图复核。

**P4：资源化**

- 按 5.2 的三批迁移；先做标准词和通知渠道。

### 7.2 验收清单

- [ ] 全仓库搜索不到「口令」「普通播放列表」「自建列表」「整作」「挂在其他曲目」「匹配成功」等面向用户的旧词。
- [ ] 所有失败状态都能看到原因和下一步；没有只写「失败」的页面。
- [ ] 所有徽章文本不超过 6 个汉字；没有把错误句子放进 `StatusBadge`。
- [ ] 所有 Snackbar 文案在 30 个汉字以内，或使用 `Long`/页面内展示。
- [ ] 360 dp × 字体 2.0 下，顶栏、状态徽章、FlowRow、对话框按钮无截断。
- [ ] TalkBack 能读出「已选 N 项」「下载与字幕任务，N 项进行中」和每个「更多」的具体对象。
- [ ] 数字与单位、中文与 Latin 之间空格一致。
- [ ] `strings.xml` 迁移后，没有 `$` 模板残留在 XML；`%1$s`/`%1$d` 参数数量与调用一致。
- [ ] 数据库中的中文状态值未被文案替换误改。

---

## 附录 A：高频文案

| 字面量 | 次数 | 处理建议 |
| --- | ---: | --- |
| 取消 | 35 | 大多是按钮，合理；区分「取消选择」「关闭」「保留」 |
| 生成失败 | 22 | 不要单独出现；按听写/翻译/润色补上下文 |
| 已中止 | 17 | 好，保留 |
| 更多 | 14 | 全部改为具体对象的无障碍描述 |
| 重试 | 10 | 好，保留 |
| 加入音频库 | 10 | 好，保留 |
| 从音频库移除 | 9 | 好，保留 |
| 已加入音频库 | 8 | 好；批量操作要统计实际新增 |
| 删除 | 9 | 好；菜单里加对象（删除字幕/删除文件） |
| 关闭 | 9 | 好，保留 |
| 这部作品已经在音频库 | 7 | 好，保留 |
| 登录已失效，请重新登录 | 7 | 好，保留 |
| 无法打开这个文件 | 7 | 按场景区分：文件缺失/网络/无应用 |
| 加入播放队列 | 7 | 好，保留 |
| 这个文件夹里没有可对照的文件 | 5 | 可改「这个文件夹里没有能和作品对应的文件」 |
| 没有需要下载的音频 | 5 | 好，保留 |
| 已加入 N 个下载任务 | 4 | 好；补上 N 的路径要一致 |
| 操作失败，请重试 | 3 | 换成具体动作失败 |
| 暂时无法连接 | 3 | 补对象和下一步 |

## 附录 B：界面层文案分布（Top 25）

| 文件 | 中文字面量 | 主要类型 |
| --- | ---: | --- |
| `ui/asmr/OnlineScreens.kt` | 153 | 搜索、详情、登录、已购买 |
| `ui/player/PlayerChrome.kt` | 64 | 播放、队列、字幕、定时 |
| `ui/download/DownloadScreen.kt` | 54 | 任务状态、删除确认 |
| `ui/settings/ModelSettingsScreen.kt` | 52 | 听写/翻译设置与帮助 |
| `ui/library/LibraryViewModel.kt` | 50 | 操作结果、错误、缓存提示 |
| `ui/library/LibraryRows.kt` | 49 | 行菜单、确认弹窗 |
| `ui/bilibili/BiliCollectionScreen.kt` | 41 | 在线列表 |
| `ui/library/LibraryScreen.kt` | 37 | 音频库、导入、筛选 |
| `ui/youtube/YouTubeScreens.kt` | 36 | 搜索、账号 |
| `ui/bilibili/BiliBrowser.kt` | 31 | 搜索、分 P |
| `ui/asmr/JapaneseAsmrVerificationScreen.kt` | 29 | 网页验证 |
| `ui/settings/SettingsScreen.kt` | 24 | 设置首页 |
| `ui/playlist/PlaylistScreens.kt` | 24 | 播放列表 |
| `ui/library/FilePreviewScreen.kt` | 24 | 文件预览 |
| `ui/download/StoredFilesScreen.kt` | 22 | 下载管理 |
| `ui/bilibili/BiliVideoScreen.kt` | 20 | 多 P 视频 |
| `ui/settings/AboutScreen.kt` | 19 | 关于与更新 |
| `ui/bilibili/BiliCollectionViewModel.kt` | 19 | 在线列表状态 |
| `ui/library/LibraryWorkScreen.kt` | 18 | 作品页 |
| `MainActivity.kt` | 16 | 导航、剪贴板提示 |
| `ui/library/FavoriteScreen.kt` | 13 | B 站收藏夹 |
| `ui/importing/ClipboardImportDialog.kt` | 12 | 剪贴板弹窗 |
| `ui/settings/AppearanceScreen.kt` | 11 | 外观 |
| `ui/bilibili/BiliImportDialog.kt` | 11 | 导入方式 |
| `ui/settings/CacheScreen.kt` | 9 | 缓存 |

数据层 Top 6：`LibraryRepository.kt` 59、`DownloadCenter.kt` 58、`TaskProgressDisplay.kt` 49、`TranslateClient.kt` 30、`TranslateCenter.kt` 24、`WhisperCenter.kt` 23。

## 附录 C：与 2026-11-07 审查的对照

已解决：

- 在线作品排序已写明方向（`OnlineScreens.kt:193-203`）。
- B 站手动登录显示「已保存」，扫码登录才显示「已登录」（`OnlineScreens.kt:2508-2521`）。
- 关于页优先显示版本名，不再把 `android-1.3` 放在主位置（`AboutScreen.kt:96-101`）。
- `DeleteAudioDialog` 提到的「设置 → 下载管理 → 字幕」入口已存在（`StoredFilesScreen.kt:263-275`）。
- 下载任务页标题已改为「下载与任务」（`DownloadScreen.kt:219`）。
- 「已发布」「关联文件」「查看返回」已改为「已解压并加入作品」「整理入库」「查看错误详情」。
- 「未检测到人声」取代了旧的「没有听出人声」。

仍然存在：

- 「缓存」的边界仍需在 UI 里解释（`CacheScreen.kt:53-70`）。
- 下载页与下载管理的命名仍需决策（见 6.1）。
- 混合删除的按钮语义（见 0.3-2）。
- 保存/导入/加入音频库的历史混用已基本收敛，但剪贴板弹窗仍写「导入这个内容？」和「导入」（见 3.13）。
- 「收藏/在线列表」仍混用（见 0.3-5）。

本次新增：

- 任务失败徽章截断（0.3-1）。
- 默认时长 Snackbar 承载长结果（2.4）。
- 全部文案硬编码、零 `stringResource`（5.1）。
- YouTube「未保存」与批量提示（0.3-7）。
- 数字单位与中英混排空格（0.3-12）。
- 设置选择弹窗的「取消」语义（0.3-6）。
- 无障碍描述泛化（4.3）。
- 本轮确认：「下载到本地」改为「下载整部作品」（0.4-A）。
- 本轮确认：Japanese ASMR 的「文章」改为「篇目」（0.4-B）。
