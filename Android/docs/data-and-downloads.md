# 数据库、下载与文件约束

## 1. Room 数据库与迁移

- `AppDatabase` 当前版本为 **24**，`exportSchema = false`。新安装按实体建表，升级使用显式 `MIGRATION_*`；不支持数据库降级。
- 新增可空列时，迁移 `ALTER TABLE ... ADD COLUMN` 禁止设置 `DEFAULT`，实体字段禁止设置 `@ColumnInfo(defaultValue)`，确保新装与升级结构一致（沿用 `endpoint`、`translatedAt`、`translateStatus`、`skipTranslate`、`model`、`biliImportPart` 等列规范）。
- 历史迁移关键节点（18→24）：
  - **18→19**：移除废弃表 `bili_import_intents`，保留恒为 NULL 的 `downloads.biliImportPart` 列（minSdk 24 的 SQLite 不支持 `DROP COLUMN`，不重建下载表）。
  - **19→20**：为下载、听写和独立翻译任务增加可空 `progressSummary` 摘要列。
  - **20→21**：新增 `japaneseasmr_posts`、`japaneseasmr_media` 与可空 `queue_items.mediaKind`。
  - **21→22**：新增 Japanese ASMR 整作下载清单及压缩包版本表、任务入口摘要及确认选项、`japaneseasmr_media.pathHash` 和 `queue_items.mediaPathHash`。
  - **22→23**：为 `downloads` 增加 `dismissedAt`，为 `whisper_jobs` 与 `translation_jobs` 增加 `createdAt`、`dismissedAt`（五个可空列均不设 SQL `DEFAULT`）。
  - **23→24**：新增 `track_subtitles` 曲目字幕绑定表（`trackId` 唯一外键级联删除、`fileId` 可空外键级联删除，含 `kind`、`fileId`、`uri`、`treeUri`、`displayName`、`offsetMs`、`updatedAt`），为 `translation_jobs` 增加可空 `targetKind`、`outputFilename`，为 `whisper_jobs` 增加可空 `forceRegenerate`（三个可空列均不设 SQL `DEFAULT`）。
- 兼容与事务规则：
  - `skipTranslate` 为 `null` 或 `false` 的旧任务，听写完成后仍执行翻译；旧任务 `model` 为空时保留按地址匹配的兼容逻辑。
  - 修改曲目、作品、下载、队列或播放列表时保留曲目身份与字幕关联；文件复用不得通过删除并重建曲目实现。
  - 作品填充与导入串行执行，查重在取得锁后重新判定；作品目录与曲目在同一事务内提交，中断不留半个作品；撞唯一索引时提示作品已在音频库，不留残缺目录。

## 2. 下载队列与任务管理

- 「下载与任务」页使用「音频 / 字幕任务」分段选择并分别保留滚动位置；音频包含完整原始包，字幕任务包含听写、翻译和润色。
- 用户确认后由独立 `dataSync` 前台服务消费音频下载队列（含 DLsite 整包下载解压发布与 B 站附带字幕后台抓取）。进程终止后，仍有效且在排队的 Japanese ASMR 整作下载与 B 站待补字幕下次启动时接手；已开工音频任务标为中断并可手动重试；等待网页验证的整作任务可点「验证并继续」或「放弃任务」（删除任务记录和本次暂存，保留已入库文件）。
- 任务结束后「删除任务记录 / 清除已完成」通过 `dismissedAt` 软隐藏，不删仍被隐藏记录复用的生成字幕文件。失败任务删除暂存和记录，完成记录只隐藏；`pendingBiliSubtitle` 为真或存在 `subtitle_publications` 发布记录的任务不得隐藏或删除。隐藏记录不参与列表展示，但仍参与按指纹匹配和字幕复用。
- 字幕任务页将听写、独立翻译和手动润色按 `createdAt` 从新到旧混排；重试刷新 `createdAt` 并只清除对应阶段进度摘要；听写完成但翻译失败可单独重试翻译，重试与手动翻译共用提交锁防重复入队。
- 下载管理使用「音频 / 字幕」分段选择（默认音频，各自保位）；音频包含已落盘在线音频与 B 站音轨，字幕包含生成字幕、作品下载目录中 `lrc/srt/vtt`、应用内导入副本与独立翻译覆盖文件；搜索和多选作用于当前分类；顶栏下载入口展示进行中任务。
- 下载入口统一在音频库及库内详情；已入库曲目可确认听写前置下载。入队、开工、重试及发布均重核曲目或整包作品根目录；未入库历史任务保留文件和记录，重试须精确匹配已入库内容；移除库内内容后中止相应未完成任务。

## 3. 删除副本、原包、外部打开与缓存边界

- 在线曲目优先播放有效本地副本。删除已下载文件时保留在线曲目并清理完成下载记录；带有生成字幕时先询问是否一并删除，保留则字幕文件与听写/翻译记录均不动，重下同一音频自动匹配（所有来源一致；B 站随音轨保存的视频字幕仍随音轨删除）。
- 术语与清理边界：用户主动保存到本机的音频称「下载」（`filesDir`），自动生成的临时文件、元数据和字幕称「缓存」（`cacheDir`）。「清理缓存」仅清理 `cacheDir`（默认上限 200 MB，按修改时间从早到晚执行，保留 Coil `journal*`），绝不触碰 `filesDir` 中的下载、字幕或听写暂存。
- 所有音频任务均可中止：普通音频传输结束清理本次半成品、启动回收残留；DLsite 整包暂存供断点续传，仅删除失败任务或启动清理无任务引用的孤儿暂存目录时回收。
- DLsite 完整原始包使用独立作品任务和原始目录清单：下载、解压与事务发布全部成功后才替换本地关联并删压缩包，失败不影响已有副本、不自动降级为 Play 音轨；移除库内作品不连带删除原始清单身份或磁盘文件。
- 原始文件按作品号和完整相对路径关联，保留匹配曲目编号、Play 身份及字幕关系，不得只按文件名前缀或去扩展名合并不同音轨；原包独有曲目不得伪造 Play 身份，缺本地文件时仅可重下完整包；包内文件已关联其他曲目时跳过并保留原关联，汇总提示后继续发布其余文件。
- `localUri` 为 App 目录绝对路径时可删除文件；为关联 `content:` 或 `file:` URI 时仅清除关联，保留用户原文件及文件夹；LOCAL 曲目不适用此删除操作。
- 删除生成字幕时清理生成文件及已完成听写/翻译记录，保留进行中听写、原有字幕和远端字幕；仅删除生成字幕（`discardGeneratedOnly`）不清除 `track_subtitles` 手动绑定；仅删除字幕不得连带删除音频；单独删除翻译覆盖文件只清翻译记录，不动听写。
- 作品内非音频附件与字幕文件支持长按多选，本地删除统一通过 `LocalRemovalPlan` 与 `DownloadCenter.deleteLocalTargets` 按文件路径去重统计与执行；保留字幕时不删除生成字幕、作品内字幕及专属自定义字幕副本。
- 未知文件经 `FileProvider` 或可转授 content URI 在外部打开；远端文件暂存于 `cacheDir/external-open`，不入库、不传登录凭证；暂存目录在下载及当前预览页使用期间受保护，退出后纳入缓存清理，失败和取消清理本次半成品。
