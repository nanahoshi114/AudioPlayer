# Android 界面文案审查

审查日期：2026-11-07

> 实施记录
>
> - 「设置 → 下载管理」已新增「音频 / 字幕」分段，生成字幕可在「字幕」中单独删除。
> - 音频库和作品页按选中的下载副本和本地关联显示操作。混选确认分别列出删除副本数和解除关联的曲目数。
> - 用户主动保存称为「下载」，应用自动保存的临时内容称为「缓存」。设置页保留「清理缓存 / 缓存上限」。
>
> 下文保留修改前的界面描述、旧行号和审查建议。

审查范围包括 Android 界面中用户可见的操作、空状态、帮助、标签和错误提示，以及数据和播放模块会直接展示给用户的异常文案。

修改前，同一操作的名称不统一，「缓存」兼指临时文件和用户主动下载的音轨，部分操作提示与实际入口或行为不一致。

---

## 0. 最值得先改的 10 处

### 1. 「设置 → 下载管理 → 字幕」不存在

位置：

- `app/src/main/java/com/nanahoshi/audioplayer/ui/components/DeleteAudioDialog.kt:24`
- `Android/README.md:81`、`Android/README.md:283`
- `AGENTS.md:79`

当前：

> 生成的字幕文件也可在「设置 → 下载管理 → 字幕」中单独删除。

问题：`StoredFilesScreen`（下载管理）只有一个混合文件列表，没有「字幕」分段或分组（`ui/download/StoredFilesScreen.kt:191-233`）。带「字幕」分段的是下载任务页（`ui/download/DownloadScreen.kt:149-154`），那里只有听写/翻译任务，不列已落盘字幕文件。用户按提示找不到入口。

建议：改成「也可在『设置 → 下载管理』里找到对应的字幕文件单独删除」；如果产品希望保留这个入口，需要在下载管理增加「音频 / 字幕」筛选。README 和 AGENTS 同步修改。

### 2. 多选「删除已下载的文件」会把「取消识别」也包含进去

位置：

- `ui/library/LibraryScreen.kt:183`
- `ui/library/LibraryWorkScreen.kt:136`
- 单条操作已经区分：`ui/library/LibraryRows.kt:504-507`
- 实际行为：`data/DownloadCenter.kt:306-313`

问题：单条菜单在本地文件是用户自己识别进来时会显示「取消识别」，但多选栏永远写「删除已下载的文件」。批量删除时，识别来的本地文件只会解除关联，不会删除磁盘文件，文案却让人以为要删手机上的文件。

建议：多选操作按选中内容动态显示：包含 App 下载副本时用「删除已下载文件」，只有识别关联时用「取消本地关联」；确认弹窗逐项说明「会删除 App 内副本 / 只解除关联」。

### 3. “缓存”同时指临时缓存和长期音频文件

位置：

- `ui/settings/CacheScreen.kt:51`「清理缓存」
- `ui/settings/CacheScreen.kt:57,78`「缓存上限」
- `ui/settings/CacheScreen.kt:70`「只清理封面图片等临时缓存。下载的音频、B 站缓存音轨和字幕请到「下载管理」处理。」
- `ui/player/PlayerChrome.kt:465`「缓存音轨」
- `ui/library/LibraryWorkScreen.kt:184`「缓存到本地」
- `ui/library/LibraryRows.kt:472,500,505`「已缓存」「缓存音轨」「删除已缓存的音轨」

问题：设置里的“缓存”会自动清理，下载/播放页的“缓存音轨”却会长期占用存储，必须手动删除。同一个词让用户无法判断「清理缓存」到底会不会删掉已缓存的 B 站音频。

建议：临时缓存改叫「图片缓存」或「临时文件」；B 站动作如果确实要保留“只存文件、不入库”的语义，改为「仅缓存音轨（不入库）」，并在按钮下方说明「会占用存储，需到下载管理删除」。否则统一叫「下载音轨」。

### 4. 保存 / 导入 / 入库 / 加入音频库 混用

位置（同一类结果的不同叫法）：

- 作品详情 FAB：`ui/asmr/OnlineScreens.kt:1785`「保存」
- 作品详情成功提示：`ui/asmr/OnlineScreens.kt:1027,1034`「已保存」
- 多选栏：`ui/bilibili/BiliBrowser.kt:118`、`ui/bilibili/BiliCollectionScreen.kt:99`「保存」
- 行菜单：`ui/bilibili/BiliChoices.kt:6`、`ui/bilibili/BiliCollectionScreen.kt:115`「保存到音频库」
- YouTube：`ui/youtube/YouTubeScreens.kt:185`「保存到音频库」
- 剪贴板导入成功：`ui/importing/ClipboardImportViewModel.kt:66,71`「已导入音频库」
- 本地导入成功：`ui/library/LibraryViewModel.kt:383,410,415`「已导入作品」「已导入」「已导入 N 首」

问题：用户无法建立稳定预期：保存是存文件还是只登记条目？“导入”和“保存”是不是两件事？

建议：统一以「加入音频库」表示“只登记条目/作品，不下载文件”；确实会下载文件的动作用「下载并加入音频库」；状态统一「已在音频库」。详见第 6 节词表。

### 5. 顶栏「下载」页里还有听写和翻译任务

位置：`ui/download/DownloadScreen.kt:148-158,178-193`；顶栏下载入口 `MainActivity.kt:314`。

问题：页面叫「下载」，分段却是「音频 / 字幕」，字幕页里是听写、翻译任务；右上角角标也把听写和翻译算进“下载”数量。用户点“下载”看到翻译任务会困惑。

建议：页面标题改为「下载与任务」或「任务」；下载图标的内容描述同步改。若不想改标题，至少把字幕分段命名为「听写与翻译」。

### 6. B 站手动粘贴登录后直接显示「已登录」

位置：`ui/asmr/OnlineScreens.kt:2243-2251,2305-2307`；保存逻辑 `ui/asmr/OnlineScreens.kt:2048-2052`。

问题：`biliReady = hasCookie()`，手动粘贴 SESSDATA/Cookie 只代表“已保存”，并未校验。B 站卡片徽章却显示「已登录」。YouTube 已经区分了「已保存」和「已登录」，B 站没有。

建议：手动登录徽章显示「已保存」，扫码或校验成功后才显示「已登录」；或统一显示「已配置登录信息」。

### 7. 关于页直接显示原始发布标签 `android-1.3`

位置：`ui/settings/AboutScreen.kt:96-101`。

当前：

> 发现新版本 1.3
> 最新发布：android-1.3

问题：上一行用版本名，下一行又出现带前缀的原始 tag；前缀是发布约定，对用户没有意义。

建议：显示「最新发布：1.3」；需要排障时再附「标签：android-1.3」，或只放在次要说明里。

### 8. 在线作品排序的方向靠猜

位置：`ui/asmr/OnlineScreens.kt:179-190`。

当前：`发售日`、`发售日-正序`、`价格`、`价格-正序`、`RJ号`、`RJ号-正序`、`评分`、`评价数`、`销量`。

问题：不带后缀的项没有说明是降序还是升序；“正序”对价格/RJ 号意味着从小到大，但用户需要猜。“最新收录”也没说明是按什么时间。

建议：直接写方向，例如：

- 发售日（新→旧）/ 发售日（旧→新）
- 价格（高→低）/ 价格（低→高）
- RJ 号（大→小）/ RJ 号（小→大）
- 评分（高→低）、销量（高→低）
- 最新上架（而不是“最新收录”）

### 9. 「全选」到底选多少不清楚

位置：

- `ui/bilibili/BiliBrowser.kt:108-110`：只选已加载的结果，后续分页不在内
- `ui/youtube/YouTubeScreens.kt:146-148`：选全部已加载视频（最多 40 个）
- `ui/bilibili/BiliCollectionScreen.kt:93`：「全选已显示」/「全选 / 取消」
- `ui/bilibili/BiliImportDialog.kt:50`、`ui/bilibili/BiliBrowser.kt:186`：「全选 / 取消全选」一个按钮同时表示两个动作

问题：两处「全选」都只覆盖已加载结果，但文案没有说明，用户可能以为会选中全部远端结果；同一个按钮的标签不随当前状态变化，用户点之前不知道会全选还是取消。

建议：统一为「全选已加载（N）」和「取消全选」；只有一个切换按钮时，按当前状态动态显示，不要写「全选 / 取消全选」。

### 10. 音频库空状态声称可以从导入菜单直接导入 BV/RJ

位置：`ui/library/LibraryScreen.kt:261-263`；实际导入菜单 `ui/library/LibraryScreen.kt:368-386`。

当前：

> 点右下角导入本地文件、文件夹、BV 号或 RJ 号。

问题：导入菜单只有「选择文件导入」「选择文件夹导入」「导入在线音频」；BV 号/RJ 号是通过剪贴板弹窗或在线搜索进入的，不是右下角直接导入。

建议：

> 点右下角导入本地文件或文件夹；也可以复制 BV 号、RJ 号或视频链接，用剪贴板导入。

---

## 1. 会误导或导致误操作的其他文案

### 1.1 作品详情的「保存」用了下载图标

位置：`ui/asmr/OnlineScreens.kt:1781-1786`。

当前：FAB 文本「保存」，图标是 `Icons.Default.Download`。

问题：这个动作实际是把作品加入音频库，不下载文件；下载图标会让人以为会下载整部作品。单首下载另有下载按钮，更强化误解。

建议：文本改「加入音频库」，图标换书架/收藏类图标；或者明确写「保存作品信息」。

### 1.2 已保存内容在剪贴板弹窗里变成禁用按钮

位置：`ui/importing/ClipboardImportDialog.kt:50-54`；状态判断 `data/ClipboardImportController.kt:17-18`。

问题：ASMR/YouTube 已在音频库时，确认按钮变成禁用状态的「已在音频库」。状态被做成按钮，用户会尝试点击。

建议：状态行显示「已在音频库」，弹窗只保留「关闭」；B 站多 P 保留「选择入库方式」。

### 1.3 「取消收藏」弹窗的取消键写成「返回」

位置：`ui/bilibili/BiliCollectionScreen.kt:142-145`。

问题：「取消收藏」是确认动作，右侧却写「返回」，容易被理解成返回上一页，而不是“保留收藏”。

建议：dismiss 改为「保留收藏」。

### 1.4 创建播放列表的确认键是「选择入库方式」

位置：`ui/bilibili/BiliCollectionScreen.kt:137-140`。

当前：标题「复制为自建播放列表」，输入名称，确认键「选择入库方式」。

问题：用户正在创建播放列表，按钮突然问入库方式，下一步不明确。

建议：确认键「下一步：选择内容」；下一步弹窗再叫「选择入库方式」。

### 1.5 删除音频时的三个选项容易混淆

位置：`ui/components/DeleteAudioDialog.kt:23-38`。

当前：正文问「要一起删除这些字幕吗？」，按钮「同时删除字幕」「保留字幕」「取消」。

问题：「保留字幕」会删除音频，「取消」什么也不删，二者只差两个字，风险很高；弹窗没有标题。

建议：标题「音频带有生成字幕」；按钮改为「删除音频和字幕」「只删除音频」「取消」；正文只保留一句「重新下载同一音频时会自动匹配字幕。」

### 1.6 “已发布”是内部流程词

位置：`ui/download/DownloadScreen.kt:255`；`data/DownloadCenter.kt:187`。

当前：

> 已发布的音频不受影响。

> 已发布原始文件；N 个文件已关联其他曲目，保持不变

问题：用户不知道“发布”指“解压并入库”。这是开发流程语言。

建议：改成「已解压并加入作品的音频不受影响」「原始文件已解压并加入作品」。

### 1.7 下载任务状态里的技术词

位置：`ui/download/DownloadScreen.kt:212-214,301,343`。

当前：`获取下载信息`、`关联文件`、`查看返回`。

问题：「关联文件」实际是“整理文件并入作品”，「查看返回」实际是“查看错误详情”，都是实现视角。

建议：

- 获取下载信息 → 解析下载地址
- 关联文件 → 整理入库
- 查看返回 → 查看错误详情

### 1.8 YouTube 在播放页被叫作“缓存”

位置：`ui/player/PlayerScreens.kt:279,281,306`。

当前：

> 已加入缓存 / 已经在本地或正在缓存 / YouTube 缓存失败

问题：YouTube 在其他所有入口都叫「下载」，只有播放页叫“缓存”；且“YouTube 缓存失败”与「下载」术语冲突。

建议：统一为「已加入下载」「已在本地或正在下载」「YouTube 下载失败」。如果只是保存到本机、不入库，写「仅下载音轨」。

### 1.9 “这台设备”指代错误

位置：`ui/player/PlayerScreens.kt:201`；`ui/download/DownloadScreen.kt:92`。

当前：

> 需要允许访问本地网络，才能连接这台设备

问题：用户连接的是自己填写的听写服务地址，不一定是“这台设备”；可能被理解成连接手机本身。

建议：

> 需要允许访问本地网络，才能连接你填写的听写服务

### 1.10 生成字幕前的下载提示太泛

位置：`ui/player/PlayerChrome.kt:844`。

当前：

> 需要下载文件，是否下载

问题：没有说下载哪个文件、为什么要下载。

建议：

> 生成字幕需要先下载这首歌的音频，是否下载？

### 1.11 “这首无法下载，不能生成字幕”没有解释原因

位置：`ui/player/PlayerScreens.kt:358,435`。

问题：“无法下载”会被理解为网络问题，实际可能是这首歌没有可用的音频文件或来源不支持。

建议：

> 这首歌没有可用的音频文件，无法生成字幕

### 1.12 「原始文件尚未缓存」

位置：`ui/library/FilePreviewScreen.kt:144`。

问题：这里的“原始文件”和“缓存”都是内部词；用户只关心文件有没有下载。

建议：

> 这个文件还没有下载到本机

### 1.13 文件准备过程中写「已暂存」

位置：`ui/library/FilePreviewScreen.kt:275`。

问题：进度条还在走，文案却说“已暂存”，时态不对；用户不知道是在下载还是在交接。

建议：

> 正在准备文件，已下载 X

### 1.14 图库在移动数据下只显示一个「预览」按钮

位置：`ui/asmr/OnlineScreens.kt:1905-1906`。

问题：使用移动数据时图库被隐藏，只留「预览」。用户不知道原因，也不知道点它会消耗流量。

建议：

> 使用移动数据，点此加载图片

或按钮「加载图片（使用移动数据）」。

### 1.15 「音频信息」弹窗只有标题和作者

位置：`ui/player/PlayerChrome.kt:262`。

问题：入口是点击标题查看完整标题，弹窗却叫「音频信息」，容易期待更多信息（来源、时长、文件路径等）。

建议：改叫「标题与作者」，或真的补充来源、时长等信息。

### 1.16 定时结束提示没有说明发生了什么

位置：`MainActivity.kt:269`。

当前：

> 倒计时已结束

问题：用户只看到播放停止，不知道是定时关闭触发的。

建议：

> 定时关闭时间已到，播放已暂停

### 1.17 「没有听出人声」

位置：`ui/player/PlayerChrome.kt:383`。

问题：语气偏口语，且“听出”可能被理解成“没听清”。听写场景下「未检测到人声」更准确。

建议：视产品语气决定；若保持亲和可以保留，但建议在帮助里解释“没有检测到可识别的人声”。

### 1.18 下载管理的搜索范围写窄了

位置：`ui/download/StoredFilesScreen.kt:228`。

当前：占位符「文件名、RJ 号或作品名」。

实际匹配还包含 BVID、工作编号、`生成字幕`/`字幕` 标签和路径文件名（`data/StoredFiles.kt:548-560`）。

建议：

> 文件名、编号或作品名

### 1.19 「导出」实际是系统分享

位置：`ui/download/StoredFilesScreen.kt:202,328`。

问题：`导出` 打开的是 `ACTION_SEND` 分享面板，不是选择保存位置。用户可能找不到“导出到文件夹”。

建议：如果保留分享行为，改叫「分享」；如果产品需要真正的导出，另做文件保存流程。

### 1.20 下载管理里「字幕」与「生成字幕」两个标签含义不清

位置：`ui/download/StoredFilesScreen.kt:269-270`。

问题：用户不知道「字幕」指作品目录里的 lrc/srt/vtt，「生成字幕」指 App 生成的 json；两者都叫字幕。

建议：

- `生成字幕` → `App 生成字幕`
- `字幕` → `字幕文件`

或在行内显示扩展名。

---

## 2. 术语与动作不一致（建议做一次全局统一）

### 2.1 保存 / 导入 / 入库 / 加入音频库

现状与位置：

| 叫法 | 位置 |
| --- | --- |
| 保存 | `ui/asmr/OnlineScreens.kt:1785`、`ui/bilibili/BiliBrowser.kt:118`、`ui/bilibili/BiliCollectionScreen.kt:99` |
| 保存到音频库 | `ui/bilibili/BiliChoices.kt:6`、`ui/youtube/YouTubeScreens.kt:185`、`ui/bilibili/BiliCollectionScreen.kt:115,178` |
| 已导入音频库 | `ui/importing/ClipboardImportViewModel.kt:66,71` |
| 已保存 | `ui/asmr/OnlineScreens.kt:1027,1034` |
| 已导入作品 / 已导入 / 已导入 N 首 | `ui/library/LibraryViewModel.kt:383,410,415` |
| 入库 | `ui/bilibili/BiliImportDialog.kt:22,32`、`data/BiliLibraryStore.kt:26` |

建议：统一用「加入音频库」；成功提示「已加入音频库」；菜单「加入音频库」；涉及下载时「下载并加入音频库」。

### 2.2 下载 / 缓存 / 已下载 / 已缓存

现状与位置：

| 叫法 | 位置 |
| --- | --- |
| 下载 | 大多数音频入口 |
| 缓存音轨 | `ui/player/PlayerChrome.kt:465`、`ui/library/FavoriteScreen.kt:263`、`ui/library/LibraryRows.kt:500` |
| 缓存到本地 | `ui/library/LibraryWorkScreen.kt:184`、`ui/library/LibraryRows.kt:276,425` |
| 已缓存 | `ui/library/LibraryRows.kt:472` |
| 删除已缓存的音轨 | `ui/library/LibraryRows.kt:505` |
| 已加入缓存 / 正在缓存 | `ui/library/FavoriteScreen.kt:183-185`、`ui/player/PlayerScreens.kt:279,306` |

建议：

- 只存文件、不入库：`仅缓存音轨（不入库）`，并说明会占存储、需手动删除。
- 存文件并入库：`下载并加入音频库`，或保留「下载」但帮助中说明会自动入库。
- 状态统一：`已下载`；如果必须区分 B 站，用来源徽章区分，不要再用“已缓存”。
- 删除统一：`删除已下载文件`。

### 2.3 队列动作：下一个播放 / 加到末尾 / 加入当前列表末尾 / 追加

位置：

- `ui/library/LibraryScreen.kt:174-175`：「下一个播放」「加到末尾」
- `ui/library/LibraryRows.kt:497-498`：「下一个播放」「加入当前列表末尾」
- `ui/library/LibraryWorkScreen.kt:130-131`：「下一个播放」「加到末尾」
- `ui/bilibili/BiliVideoScreen.kt:111-112`：「下一个播放」「加到末尾」
- `ui/bilibili/BiliCollectionScreen.kt:98,153`：「追加」「加到末尾」
- `ui/playlist/PlaylistScreens.kt:154`：「加到末尾」

建议：

- 插入下一首：`接下来播放`
- 追加到当前队列：`加入播放队列`
- 替换当前队列并播放：`播放`
- 加入自建列表：`加入播放列表`

「加到末尾」必须说明末尾是什么；在合集页可写「将全部加入播放队列」。

### 2.4 识别 / 关联

位置：`ui/library/LibraryScreen.kt:413`、`ui/library/LibraryRows.kt:279,506`、`ui/library/LibraryWorkScreen.kt:187`。

当前：`识别为作品`、`识别本地文件夹`、`取消识别`。

问题：这些动作是用户手动把本地文件夹挂到作品上，不是自动识别；「取消识别」听起来像撤销一个识别结果。

建议：

- `关联为作品`
- `关联本地文件夹`
- `取消本地关联`

### 2.5 收藏 / 收藏夹 / 播放列表

位置：

- `ui/asmr/OnlineScreens.kt:2260`：「查看收藏夹」= B 站收藏夹
- `ui/bilibili/BiliCollectionScreen.kt:77,142,178-182`：「收藏到播放列表」「取消收藏」「复制为自建播放列表」
- `ui/playlist/PlaylistScreens.kt:68`：「加入播放列表」

问题：三个不同的“收藏”：

1. B 站账号里的收藏夹；
2. 把 B 站合集/系列收藏到本地播放列表，随远端更新；
3. 复制成一个不随远端更新的自建播放列表。

建议：

- `查看 B 站收藏夹`
- `收藏在线列表`（并说明会随 B 站更新）
- `取消收藏（仅移除本地收藏）`
- `复制为普通播放列表`（并说明不会随 B 站更新）

### 2.6 原始包 / 完整包 / 完整原始压缩包

位置：

- `ui/components/DlsiteDownloadDialog.kt:29-31,43`：「完整原始压缩包」「完整包」
- `ui/download/DownloadScreen.kt:255`：「压缩包」
- `ui/asmr/OnlineScreens.kt:930,959`：「完整包下载」
- `AGENTS.md` 与 `README.md` 中还有「原始包」「原始压缩包」

建议：统一为「完整原始包」；按钮「下载完整原始包」；状态「完整原始包下载中」。如果太长，至少保持「完整包」与「原始包」二选一，不混用。

### 2.7 口令 / API Key

位置：`ui/settings/ModelSettingsScreen.kt:119,151,516,546`。

问题：同一个凭据，在 Qwen 云模型叫「API Key」，在自部署模型和翻译里叫「口令」；帮助弹窗也两种叫法并用。

建议：统一为「API Key（密钥）」或「访问令牌」；如果面向非技术用户，可在括号里写“服务商提供的密钥”。

---

## 3. 空状态、错误提示与语气

### 3.1 “没有读到”被用来表示“没有结果”

位置：

- `ui/bilibili/BiliBrowser.kt:130`：「没有读到视频」
- `ui/bilibili/CollectionCatalogue.kt:28`：「没有读到合集或系列」
- `ui/library/FavoriteScreen.kt:150`：「没有读到可导入的视频」
- `ui/library/LibraryViewModel.kt:261,269,295,304`：「没有读到这个作品的文件」
- `ui/asmr/OnlineScreens.kt:668`：「没有读到作品。」

问题：搜索成功但结果为空时，“没有读到”会让人以为读取失败；真正的读取失败另有错误提示，两者混在一起。

建议：结果为空统一用「没有找到…」「没有…」；只有异常才用「读取失败」。例如：

- 没有找到视频
- 没有合集或系列
- 没有可导入的视频
- 没有找到这部作品的文件

### 3.2 在线搜索错误会重复来源名

位置：`ui/asmr/OnlineScreens.kt:410-411`；来源异常文案 `asmr/AsmrClient.kt:289,303`、`dlsite/DlsiteClient.kt:409,550,556`。

问题：UI 会拼成：

> asmr.one：暂时无法连接 asmr.one

> DLsite：暂时无法连接 DLsite

建议：如果异常消息已经包含来源名，UI 不再加前缀；或者异常消息保持中性（“暂时无法连接”），由 UI 统一加来源。

### 3.3 直接展示 HTTP 状态码和内部字段

位置示例：

- `bilibili/BilibiliClient.kt:224,242`：「无法读取收藏夹（${response.code}）」
- `bilibili/BilibiliClient.kt:468`：「B站返回错误 $code」
- `youtube/YouTubeClient.kt:256,295,301,304,345`：「不是 MP4 音轨」「音频分段信息无效」等
- `dlsite/DlsiteArchiveExtractor.kt:34,130,134,139`：「解压暂存目录不是空目录」「包含不安全的路径」等
- `data/DownloadCenter.kt:187`：「已发布原始文件」

建议：第一层只给用户能行动的信息，例如：

- 无法读取收藏夹，请重新登录后再试
- B 站暂时拒绝了请求，请稍后重试
- YouTube 返回了无法识别的音轨，请重试或换一个视频
- 压缩包无法解压，文件可能损坏

把状态码、HTTP 响应、分段信息放到「查看错误详情」（现在叫「查看返回」）里。

### 3.4 空结果 vs 未收录 vs 作品不存在，需要区分

位置：

- `ui/asmr/OnlineScreens.kt:651`、`asmr/AsmrClient.kt:168,286`：「未找到该作品」「RJ 号不正确」
- `ui/asmr/OnlineScreens.kt:1285,1637`：「asmr.one未收录」
- `ui/library/LibraryViewModel.kt:227`：「没有这个作品」
- `ui/asmr/OnlineScreens.kt:1585`：「没有读到作品。」

建议：明确三种状态：

- 作品不存在 / RJ 号有误
- asmr.one 明确未收录（但 DLsite 可能有）
- 连接失败，暂时无法判断

这也会影响用户是否应该去 DLsite 找。

### 3.5 失败提示缺少下一步

位置示例：`ui/player/PlayerScreens.kt:220,455`「翻译失败」；`ui/asmr/OnlineScreens.kt:936`「下载失败」；`ui/asmr/OnlineScreens.kt:1048`「保存失败」。

建议：补一句可行动的话：「翻译失败，请检查网络或平台密钥后重试」「保存失败，请稍后重试」。不需要长，但要让用户知道能做什么。

---

## 4. 品牌、空格与排版

这些问题不影响理解，但会影响成品质感，建议统一：

| 项 | 现状 | 建议 |
| --- | --- | --- |
| B 站 | 徽章/筛选写「B站」，帮助和错误里常写「B 站」 | 二选一全局统一；正文建议「B 站」，窄徽章可保留「B站」 |
| DLsite Play | `ui/library/LibraryWorkScreen.kt:201`、`ui/asmr/OnlineScreens.kt:1622` 写「DLSite Play」；来源徽章写「DLsite」 | 统一「DLsite Play」 |
| UP 主 | `ui/bilibili/BiliBrowser.kt:73` 写「UP主」，同文件其他位置写「UP 主」 | 统一「UP 主」 |
| asmr.one 未收录 | `ui/asmr/OnlineScreens.kt:1285,1637` 写「asmr.one未收录」 | 中间加空格；或改成徽章「未收录」并把来源放在旁边 |
| 多 P | `ui/library/LibraryRows.kt:335`、`ui/bilibili/BiliVideoScreen.kt:131` 写「多P」 | 统一「多 P」；窄徽章可保留「多P」但需一致 |
| SESSDATA | `ui/asmr/OnlineScreens.kt:2266,2355` 写「SESSDATA登录」 | 写「SESSDATA 登录」或「粘贴 SESSDATA」 |
| 搜索 B 站 | `ui/bilibili/BiliBrowser.kt:130` 写「搜索B站音频」 | 「搜索 B 站音频」 |
| 句末标点 | 「这个文件夹是空的。」「没有读到作品。」有句号，多数状态没有 | 统一状态短句不加句号，说明性文字加句号 |

---

## 5. 建议统一词表（可以直接作为改文案的基准）

| 概念 | 现在出现的叫法 | 建议统一 |
| --- | --- | --- |
| 只登记条目/作品，不下载文件 | 保存 / 保存到音频库 / 导入 / 入库 | 加入音频库 |
| 下载文件并入库 | 下载 / 下载全部音轨 / 完整包下载 | 下载并加入音频库（或「下载」+ 帮助说明） |
| 只下载文件、不入库（B 站） | 缓存音轨 / 缓存到本地 / 缓存 / 已缓存 | 仅缓存音轨（不入库） |
| 删除 App 下载副本 | 删除已下载的文件 / 删除已缓存的音轨 | 删除已下载文件 |
| 解除用户本地文件关联 | 取消识别 | 取消本地关联 |
| 把本地文件夹挂到作品 | 识别为作品 / 识别本地文件夹 | 关联为作品 / 关联本地文件夹 |
| 插入下一首 | 下一个播放 | 接下来播放 |
| 追加到当前队列 | 加到末尾 / 追加 / 加入当前列表末尾 | 加入播放队列 |
| 加入自建列表 | 加入播放列表 | 加入播放列表 |
| 收藏 B 站合集/系列到播放列表 | 收藏到播放列表 | 收藏在线列表 |
| 复制为不随远端更新的列表 | 复制为自建播放列表 | 复制为普通播放列表 |
| 临时图片缓存 | 缓存 / 缓存上限 / 清理缓存 | 图片缓存 / 临时文件 |
| 删除任务记录 | 删除 / 删除下载任务 | 删除任务 |
| 查看异常详情 | 查看返回 | 查看错误详情 |

---

## 6. 次要但值得改的文案

### 设置与模型

- `ui/settings/ModelSettingsScreen.kt:119,151` 的「口令」与同页 `516` 的「API Key」统一。
- `ui/settings/ModelSettingsScreen.kt:165` 的 supportingText「模型名」没有说明可留空。建议标签「模型名（可选）」，说明「留空使用 qwen3.8-flash」；DeepSeek 同理。
- `ui/settings/ModelSettingsScreen.kt:230`「未配置完成」→「未配置」或「还缺口令」。
- `ui/player/PlayerChrome.kt:837`「SenseVoiceSmall 模型还没有下载。请到…先下载模型」有重复；改为「请先在『设置 → 听写和翻译』下载 SenseVoiceSmall 模型」。
- `ui/settings/SettingsScreen.kt:62-73` 分组标题「外观」和行标题「外观」重复。行标题可改「主题与颜色」。
- `ui/settings/CacheScreen.kt:53`「当前占用 X」→「当前图片缓存 X」（如果保留“缓存”一词）。

### 登录

- `ui/asmr/OnlineScreens.kt:2376`「改用文字框里的登录？」→「改用粘贴的登录信息？」；「文字框」→「输入框」。
- `ui/asmr/OnlineScreens.kt:2283` 清除 B 站登录的说明默认写了「扫码登录的自动续期也会停止」；手动登录时这句话不适用。建议按是否有续期能力显示不同说明。
- `ui/asmr/OnlineScreens.kt:2230` DLsite 用「退出」，B 站用「清除」，YouTube 也用「清除」。同一类动作统一为「退出登录」或「清除登录信息」。
- `ui/youtube/YouTubeScreens.kt:206,212-220` 与 `youtube/YouTubeClient.kt:82-84` 同时存在「已保存，但 YouTube 没有确认登录」「已保存，未能确认登录状态」。统一其中一句。
- `ui/youtube/YouTubeScreens.kt:260` 清除 YouTube 登录的确认只提到列表不可读；可补一句「已保存到音频库的曲目和下载文件不受影响」，减少顾虑。

### B 站与合集

- `ui/bilibili/BiliImportDialog.kt:22`「确认转换入库方式」→「更改入库方式？」。
- `ui/bilibili/BiliImportDialog.kt:31-32` 两段说明偏长且出现「原组」「分 P」「视频项」「关联」等词。建议拆成：
  - 合并：「把这个视频已有的分 P 合并为一个视频项；下载、字幕和播放列表关联不变。」
  - 拆分：「原分 P 会保留为独立项并放回原目录；勾选只影响本次新增内容。」
- `ui/bilibili/BiliImportDialog.kt:42`「全部 3 P 合为一个视频项」→「全部 3 个分 P 合并为一个视频项」。
- `ui/bilibili/BiliImportDialog.kt:73`「确认转换并继续」→「确认更改并继续」；`继续`→`按此方式导入`。
- `ui/bilibili/BiliCollectionScreen.kt:127-130`「继续处理 N 个成功项」→「继续处理其余 N 个视频」。
- `ui/bilibili/BiliCollectionScreen.kt:143`「目录缓存」→「本地目录缓存」或「已读取的目录」。
- `ui/bilibili/BiliCollectionScreen.kt:87`「这个合集没有视频」→「这个合集暂时没有视频」。
- `ui/bilibili/BiliCollectionScreen.kt:152`「顺序播放全部」→「按当前排序播放全部」（因为支持倒序/随机）。
- `ui/bilibili/BiliBrowser.kt:155-157`「已保存 3 P」→「已保存 3 个分 P」；「已下载 3 P」→「已下载 3 个分 P」。
- `ui/bilibili/BiliBrowser.kt:131`「这位 UP 主暂时没有可显示的投稿。」→「这位 UP 主暂时没有投稿。」（更直接）。
- `ui/bilibili/CollectionCatalogue.kt:28`「没有读到合集或系列」→「没有合集或系列」。

### 下载与文件

- `ui/download/DownloadScreen.kt:158` 空状态只写「在作品里可以选择单个音频，或缓存整个文件夹」，遗漏音频库、播放页、收藏夹等入口。建议「在作品、音频库或播放页选择音频下载」。
- `ui/download/DownloadScreen.kt:253-257`「只删除这条失败记录」；用户可能担心删除文件。可写「只删除任务记录，已下载的文件保留」。
- `ui/download/DownloadScreen.kt:149-154` 分段「音频 / 字幕」；字幕页实际是听写与翻译任务，建议「听写与翻译」。
- `ui/download/StoredFilesScreen.kt:241`「没有已下载的文件」没有覆盖生成字幕；建议「还没有下载或生成的文件」。
- `ui/download/StoredFilesScreen.kt:252`「长按文件可多选、导出或删除」；若改「分享」，这里同步。
- `ui/download/StoredFilesScreen.kt:209` 多选操作「作品」→「打开作品」。
- `ui/download/StoredFilesScreen.kt:89,131,150` 的「无法读取已下载的文件」「无法读取字幕信息」「删除失败」都偏技术/笼统；可改「无法读取文件列表」「无法读取字幕信息，请重试」「删除失败，请重试」。
- `ui/library/FavoriteScreen.kt:153`「已导入音频库，失败 N 个视频」→「已加入音频库；另有 N 个视频读取失败」。
- `ui/library/FavoriteScreen.kt:184`「已加入 N 个缓存」→「已开始缓存 N 个视频的音轨」。
- `ui/library/FavoriteScreen.kt:226`「若刚保存过登录信息，请返回后重新进入。」→「登录信息可能还没生效，请返回后重新进入收藏夹。」

### 播放与字幕

- `ui/player/PlayerChrome.kt:830` 长提示缺句末标点，且「API Key / 口令」混用。建议「请先在『设置 → 听写和翻译』保存听写设置（线上模型填 API Key，自部署模型填地址和口令）。」
- `ui/player/PlayerChrome.kt:311-312`「当前没有在播的音频 / 从音频库点一首，或播放一个列表。」语气可以，但「在播」偏口语；可改「当前没有正在播放的音频」。
- `ui/player/PlayerChrome.kt:551` 队列项删除的 contentDescription「从当前列表移除」→「从队列移除」。
- `ui/player/PlayerChrome.kt:465`「缓存音轨」按钮没有说明缓存后是否入库。建议加一句帮助或在下载管理状态中体现。
- `ui/player/PlayerChrome.kt:794-821` 定时关闭的「分 / 秒」输入框可以，但「开始」→「开始倒计时」更明确。
- `ui/player/PlayerChrome.kt:708-709` 「润色」「生成翻译」两个动作没有说明区别；帮助里已有，可保留。

### 在线作品

- `ui/asmr/OnlineScreens.kt:881,915`「没有可播放的音频」→「这个文件没有可播放的音频」。
- `ui/asmr/OnlineScreens.kt:980`「没有可下载的地址」→「这个文件没有可用的下载地址」。
- `ui/asmr/OnlineScreens.kt:925,949`「找不到作品编号」→「这个作品缺少编号，无法下载」。
- `ui/asmr/OnlineScreens.kt:943,975`「已经下载」→「已下载」。
- `ui/asmr/OnlineScreens.kt:962,996`「已经在下载」→「正在下载」或「已加入下载队列」。
- `ui/asmr/OnlineScreens.kt:930,959`「已加入完整包下载」→「已加入完整原始包下载任务」。
- `ui/asmr/OnlineScreens.kt:1502`「没有作品编号」→「这个作品缺少编号」。
- `ui/asmr/OnlineScreens.kt:1508`「没有可用的浏览器」→「没有可以打开网页的浏览器」。
- `ui/asmr/OnlineScreens.kt:1531-1553`「打开外部浏览器」弹窗其实是在选 asmr.one / DLsite 站点；标题建议「在浏览器打开作品页」。
- `ui/asmr/OnlineScreens.kt:1756`「这个文件夹是空的。」去掉句号，与其他空状态一致。
- `ui/asmr/OnlineScreens.kt:1939`「没有试听」→「暂无试听」。
- `ui/asmr/OnlineScreens.kt:2014,2033`「登录失败」补「请检查网络后重试」。

### 剪贴板

- `ui/importing/ClipboardImportDialog.kt:33`「是否要导入：」带冒号且像半句话。建议「导入这个内容？」。
- `ui/importing/ClipboardImportDialog.kt:38`「暂时无法读取内容」→「无法读取标题和封面」。
- `ui/importing/ClipboardImportDialog.kt:43`「已在音频库，可调整入库方式」可以；「入库方式」建议统一为「导入方式」。

### 关于与更新

- `ui/settings/AboutScreen.kt:84`「当前版本：1.3（4）」→「版本 1.3（构建 4）」。
- `ui/settings/AboutScreen.kt:98`「当前版本高于最新发布版本」→「本机版本比发布版本新」。
- `ui/settings/AboutScreen.kt:91`「从 GitHub 检查最新正式版本」可以；可补“不会自动下载或安装”。
- `ui/settings/AboutScreen.kt:106`「在浏览器打开发布页」在尚未检查更新时会打开全部发布列表，不一定是“最新发布页”；可保留，但文案不要暗示是单个版本。

### 外观

- `ui/settings/AppearanceScreen.kt:96` 不支持系统取色时显示「需要 Android 12 及以上，当前使用默认靛蓝」。此时行是禁用的，用户可能以为默认靛蓝是当前选择；建议「当前系统不支持，暂用默认靛蓝」。

### 错误提示的通用写法

建议把错误分成两层：

1. 第一层：用户能理解、能行动的一句话（例如「B 站暂时限制了访问，请稍后重试」）。
2. 第二层：「查看错误详情」里放状态码、HTTP 响应、解析失败原因。

这样既能保留排查能力，又不会让普通用户直接看到「音频分段信息无效」「解压暂存目录不是空目录」这类实现语言。

---

## 7. 需要同步修改的文档

如果按本报告改文案，以下文档中的引用需要一起更新：

- `Android/README.md:81`、`Android/README.md:283`：`设置 → 下载管理 → 字幕` 的实际入口与文案。
- `AGENTS.md:79`：同样提到下载管理「字幕」。
- `Android/README.md:262-263`：设置页分组和入口名称。
- `Android/README.md:240`：下载页「音频 / 字幕」命名，如果改名。
- `Android/README.md:322`：清理缓存与「B 站缓存音轨」的术语冲突。
- `Android/README.md:75,81`：多选「删除已下载的文件」与「取消识别」的区别。

---

## 8. 建议的修改顺序

1. 先改会误导入口或行为的部分：第 0 节第 1、2、3、10 条和第 1.5、1.6 条。
2. 再做一次全局术语替换：保存/导入/入库、下载/缓存、队列/播放列表、识别/关联、收藏。
3. 最后统一空状态、错误提示和品牌空格。

第 1、2 步会同时影响帮助文案和 README；建议一次改完后再统一检查，不要逐条零散改动。
