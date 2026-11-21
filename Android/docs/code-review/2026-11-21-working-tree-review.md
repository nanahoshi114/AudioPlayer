# Android 未提交代码审查报告

- 审查日期为 2026-11-21，基线为 `ea34913`，范围为 Android 工作区的未提交改动。
- 共 12 项待处理问题，包括 1 项 P1、9 项 P2、2 项 P3。P1 的处理方案已按讨论更新。
- Japanese ASMR 下文简称 JA，界面按钮沿用「刷新 JA 篇目」。

## P1

### 1. JA 播放失败时提供恢复提示，库内清理由显式刷新处理

**现状。** 弹窗只询问是否从队列中清除音频。确认后，如果该项关联了库内曲目，仓储会删除曲目及其播放列表关联，并终止相关任务。篇目不可用、完整篇目确认音轨已消失，以及媒体候选地址均返回 404 或 410，都能触发这个弹窗。媒体地址不可用时，音轨仍可能列在篇目中。 [播放失败触发条件](../../app/src/main/java/com/nanahoshi/audioplayer/playback/PlaybackService.kt#L106) · [媒体候选判断](../../app/src/main/java/com/nanahoshi/audioplayer/playback/BiliPlaybackDataSource.kt#L138) · [仓储删除逻辑](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt#L807)

**处理方案。** 移除播放失败弹窗中的删除操作。普通播放失败提示使用「音频播放失败，请检查网络后重试。如果仍无法播放，可到作品页点击『刷新 JA 篇目』。」需要网页验证时继续提供「验证并继续」。播放失败时保留库内曲目、队列、播放列表、字幕和任务。 [现有弹窗](../../app/src/main/java/com/nanahoshi/audioplayer/ui/player/PlayerChrome.kt#L241)

**库内清理规则。** 维持作品内单曲禁止单独移除的规则。用户显式刷新篇目时，只有在作品编号相符、媒体列表完整且确认为已从篇目消失时，才清理无有效本地副本、也未在播放器中选中的音轨。同步清理受影响的队列和播放列表条目，保留字幕和任务记录。用户想清理当前项时，可先切换到其他音轨再刷新。提示文案只引导刷新，避免承诺刷新一定会清理。 [作品内单曲移除规则](../../../AGENTS.md#L135) · [篇目刷新规则](../../../AGENTS.md#L185) · [刷新事务](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt#L735)

## P2

### 2. 限制 JA 原生媒体请求及重定向的目标

网页直链和播放清单中的分片、密钥地址由原生客户端请求。客户端可访问任意 HTTP(S) 目标并自动跟随重定向，页面或清单被篡改时，播放和下载可能访问设备可达的本机或局域网服务。将首个请求及每次重定向限定在获准的公开媒体地址，并校验实际连接目标。覆盖在线播放、媒体探测和下载。 [媒体客户端](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JapaneseAsmrClient.kt#L198) · [媒体播放入口](../../app/src/main/java/com/nanahoshi/audioplayer/playback/BiliPlaybackDataSource.kt#L193)

### 3. 从队列移除失效项时保留其余曲目

失效项仍为当前播放项时，现有确认分支会清空播放器的媒体列表，而数据库只移除受影响的队列项。剩余曲目仍显示在队列界面，需要再次点选才能重建播放器列表。落实第 1 项时移除这条确认分支，并验证用户通过队列执行单项移除后，剩余项目的顺序和续播能力保持正常。 [播放器删除分支](../../app/src/main/java/com/nanahoshi/audioplayer/playback/PlayerClient.kt#L68)

### 4. 播放时根据本地副本的当前状态选择音源

组装队列项时，已有的 JA 副本被固定为本地文件地址。删除副本后，原队列项仍访问旧文件。反过来，先入队后下载完成的音轨仍可能联网。按稳定媒体身份在打开音轨时选择有效的本地副本，或在副本变化后更新对应队列项。验证同一队列项在下载完成后优先播放本地文件，在删除副本后还能在线播放。 [播放项组装](../../app/src/main/java/com/nanahoshi/audioplayer/playback/MediaItemFactory.kt#L41) · [本地副本约定](../../../AGENTS.md#L103)

### 5. 刷新篇目时识别按媒体身份入队的音轨

用户先在在线详情播放、再将作品加入音频库时，旧队列项只有篇目和音轨身份，没有库内曲目编号。刷新只按库内曲目编号匹配队列，会漏掉当前项的保护，也可能留下其他已确认消失的队列项。刷新应同时识别两种入队方式，保护当前项，只清理确认匹配且非当前的失效项。 [刷新清理逻辑](../../app/src/main/java/com/nanahoshi/audioplayer/data/LibraryRepository.kt#L755) · [篇目刷新规则](../../../AGENTS.md#L185)

### 6. Buzzheavier 传输遇到验证时可从失败文件继续

清单探测可以成功，实际下载或续传随后才遇到验证。对已验证清单，当前代码直接把传输异常抛出。其余清单会重读后再传，若传输仍遇验证则任务会普通失败，页面缺少「验证并继续」。传输阶段也应进入等待验证状态，记住当前失败文件的托管页，通过现有入口验证后继续。 [下载任务状态转换](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L385) · [托管页恢复约定](../../../AGENTS.md#L188)

### 7. Buzzheavier 探测和传输都应核验响应为文件

托管地址可能以 HTTP 200 返回提示网页。清单探测会把网页长度记为文件大小，后续传输若收到同样长度的网页且没有远端校验值，就可能把它按原文件名发布。探测与实际传输都要识别并拒绝网页响应。遇到验证页时进入第 6 项的等待验证流程。 [清单探测](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JaWorkClient.kt#L376) · [文件传输](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JaWorkClient.kt#L443)

### 8. 在下载管理中列出整部作品附带的字幕

JA 和 DLsite 的已发布清单目前都跳过非音频条目。JA 整部作品目录不参与后续扫描，DLsite 原始包没有剩余音轨时也可能无法触发目录扫描。直接从两份清单将存在的 `lrc`、`srt`、`vtt` 文件纳入「下载管理 → 字幕」，并验证作品只剩字幕时仍能查看和删除。 [已发布文件枚举](../../app/src/main/java/com/nanahoshi/audioplayer/data/StoredFiles.kt#L153) · [下载管理分类](../../../AGENTS.md#L101)

### 9. 「全部篇目」应覆盖去重后的全部下载入口

同一作品有多个独立下载入口时，「全部篇目」只扩大了候选范围，用户仍须选一个入口，最终只确认和下载一份清单。按现有界面和文档约定，「全部篇目」应确认并处理全部去重后的入口。测试多个篇目共享一个入口，以及多个篇目对应多个入口的情况。若产品决定只支持单入口，应同步调整按钮、提示和文档。 [入口选择逻辑](../../app/src/main/java/com/nanahoshi/audioplayer/ui/library/LibraryViewModel.kt#L713) · [篇目选择约定](../../../AGENTS.md#L133)

### 10. HLS 部分续传应保留本次传输统计

重试时只要命中一个已暂存分片，代码就将整项标为复用。即使本次还下载了其他分片，完成摘要也会丢掉实际传输量和耗时。只有全部分片均可复用时才标记整项复用。部分续传应按本次实际传输量和耗时展示，暂存文件大小仍可计入任务总体完成进度。 [分片续传逻辑](../../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JapaneseAsmrHlsDownload.kt#L63)

## P3

### 11. 同步 README 的数据库版本和迁移说明

README 的源码索引写第 17 版，迁移说明写第 21 版。两处都应改为第 22 版，并补充 21→22 引入的 JA 整部作品下载清单及压缩包版本表、任务入口摘要和确认选项，以及媒体和队列的路径摘要字段。 [README 源码索引](../../README.md#L380) · [README 迁移说明](../../README.md#L385) · [数据库迁移](../../app/src/main/java/com/nanahoshi/audioplayer/data/db/AppDatabase.kt#L107)

### 12. 说明 JA 整部作品下载在重启后的恢复行为

README 和 `AGENTS.md` 目前笼统描述未完成的音频任务为中断或需手动重试。重新打开应用后，仍有效且尚在排队的 JA 整部作品任务可自动接手。已经开工、下载对象仍有效的任务标为中断，可手动重试。在两份文档中写明这一区别。 [启动恢复逻辑](../../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L630) · [README 下载说明](../../README.md#L250) · [开发指南下载约定](../../../AGENTS.md#L100)

## 验证记录

- 原审查记录显示 Android 主代码及仪器测试代码编译通过，461 项 JVM 单元测试通过。原审查未执行模拟器、真机或真实站点验证。
- 此前模拟器文案复核另外覆盖了 360 dp 下的截图和本地下载。两项失败涉及迁移版本断言和 B 站合集模拟响应，修正测试后，相关六个仪器测试类共 23 例全部通过。 [模拟器文案复核](../ui-copy-review-v1.4.md#44-字体缩放与长文本)
- 本报告中的修复仍待实施，相关业务回归测试待执行。优先覆盖播放失败不删除库内数据、显式刷新保护从在线详情入队的当前项、本地副本增加与删除后继续播放，以及下载验证后继续和网页响应不发布。
