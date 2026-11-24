# 项目开发指南

## 1. 项目范围

本仓库包含两个工程：

- `Android/`：Android 音频播放器，应用名为「音频播放器」，包名为 `com.nanahoshi.audioplayer`，使用 Jetpack Compose。
- `DesktopDemo/`：Windows 桌面播放器，使用 WPF / .NET 8。

仓库地址：<https://github.com/nanahoshi114/AudioPlayer.git>。

本指南主要适用于 Android 工程。下文源码目录相对于 `Android/app/src/main/java/com/nanahoshi/audioplayer/`；构建命令在 `Android/` 下执行。

Android 支持本地音频、B 站音轨、YouTube 独立音轨、asmr.one 作品、Japanese ASMR 公开文章音轨及用户在 DLsite 购买的内容。当前产品行为以代码为准，功能变更应同步更新 `Android/README.md` 及相关接口文档。**在本仓库内进行任何 UI、主题、组件、布局、图标、文案视觉或动效修改，都必须最优先、严格遵守仓库根目录的 [`DESIGN.md`](DESIGN.md)（修改前尚未完整读取该文件时须先读取），严禁违背其中的色彩语义 Token（`LocalDarkTheme`、`LocalSourceColors`、`ListItem` 透明容器）、排版与 `strings*.xml` 文案资源化、`AppShapes`/间距/≥48dp 触控区/`BadgeLine` 布局限制、克制阴影描边、图标库以及 `Motion`/`LocalReduceMotion` 规范。**本文件保留稳定的开发约束，不累积逐次构建结果或临时调试记录。

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

#### 模拟器仪器测试

本机已配置可复用的 headless 模拟器 AVD `audiotest_api36`（Pixel 6，Android 16 / API 36，`system-images;android-36.1;google_apis_playstore;arm64-v8a`），无需真机即可运行 Room、迁移和纯逻辑类仪器测试。模拟器包与 AVD 分别位于 `~/Library/Android/sdk` 和 `~/.android/avd`，缺失时用 `sdkmanager` 安装 `emulator` 及上述系统镜像。

```sh
export PATH="$HOME/Library/Android/sdk/platform-tools:$HOME/Library/Android/sdk/emulator:$PATH"
emulator -avd audiotest_api36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot &
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'

cd Android
export GRADLE_USER_HOME="$HOME/.gradle"
./gradlew :app:connectedDebugAndroidTest --no-configuration-cache \
  -Pandroid.testInstrumentationRunnerArguments.class=com.nanahoshi.audioplayer.data.StoredCatalogOverlayAndroidTest

adb emu kill
```

- `class` 参数传逗号分隔的多个类时只会执行第一个，须逐类运行；不传该参数则运行全部仪器测试。
- Compose UI 仪器测试会因旧版 Espresso 反射调用 `InputManager.getInstance()` 失败（`NoSuchMethodException`），属环境问题，不要当作代码回归；验证改动优先使用非 UI 套件。
- 在模拟器上安装应用同样需要用户明确授权；模拟器结果不能替代真机对通知、存储和系统交互的验证，两者须分别说明。

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
- `Android/docs/japaneseasmr-api.md`：公开网页、文章与音轨身份、播放和 HLS 下载。
- `Android/docs/whisper-api.md`：听写接口与本机模型。
- `Android/docs/update-api.md`：更新检查与发布约定。

### 3.2 源码

- `ui/components`：通用顶栏、多选栏、来源标签和空状态。
- `ui/library`、`ui/playlist`：音频库、作品页、文件预览、播放列表及在线列表。
- `ui/player`、`playback`：播放界面、队列、播放服务及字幕读取。
- `ui/asmr`：在线搜索、作品详情、「我的」及已购买列表。
- `bilibili`、`ui/bilibili`：搜索、投稿、分 P 操作、播放地址、收藏夹、字幕、登录及合集／系列。
- `youtube`、`ui/youtube`：搜索、列表、音轨播放下载、签名脚本及 Cookie 管理。
- `asmr`、`dlsite`、`japaneseasmr`：作品元数据、远端文件和授权。
- `ui/importing`：剪贴板预览及确认导入。
- `ui/settings`、`ui/theme`：设置、外观、模型配置及更新检查。
- `ui/download`、`data`：任务管理、文件管理、凭证、听写和翻译。
- `data/db`：Room 实体、查询与迁移。
- `AppGraph.kt`：在 `Application.onCreate` 中按依赖顺序组装服务。

## 4. 数据与文件约束

### 4.1 数据库

- `AppDatabase` 当前版本为 **24**，`exportSchema = false`。新安装按实体建表，升级使用显式 `MIGRATION_*`；不支持数据库降级。23→24 新增 `track_subtitles` 曲目字幕绑定表（`trackId` 唯一外键级联删除、`fileId` 可空外键级联删除，包含 `kind`、`fileId`、`uri`、`treeUri`、`displayName`、`offsetMs`、`updatedAt`），为 `translation_jobs` 增加可空 `targetKind`、`outputFilename`，为 `whisper_jobs` 增加可空 `forceRegenerate`，三个新增可空列均不设置 SQL `DEFAULT`。22→23 为 `downloads` 增加 `dismissedAt`，为 `whisper_jobs` 增加 `createdAt`、`dismissedAt`，为 `translation_jobs` 增加 `createdAt`、`dismissedAt`，五个可空列均不设置 SQL `DEFAULT`。21→22 增加 Japanese ASMR 整作下载清单及压缩包版本表、任务入口摘要及确认选项、`japaneseasmr_media.pathHash` 和 `queue_items.mediaPathHash`。20→21 新增 `japaneseasmr_posts`、`japaneseasmr_media` 与可空 `queue_items.mediaKind`。19→20 为下载、听写和独立翻译任务增加可空的 `progressSummary` 摘要列。18→19 移除废弃的 `bili_import_intents` 表，保留恒为 NULL 的 `downloads.biliImportPart` 列（minSdk 24 的 SQLite 不支持 DROP COLUMN，不重建下载表）。
- 新增可空列时，迁移中的 `ALTER TABLE ... ADD COLUMN` 不设置 `DEFAULT`，实体字段不设置 `@ColumnInfo(defaultValue)`，确保新装与升级结构一致。现有 `endpoint`、`translatedAt`、`translateStatus`、`skipTranslate`、`model`、`biliImportPart` 等列遵循此规则。
- `skipTranslate` 为 `null` 或 `false` 的旧任务，听写完成后仍执行翻译。旧任务 `model` 为空时保留原有按地址匹配的兼容逻辑。
- 修改曲目、作品、下载、队列或播放列表时，应保留曲目身份及字幕关联；文件复用不得通过删除并重建曲目实现。
- 作品填充与导入串行执行，查重在取得锁后重新判定；作品目录与曲目在同一事务内提交，中断不留半个作品。撞唯一索引时提示作品已在音频库，不留下残缺目录。

### 4.2 下载与删除

- 下载与任务页使用「音频 / 字幕任务」分段选择，分别保留位置；音频包含完整原始包，字幕任务包含听写、翻译和润色。
- 用户确认后由独立的 `dataSync` 前台服务准备并消费音频下载队列，覆盖 DLsite 完整原始包下载、解压及发布，也负责 B 站附带字幕的后台抓取。进程终止后，仍有效、尚在排队的 Japanese ASMR 整作下载及 B 站待补字幕下次打开应用时接手。已经开工的音频任务标为中断，可手动重试。等待网页验证的整作任务可点「验证并继续」；不再继续时可「放弃任务」，删除任务记录和本次暂存，已加入作品的文件保留。
- 任务结束后的「删除任务记录 / 清除已完成」通过 `dismissedAt` 软隐藏，不删除仍被隐藏记录复用的生成字幕文件。失败任务删除暂存和记录，完成记录只隐藏；`pendingBiliSubtitle` 仍为真或存在 `subtitle_publications` 发布记录的任务不得隐藏或删除。隐藏记录不参与任务列表，但仍参与按指纹匹配和字幕复用。
- 字幕任务页把听写、独立翻译和手动润色按 `createdAt` 从新到旧混排；重试刷新 `createdAt` 并只清除对应阶段的进度摘要。听写完成但翻译失败时可单独重试翻译，重试与手动翻译共用提交锁并防止重复入队。
- 下载管理使用「音频 / 字幕」分段选择，默认显示音频，分别保留滚动位置。音频包含已落盘的在线音频和 B 站下载音轨，字幕包含生成字幕、作品下载目录中的 `lrc/srt/vtt`、应用内导入字幕副本及独立翻译覆盖文件。每行显示文件大小，搜索和多选作用于当前分类。顶栏下载入口展示进行中的任务。
- 下载入口统一在音频库及库内详情。已入库曲目可以确认听写前置下载。入队、开工、重试及发布均重新核对曲目或整包作品根目录。未入库历史任务保留文件和记录，重试时须找到精确匹配的入库内容。移除库内内容后中止相应未完成任务。
- 在线曲目优先播放有效的本地副本。删除已下载文件时保留在线曲目并清理完成的下载记录；音频带有生成字幕时先询问是否一并删除。选择保留时字幕文件与听写、翻译记录都不动，可在下载管理「字幕」中单独删除，重新下载同一音频会自动匹配、无需重新生成；所有来源一致，B 站随音轨保存的视频字幕仍随音轨删除。
- 用户主动保存到本机的音频统一叫「下载」，App 自动生成的临时文件、元数据和字幕叫「缓存」；「清理缓存」只处理临时缓存。
- 所有音频任务均可中止。普通音频传输结束时清理本次半成品，启动时回收进程终止残留。DLsite 整包暂存用于断点续传，删除失败任务时才一起回收，启动时清理没有任务引用的孤儿整包暂存。
- DLsite 完整原始包使用独立作品任务和原始目录清单；下载、解压和事务发布全部成功后才替换本地关联并删除压缩包，失败不影响已有副本、不自动降级为 Play 音轨。移除音频库内容不连带删除原始清单身份或磁盘下载。
- 原始文件按作品号和完整相对路径关联，保留匹配曲目编号、Play 身份及字幕关系；不得只按文件名前缀或去掉扩展名合并不同音轨。原包独有曲目不得伪造 Play 文件身份，缺少本地文件时仅可重新下载完整包。包内文件已关联其他曲目时跳过并保留原关联，汇总提示后继续发布其余文件，不整体失败。
- `localUri` 为 App 目录绝对路径时可删除文件；为关联的 `content:` 或 `file:` URI 时仅清除关联，保留用户文件及文件夹。LOCAL 曲目不适用此删除操作。
- 删除生成字幕时，清理生成文件及已完成的听写、翻译记录；保留进行中的听写、原有字幕文件和远端字幕。仅删除生成字幕时（`discardGeneratedOnly`）不清除 `track_subtitles` 手动绑定。仅删除字幕不得连带删除音频。单独删除翻译覆盖文件时只清理翻译记录，不动听写。
- 作品内非音频附件与字幕文件支持长按多选，本地删除通过 `LocalRemovalPlan` 与 `DownloadCenter.deleteLocalTargets` 统一按文件路径去重统计与执行；保留字幕时不删除生成字幕、作品内字幕及专属自定义字幕副本。
- 未知文件通过 `FileProvider` 或可转授的 content URI 在外部打开；远端文件暂存于 `cacheDir/external-open`，不入库、不传递登录凭证。暂存目录在下载及当前预览页使用期间受保护，退出后参与缓存清理；失败和取消清理本次半成品。
- 「清理缓存」仅处理 `cacheDir`，不处理 `filesDir` 中的下载、字幕或听写暂存。默认上限 200 MB，自动清理按修改时间从早到晚执行，并保留 Coil 的 `journal*`。

## 5. 界面与播放

### 5.1 导航与通用行为

- 底栏包括音频库、播放列表、在线搜索、我的、设置。音频库作品页、在线作品详情、自建播放列表详情（`playlist/{id}`）、B 站分 P 页面（`bili/video/{folderId}`）及合集／系列详情（`bili/collection/{ref}`）保留底栏及播放条，隐藏主页面顶栏。其他独立功能页隐藏主页面顶栏和底栏。
- 未入库内容使用 `PlayerClient.playQueueItem`；仅对 `track.id > 0` 的已入库曲目使用 `playTrack` 追加逻辑。
- 播放列表包含自建列表及本地收藏的 B 站合集／系列。在线列表成员独立于曲目，禁止本地改名、拖动改写目录顺序和增删单项；允许正序、倒序和随机的本地浏览及操作排序，不修改远端目录缓存或当前队列，多 P 视频内部保持 P 序号顺序。多选时隐藏顶部整合集播放及追加按钮。刷新全部分页成功后事务替换目录，取消收藏不删除曲目、下载或当前队列。条数与总数不一致或分页空转时自动重读一次；仍不一致则展示已读内容并标注目录可能不完整，不覆盖已保存的完整目录。
- 自建播放列表卡片按曲目顺序选取最多三个不同作品的封面。音频库作品按作品根目录识别，B 站和 YouTube 按视频编号识别，无作品归属的曲目按曲目编号识别。封面地址及图片内容不参与去重。
- 队列弹层使用 `sheetGesturesEnabled = false`，隐藏默认把手，通过关闭按钮、遮罩或返回键关闭；保留列表滚动及拖动排序。
- 作品和搜索卡片的标签使用 `BadgeLine`，RJ 号固定显示，超出宽度的标签收起为 `+N`；`LabelBadge` 保持单行。
- `BadgeLine` 使用 `BoxWithConstraints`，不得直接放入 Material3 `ListItem` 等会查询固有尺寸的内容槽；带此标签的列表卡片使用普通 `Row` / `Column` 布局。
- 主题与系统栏图标使用应用实际主题状态；需要判断深浅时使用 `LocalDarkTheme`。封面取色仅在选择该模式后执行，取色失败时保留已有颜色。

### 5.2 音频库与作品页

- 作品页路由为 `library/work/{folderId}`，使用独立的 `LibraryViewModel`，不复用音频库列表实例。
- 音频库作品页和在线详情共用 `WorkFileBrowser` 两层布局；在线详情保留图库、试听和原有操作，图库可打开缩放大图。
- 作品页使用两层滚动：向下先滚外层，外层到底后滚文件列表；向上先滚文件列表，到顶后滚外层。`WorkPageScrollConnection` 负责协调，文件标题、来源切换和路径栏位于文件 `LazyColumn` 外。进入子文件夹仅重置内层位置。
- 作品页的列表、播放队列及多选顺序固定为 `LibrarySort.NAME`。路径栏以「根目录」为起点，点击祖先直接跳转，路径超宽时自动露出当前目录。返回操作逐层上移，到作品根后退出，不越过作品根。目标目录与来源的列表就绪后才恢复内层滚动；预览返回和屏幕旋转保留当前目录、来源及滚动位置。「文件」分区提供「显示字幕文件」开关（默认隐藏，关闭时同步从多选移除被隐藏的字幕文件）；选中不含可用音频的附件/字幕或纯附件目录时，顶栏隐藏播放、入队、下载与歌单操作，仅保留删除副本或取消关联（及普通目录下的移除）操作。作品页顶栏菜单支持按名称/顺序跨目录或选择外部文件夹批量关联字幕，曲目与字幕文件单条菜单支持互相手动关联。
- 同一作品含多套文件时，显示、播放和作品页下载均以 `workFileSource` 为准。来源为 DLsite Play、asmr.one 与 Japanese ASMR，「在线作品」筛选覆盖三种来源共用的 RJ 作品根。已有 Play 文件时直接允许来源切换；否则在后台确认购买状态，不阻塞页面打开。文件来源判定使用 `resolveLibraryFileSource(..., jaFileIds)`，避免将 JA 附件误归为 `asmr.one`。
- 下载来源选择使用已有购买及收录信息。多个来源可用时先选择来源；Japanese ASMR 多篇文章先选择一篇，再在该篇去重后的下载入口中选择一个。无需选择时，作品页使用当前来源，作品页外优先使用 DLsite。选定 DLsite 后直接下载整个作品的原始压缩包并自动解压，不以已有 Play 文件或单曲本地副本代替完整原始包。
- asmr.one 下载同目录同名前缀、仅扩展名不同的音频时，使用 `findFormatConflicts` 确认格式组合；无冲突文件正常处理。DLsite 完整原始包不做格式筛选，保留全部原始文件。普通文件夹维持整目录播放和下载行为。
- 作品内部的曲目、文件及子文件夹禁止单独从音频库移除；所有入口共用作品归属判断，仓储事务再次校验。整部作品或包含整部作品的普通父目录仍可移除，批量操作包含未被整作品覆盖的受限条目时整体拒绝。删除下载副本、播放列表及队列移除不受此限制。
- 多选中包含作品文件夹时，仅提供删除已下载文件和移除操作。播放列表从音频库多选添加曲目。
- 普通文件夹内隐藏来源筛选，显示和递归搜索不受根目录来源限制；范围为当前目录及后代，不返回范围外祖先、不触发在线 RJ 提示，返回根目录恢复筛选。导入菜单不提供新建文件夹，内部目录创建保留。
- 音频库搜索匹配文件夹名、作品标签、曲目名和文件名；纯数字同时匹配作品编号。作品内部结果按 `workRootOf` 归并至最近作品根，避免重复展示作品。

### 5.3 本地文件识别

- 普通本地文件夹关联为作品时，优先匹配已购买且可访问的 DLsite 文件，失败后匹配 asmr.one；应区分连接失败与内容不匹配。
- 本地目录可缺少作品文件，但不得包含作品文件树之外的内容。关联成功应保留曲目编号、本地路径和字幕关联。
- 已加入音频库的作品使用 `recognizeLocalTree`，按当前 `workFileSource` 匹配系统选择的目录，仅更新匹配曲目的 `localUri`，不新建曲目或删除原文件夹。
- 已下载至 App 目录的曲目保留下载副本；同一本地文件已关联其他曲目时跳过并说明，避免覆盖关联或违反唯一索引。

## 6. 在线来源与导入

### 6.1 在线搜索与 B 站

- 三个搜索来源提交新查询（包括相同条件）、改变筛选或排序时重置分页与列表位置，作品搜索同时展开折叠栏；追加分页、切来源和详情返回保留位置，旧响应不得覆盖新查询。
- 在线搜索来源顺序为「作品 / B 站 / YouTube」，分别保留输入、结果及滚动位置。音频库「导入在线音频」打开在线搜索根页并恢复搜索状态。在线加入音频库统一放入库根目录。
- B 站合集／系列封面统一使用首视频封面，忽略 `meta.cover` 和 `ugc_season.cover`；首视频无封面时显示默认图标，旧收藏也须遵循这一规则。
- B 站提供视频和 UP 主模式。纯数字在视频模式中为关键词，在 UP 主模式中为 UID；明确的 BV 或主页链接优先识别。
- 分页失败保留已加载内容和页码，中止后不自动续读；取消旧请求并阻止旧响应覆盖新查询。访问受限应显示错误，不作为空结果处理。
- 点视频只播放所选 P，不自动加入音频库。所有 B 站导入入口共用多 P 方式选择：分开导入按勾选保存，统一导入保存全部 P 为一个带「多 P」标签的视频项。同一时间只处理一个导入选择，已有选择未完成时其他入口立即提示，不持锁等待用户；确认或取消后可继续新的选择。分 P 页面按 page 排序并支持多选，禁止部分移除；整组移除允许。
- 未加入音频库的 B 站队列以 `workno=bvid`、`fileKey=cid` 保存身份，使用稳定观看页链接恢复；播放时解析 DASH 音轨，不将观看页当作媒体。
- B 站按已入库 BV/CID 下载。分组使用独立的 `folders.biliBvid`，组织方式只由用户确认导入转换修改。17→18 清空过时的自动入库批次及下载元数据，保留曲目、下载记录和已完成音轨。
- B 站下载仅保存音轨，视频字幕写入 `filesDir/bilibili/{bvid}/{cid}.json`。播放优先使用本地字幕，删除音轨时同时删除该缓存字幕。
- B 站字幕需要有效登录。`code = 0` 且字幕为空、`login_mid = 0` 时应提示重新登录，并保留生成字幕入口；列表或正文因限流为空时，刷新播放信息后最多重试一次。
- 从下载管理播放未加入音频库的 B 站音轨时，以队列的 `workno` 和 `fileKey` 获取身份，不依赖 `trackId`。

### 6.2 登录与授权

- B 站扫码登录将 Cookie 和 refresh token 存入加密偏好，约每 20 小时检查更新需求；刷新完成后确认旧 token 作废。续期失败或读取更新信息失败时记 10 分钟冷却，冷却内不再请求，「我的」页提示重新登录；续期成功、保存新凭证或退出登录都会清除失败标记。
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
- 中止时仅清理本次 `.part`，不得删除重试任务正在使用的暂存。下载完成后在事务中关联已入库曲目，同视频复用原曲目。
- 队列、播放列表、下载管理和生成字幕均须保留视频身份；删除本地副本后仍可恢复在线播放。

### 6.4 Japanese ASMR

- 面向用户的界面统一写「篇目」（指站内文章）和「整部作品」；本文与代码中的「文章」「整作」「post」指同一概念。
- 作品搜索只请求 DLsite 和 asmr.one。在线详情打开后按完整 RJ 查询 Japanese ASMR，优先使用库内已知篇目。仅 Japanese ASMR 收录且未加入音频库的作品可通过明确篇目链接或剪贴板打开。
- 播放器音轨以 `postId + mediaKey` 标识。刷新先对整篇做精确键匹配，再按唯一路径摘要匹配，沿用曲目编号、本地路径、字幕关系及稳定字幕身份。显式刷新仅在 RJ 相符、媒体列表完整时清除确认消失的曲目，保护有效本地副本和当前播放项。受影响的队列及播放列表条目随曲目清理，保留 `filesDir/subtitles/` 下的生成字幕、译文及任务记录，并回收该曲目独占的手动字幕副本（`APP_FILE`）和已无引用的外部 SAF 读权限。
- 公开页面请求串行，列表请求之间至少间隔 10 秒。验证由用户在可见网页内完成，网页会话只在网页内使用。播放、刷新、普通同步和整作下载按各自操作恢复。媒体服务器验证单独提示。
- 单曲、多选曲目及听写前置下载使用播放器直链或 HLS。HLS 支持完整、未加密的连续 AAC/TS 分片并封装为 M4A。章节使用页面中的单媒体时间点。
- 整作下载读取站内 Download 入口，展示文件清单后由用户确认入队。任务保存所选文章编号及入口摘要，登录凭证、MEGA 密钥和临时地址只驻内存。排队、运行及等待验证共用同一作品的任务占用，普通失败和中止可重试或删除。
- Buzzheavier 的 Download File 可能返回 `204 + HX-Redirect`；必须在内建网页里用普通请求（不带 `HX-Request`）跟随到 `audio/mp4` 等文件响应，不能按 `/download` 路径把已确认的文件地址丢掉。原生首次使用普通 GET，确认可读后再用 `Range` 续传。仅对 Buzzheavier 主机在内存中复用 WebView 的 Cookie 和 User-Agent，JA 站点的 Cookie 不进入原生请求。验证失败重新打开当前失败的 Buzzheavier 文件页，不退回 JA 入口；清单读取最多重试 3 轮。进入托管页后自动依次读取待处理文件，浏览器保持打开；只在真正出现验证时要求用户操作，不要求逐页点击。
- 整作文件使用 `(rj, relativePath)` 身份，统一显示在作品根目录的「下载文件」。存储位于 `filesDir/japaneseasmr-downloads/`。重下先读取远端清单，按大小及可用校验值识别变化和缺失。发布失败保留旧文件和清单，删除单曲本地副本保留原曲目及清单，整作下载可补回。整作文件使用独立的稳定字幕身份。

### 6.5 剪贴板导入

- `MainActivity` 每次进入前台，仅在 RESUMED 且窗口有焦点后读取一次剪贴板。Activity 级 `ClipboardImportViewModel` 保留旋转期间的预览和导入状态。
- 识别 BV、RJ、YouTube 单视频和 Japanese ASMR 文章链接或分享文字。明确视频链接优先于文章链接及标题中的编号，带 RJ 前缀的编号优先于独立 BV 号及纯数字。无明确候选时，文本中的连续六位数字或以 `01` 开头的连续八位数字可作为 RJ 候选。只解析文本和 HTTP(S) URI，不打开 `content:` URI，不执行剪贴板 Intent，不记录原文，不清空用户剪贴板。
- RJ 号是字符串标识，解析、预览、请求及加入音频库均须保留前导零，例如 `RJ01234567` 不得变成 `RJ1234567`；禁止通过整数转换或去零后重建编号。剪贴板仅对不足六位的数字简写补零，不截短更长编号，全零编号无效。接口返回的整数 `id` 与字符串作品编号分别使用，不得混用。
- 纯数字候选先显示底部「查找作品」提示，用户点击后才开始预览查询。提示不显示复制的数字，关闭或超时后记为已处理。明确编号和视频链接直接查询预览。
- 剪贴板解析与预览查询在后台进行，查到内容后才显示弹窗，确认后导入根目录，不自动播放或下载。RJ 先查音频库，再查 asmr.one，确认未收录后查 DLsite 商品及已购记录。DLsite 已购作品确认后读取 Play 文件树，未购作品只提供「查看详情页」。主动点「查找作品」后确认不存在时显示底部提示，自动查询无结果时静默。作品查询连接失败时显示对应的网络提示。B 站多 P 进入共用导入选择，分开导入默认全选。YouTube 预览使用 `videoInfo` 的官方 oEmbed，不解析媒体或执行脚本。Japanese ASMR 文章预览核对 RJ、封面与播放器元素，确认后按作品追加文章。
- 取消和新候选通过请求代号隔离旧响应，导入中禁止重复确认或替换条目。
- `clipboard_import` 偏好仅保存候选类型、身份与复制时间的 SHA-256 摘要。API 26 以下按类型和身份去重，同一份已处理内容不重复提示。

## 7. 听写、翻译与字幕

### 7.1 任务生命周期

- 听写结果保存至 `filesDir/subtitles/{sha256}.json`。文件内容、服务地址和模型相同，且生成文件仍存在时复用结果；地址或模型改变时重新生成。新文件以其中记录的生成模型和地址为准，翻译回写时保留。旧文件按最近已完成记录判断，无有效模型信息时不按地址直接复用。
- 「生成字幕」设置 `skipTranslate = true`；「生成字幕和翻译」才在听写后翻译。重试保留原设置。
- 本机和在线听写、翻译及润色共用 `SubtitleTaskService`（`dataSync`）。在用户发起任务或应用重新进入前台后启动，覆盖等待音频下载、识别、自动翻译和润色。全部任务结束后释放唤醒锁并停止服务。系统超时时停止服务、取消本地请求并登记任务失败。应用进程终止后，重新进入应用会接手仍在排队或听写中的任务；中断的翻译登记为失败，保留已保存字幕。
- 离开播放页仅取消界面等待。听写后的自动润色在提交译文前完成，润色失败保留未润色译文。自动翻译、独立翻译和润色以持久化发布记录协调译文文件和任务状态，启动时先补完已提交的发布。
- 中止仅取消本地任务。Qwen3-ASR 无服务端取消接口；本机模型在片段间响应取消，JNI 解码与 release 不得并发。听写等待下载时，取消听写不连带取消音频下载。
- 听写前置下载绑定指定任务。Japanese ASMR 按文章与音轨键精确匹配，只读取下载完成后关联的直接音轨或 HLS 成品。选择 DLsite 完整原始包时等待解压和目标文件关联完成；目标缺失时明确失败，不被其它压缩音轨下载提前唤醒。旧任务空依赖字段保留原兼容逻辑。
- `filesDir/whisper-src/` 暂存文件保持无后缀，上传时根据文件头设置格式。任务结束、取消或重试后仅清理无任务引用的暂存；重试优先复用原文件，其次从曲目本地文件复制。本机模型直接读取本地文件，不使用上传暂存。

- 独立翻译及手动润色在读取源字幕前登记任务，支持中止和失败重试。复用现有听写只翻译时也建立独立记录。重试或提交前核对原字幕身份，避免覆盖已变化的字幕；非生成字幕的保存在写锁外核对且只比较源内容指纹，读不到源时放行，译文覆盖按内嵌源指纹精确套用。生成字幕保留本地严格核对。

### 7.2 模型与网络

- 听写模型为 `qwen-audio-3.1-asr`、`Qwen3-ASR`、`SenseVoiceSmall` 和 Parakeet 0.6B 日语（CTC）。地址及密钥按模型独立保存，运行中的任务使用其自身配置；地址为空时仍保存模型选择。
- `qwen-audio-3.1-asr` 固定使用阿里云接口和 `qwen-audio-3.1-asr-flash-filetrans` 模型，经临时上传后轮询结果，不上传超过 1 GB 的文件。保存远端任务编号和提交时间，进程重启后接着查原任务。30 分钟轮询到期时取消当前查询并按失败结束。恢复时已过期的任务最多查询一次。手动重试可以重新提交，用户中止显示「已中止」。
- Qwen3-ASR 要求非空地址，先读取模型名再请求 `/v1/audio/transcriptions`，语言为日语；保留服务返回的分段时间，只有整段文字时覆盖整首时长。保存地址不自动补 `/v1`。
- 本机模型使用 sherpa-onnx，在 arm64 设备离线运行，`endpoint` 为 `null`，按各自模型身份去重。下载和重试当次选择 GitHub 或 Hugging Face，模型就绪、取消和删除按各自安装目录处理。
- 模型下载由用户确认后启动独立的 `dataSync` 前台服务，覆盖下载、校验和安装。通知可取消对应下载，全部任务结束后释放唤醒锁并停止服务。进程终止后由用户重试，清理旧暂存。
- SenseVoiceSmall 固定使用 `int8-2024-07-17` 多语种包，保留 `filesDir/sense-voice/` 和 `version.txt`。Parakeet 固定使用 NVIDIA 日语模型的 `35000-int8` CTC 导出包及独立安装目录。两个模型对 16 kHz 音频使用 Silero VAD，送入识别器的单声道片段不超过 28 秒，拼接时保留时间偏移。Parakeet 双声道分别检测人声，再按片段选择混合或使用单侧。来源、占用和 CC BY 4.0 署名见 `Android/docs/whisper-api.md`。
- 已移除模型的偏好迁移至 `qwen-audio-3.1-asr`，不生成虚假密钥；旧模型的待执行任务明确失败。
- Android 17 下，Qwen3-ASR 访问局域网、本机或 `.local` 地址前申请 `ACCESS_LOCAL_NETWORK`。使用 `isLocalNetworkAddress` 判断，仅解析字面 IP，不额外发起 DNS 查询。
- 翻译平台限定千问 AI 平台和 DeepSeek，地址固定，密钥与模型名按平台独立保存。平台地址、默认模型及思考参数集中在 `data/TranslatePlatforms.kt`，修改时保留各平台的字段差异；思考内容 `reasoning_content` 不写入字幕。
- 日文校对合并的条数、跨度、字数为软限制，按听写模型区分（`qwen-audio-3.1-asr` 为 2 条／18 秒，其余 4 条／12 秒，双语各 80 字）；自身超限的条目标记 `lock` 禁止合并。结构合法（JSON 可解析、编号连续全覆盖、双语非空）是硬要求；软违规默认直接交付，「自动修正合并格式」开关（默认关）开启后定向修正一次，每批最多 2 次请求。润色不按合并字数硬拒绝。

### 7.3 字幕交互

- 字幕加载优先按已入库曲目的 `track_subtitles` 手动绑定读取（支持作品文件、外部持久授权 URI、应用内导入副本、强制生成字幕或明确关闭），无绑定时自动匹配生成字幕、作品跨目录同源字幕及受「作品跨来源借用字幕」（`crossSourceFallback`，默认开启）控制的其他来源字幕，并支持毫秒级时间轴微调（`offsetMs`）与强制重新生成（`forceRegenerate`）。未入库曲目不支持手动关联。
- 字幕按钮仅在字幕就绪、无当前句子且结果不是「没有听出人声」时显示；句间空隙不触发按钮显示。
- 字幕全文列表点击句子后跳转至句子起始时间，保留列表页面；播放页当前句用于打开全文列表；顶栏菜单支持切换/选择字幕、重新生成、解除手动关联及仅删除生成字幕。

## 8. 关于与更新检查

- 「设置 → 关于」通过 PackageManager 读取实际安装包的版本名和构建号，不使用界面常量代替。
- `AppUpdateClient` 使用独立匿名请求读取固定仓库的 `/releases?per_page=100`，只接受 `tag_name` 以 `android-` 开头、非草稿/预发布且包含已上传 APK 的发布；桌面端发布不参与 Android 更新检查。版本按去掉 `android-` 前缀后的 tag 比较，无法解析时不推断版本关系，本地版本较高时不提示降级。
- Android 发布标签统一为 `android-X.Y`（例如 `android-1.3`），桌面端使用自己的标签；历史 `v1.0`～`v1.2` 不带 `android-` 前缀，不会被新版检查识别。发布新 APK 时同步递增 `versionCode` 和 `versionName`。
- 检查由用户手动触发；应用不自动检查、下载或安装更新。下载入口通过外部浏览器打开固定仓库的发布列表或 tag 页面，不使用附件下载地址、WebView 或下载管理服务。
- `browser.invalid` 仅用于 PackageManager 查询浏览器，不发起网络请求。接口及发布要求见 `Android/docs/update-api.md`。
