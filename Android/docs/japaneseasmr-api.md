# Japanese ASMR 接口与 Android 实现

调查与验证日期为 2026-11-18 至 2026-11-19。目标站点为 <https://japaneseasmr.com/>。

本文记录站点的公开页面接口、Android 当前实现及真机和模拟器测试。已接入的 App 功能见第 9 节，网页 Download 入口的独立实验见第 10 节。

> 术语：用户界面把 Japanese ASMR 的站内文章称为「篇目」；本文在 WordPress/接口层继续使用「文章」，涉及 App 按钮时写「篇目」。

## 1. 结论

1. **作品目录与详情来自 HTML。** Android 读取 `/search/{关键词}/` 和站内文章页。已测试的 WordPress REST 入口均返回 `401 rest_not_logged_in`。同一个 RJ 可以关联多篇文章，各篇分别保存自己的音轨身份。
2. **App 原生在线播放和下载使用文章主播放器的资源。** 直链保存可用的音频文件，符合支持范围的 HLS 流逐段下载后封装为 M4A。真机已验证在 App 内完成站点验证后可以播放和下载。
3. **站点验证按实际响应触发。** App 可复用用户已读取的文章资料。新请求只有收到验证页时才报告验证，连续多次访问都无需操作也符合当前实现。
4. **「刷新JA篇目」会更新在线列表，保留本地副本。** 已入库作品按同一篇文章的音轨身份同步。匹配项更新，新增项入库。显式刷新完整篇目时清理确认消失且没有有效本地副本、也未在播放器中选中的音轨。
5. **网页 Download 是单独的文件入口。** 新版样例的 Buzzheavier 音频及旧版样例的 MEGA 分享文件曾在 Android 16 模拟器上用独立探针验证。App 的整作下载接入见第 9 节。

下文记录各次实测的路径、设备和范围。响应状态与传输速率以当次样例为准。

## 2. 调查方法与证据范围

第 3 至 8 节保留最初的页面调查。该轮用 Python 标准库 `urllib.request` 发送只读请求。页面请求显式设置 `User-Agent: Mozilla/5.0` 和 `Accept: */*`，HTTPS 使用默认的证书校验。请求未附加账号凭证，统计接口仅阅读脚本。

普通命令行 curl 在本机直接连接目标域名时出现 TLS 连接失败；另一抓取环境返回过 403/451。随后使用系统默认网络设置成功取得页面。这些结果应分别记录，不能将某个抓取环境失败直接解释为网站下线。

最初的媒体抽样仅获取一份 HLS 清单、一个分片的前 64 KiB 和直接文件的少量文件头。后续另行在真机验证现有 App 功能，并用浏览器和 Android 16 模拟器验证网页 Download，结果分别见第 9 节和第 10 节。

主要实测记录：

- 首页 `/`、分页 `/page/2/`：200，HTML；第二页有 14 个 `.entry-preview` 条目。
- `/search/RJ01258546/`：200，HTML；返回 `/135575/`、`/123908/` 两个条目，两者卡片都含该 RJ 编号。
- `/?s=RJ01258546`：403，HTML，响应头含 `cf-mitigated: challenge`。
- `/135575/`、`/58186/`、`/111608/`：200，HTML；用于核对 HLS、单曲多格式、多个曲目三种结构。
- `/tags/`、`/cv/`、`/tag/asmr/`、`/category/rating/sfw/`、`/extended-search/`：200，HTML。
- `/wp-json/`、下文三个 `/wp-json/wp/v2/...` 请求：401，JSON。
- `/?rest_route=/wp/v2/posts/135575`：403，Cloudflare 验证页。
- `/date/2026/11/?orderby=post_views&order=desc`：403，Cloudflare 验证页；该地址来自站点链接。
- `/robots.txt`、`/wp-sitemap.xml`：200；`/feed/`：500。

## 3. 目录、搜索和筛选入口

基址为 `https://japaneseasmr.com`。以下都是网页入口，响应不是作品 JSON。

### 首页与分页

```http
GET /
GET /page/2/
```

分页区域使用 `nav.navigation.pagination`，下一页链接为 `a.next.page-numbers`。建议读取实际 `href`，到没有下一页时结束；不要把本次观察到的 14 条或末页编号当成固定协议，也不要据此推算精确总作品数。[来源：首页](https://japaneseasmr.com/)、[第二页](https://japaneseasmr.com/page/2/)。

每个列表条目可按以下结构读取：

- `.entry-preview`：一张作品卡片的范围。
- `.entry-title a`：标题和详情地址；详情路径中的数字为站内文章编号。
- 卡片内 `img[data-src]`：封面实际地址。本次 `src` 是占位内容，应优先读取 `data-src`。
- 卡片内 `time[datetime]`：站点文章日期，不能直接当作作品发售日。
- 卡片文本中的 RJ 编号：可用于关联作品，但部分信息仍需进入详情页读取。

解析应限定在卡片范围，避免把导航、推荐链接或广告中的地址加入结果。

### 关键词与 RJ 搜索

站点搜索表单声明：

```http
GET /?s={URL编码后的关键词}
```

本次该请求被 Cloudflare 验证拦截，但下面的路径形式成功：

```http
GET /search/RJ01258546/
```

关键词作为单个路径段编码；不要直接拼接用户输入中的 `/`、`?` 或 `#`。这里只实测了一个 RJ 搜索，没有验证多关键词、日文关键词的匹配规则和搜索后续分页。[来源：搜索结果](https://japaneseasmr.com/search/RJ01258546/)。

同一个 RJ 搜索返回了两个不同的站内文章。搜索结果需要保留各自的文章地址，不能假设 RJ 与文章一一对应；确认匹配时应核对条目或详情中的作品号，而不是只取搜索第一条。

### 标签、声优与分类

```http
GET /tags/
GET /cv/
GET /tag/asmr/
GET /category/rating/sfw/
```

`/tags/` 提供标签链接；`/cv/` 中的声优链接也指向 `/tag/{slug}/`。声优并非本次发现的独立 API 实体。应读取页面已有的标签地址，保留日文 slug 的 URL 编码，不要用显示名称自行生成英文 slug。[来源：标签目录](https://japaneseasmr.com/tags/)、[声优目录](https://japaneseasmr.com/cv/)。

首页导航还提供 `rating/r-15`、`rating/maniax` 等分类链接；本次只请求了 SFW 分类，其他分类的访问状态未验证。分类页与标签页响应头虽然链接到 WordPress JSON 路径，也不代表该 JSON 路径允许匿名读取。[来源：SFW 分类](https://japaneseasmr.com/category/rating/sfw/)、[ASMR 标签](https://japaneseasmr.com/tag/asmr/)。

### 热门与日期归档

`/extended-search/` 本次返回的主体是按年、月列出的热门导航，并没有复杂搜索条件表单。页面链接形如：

```http
GET /date/2026/?orderby=post_views&order=desc
GET /date/2026/11/?orderby=post_views&order=desc
```

`orderby=post_views`、`order=desc` 是站点实际链接中的参数。本次月度地址返回 403，没有验证排序结果；年份/月度导航不能解释成 DLsite 发售时间筛选。站点 `robots.txt` 还明确排除了含 `orderby` 的抓取路径，自动目录读取应跳过这些链接。[来源：扩展搜索页](https://japaneseasmr.com/extended-search/)、[robots.txt](https://japaneseasmr.com/robots.txt)。

## 4. WordPress REST API 的实际限制

首页有如下发现链接：

```http
Link: <https://japaneseasmr.com/wp-json/>; rel="https://api.w.org/"
```

实测以下请求全部返回 HTTP 401：

```http
GET /wp-json/
GET /wp-json/wp/v2/posts/135575
GET /wp-json/wp/v2/posts?search=RJ01258546&per_page=1
GET /wp-json/wp/v2/media?parent=135575&per_page=1
```

返回正文一致：

```json
{
  "code": "rest_not_logged_in",
  "message": "You are not currently logged in.",
  "data": { "status": 401 }
}
```

因此，本次没有取得文章 JSON、媒体 JSON、命名空间清单或路由参数定义。`search`、`per_page`、`parent` 只是探测请求中的 WordPress 常见参数，不能由 401 认定站点已经接受并执行了这些筛选。[实测入口](https://japaneseasmr.com/wp-json/)。

查询参数形式 `/?rest_route=/wp/v2/posts/135575` 返回的是 403 验证页，并没有获得 API 数据。没有测试站点登录，也没有证据说明普通账号登录后一定能访问这些接口；其他来源的 Cookie 不能用于此站。

## 5. 作品详情与播放地址

```http
GET /{站内文章编号}/
```

详情页的可用字段位置：

- `h1.page-title`：文章标题。
- `#voice_actors`：声优文字，样例以 `CV:` 开头。
- `.entry-content` 中以 `RJ Code:` 开头的段落：作品号；不应从整页推荐区随意取第一个 RJ。
- `.entry-content img[data-src]`：封面和介绍图片，需按页面角色区分。
- `.cleanPlayer`：主播放器；其内部每个 `video` 或 `audio` 对应一个媒体条目。
- `.audio_main`：基础播放器区域，样例中与主播放器重复。

RJ 应作为字符串保存，例如 `RJ01258546`，不先转换成整数。编号 `135575` 是站内文章编号，不能用它构造 DLsite 或 asmr.one 的作品请求。[来源：详情样例](https://japaneseasmr.com/135575/)。

### 按曲目提供多个格式

`/58186/` 的主播放器有一个 `video`，其下两个 `<source>` 分别声明 `audio/mpeg` 和 `audio/mp4`；`/111608/` 有两个 `video`，每个各有两个格式候选。示意结构如下，地址已替换：

```html
<div class="cleanPlayer">
  <video title="Track 1" descr="...">
    <source src="<MP3地址>" type="audio/mpeg">
    <source src="<M4A地址>" type="audio/mp4">
  </video>
  <!-- 另一 video/audio 元素才是下一个媒体条目 -->
</div>
```

这里两种格式是同一条曲目的候选，不能导入成两首曲目；基础播放器中的重复地址也不能再增加曲目数。`video` 标签是网页播放器的承载方式，不能单凭标签名判断文件含视频。[来源：单曲样例](https://japaneseasmr.com/58186/)、[多曲样例](https://japaneseasmr.com/111608/)。

站点 `clean-html-player.js` 的 `getSourceConfig(container)` 直接遍历 `video, audio`，再读取各元素下的 `source` 和 `track` 属性，形成播放器配置。这个过程读取页面现成数据，没有调用作品详情或媒体解析 JSON 接口。[来源：站点播放器脚本](https://japaneseasmr.com/wp-content/plugins/cleanaudioplayer/assets/clean-html-player.js?ver=6.8.12)。

另一个站点脚本 `playlist.js` 支持从 `#basic-playlist li a` 的 `data-url` 切换音频；这是旧式列表的源码线索，本次三个详情样例没有据此验证额外曲目。[来源：列表脚本](https://japaneseasmr.com/playlist.js?ver=2)。

### HLS 整体流与章节

`/135575/` 的主播放器 `<source src>` 和基础播放器 `<audio src>` 都指向同一份 `.m3u8`。页面内联脚本把该地址传给 `Hls.loadSource`，再用 `attachMedia` 关联播放器。没有观察到取得地址所需的签名计算或额外解析请求。[来源：HLS 样例](https://japaneseasmr.com/135575/)。

章节表分别位于：

- `#plyr-chapter-playlist`：主播放器章节。
- `#basic-chapter-playlist`：基础播放器的重复章节。

行内 `td.chapter_list.start_time` 为时间文字，`td.chapter_list.chapter_title` 为名称；链接的 `data-value` 是跳转秒数。样例有效起点为 `0`、`83`、`1317`、`2308`、`3293`，共 5 章。应选择一个章节表，并合并同一行的重复链接、跳过表头；不能把两个表或同一行两个链接分别导入。

章节起点描述同一条媒体流上的位置，不是独立音频地址。可由下一章起点推算当前章时长，最后一章仍需要媒体总时长；它们也不是带完整台词和结束时间的字幕。

## 6. 媒体主机、请求头和传输实测

三个详情样例中的音频主机为 `v.weeab0o.xyz`；图片主机包括 `pic.weeabo0.xyz` 和 `img.weeabo0.xyz`。注意域名中 `0` 和 `o` 的位置不同。地址应从页面原样解析并处理相对路径，不按 RJ 编号猜文件名，也不手工替换域名。[来源：上述详情样例](https://japaneseasmr.com/135575/)。

本次成功的媒体请求显式使用：

```http
User-Agent: Mozilla/5.0
Referer: https://japaneseasmr.com/
```

读取文件头时另外使用：

```http
Range: bytes=0-1023
Accept-Encoding: identity
```

未附加 Cookie、Authorization 或 Origin。样例结果如下：

- HLS 清单：200，`Content-Type: application/vnd.apple.mpegurl`，正文 23,476 字节。
- 清单是媒体播放列表，含 391 个 `.ts` 分片和 `#EXT-X-ENDLIST`；`EXTINF` 时长之和约为 3,907.861 秒。本样例没有主清单、密钥或初始化片段标签，不能据此排除其他作品使用这些结构。
- 首个 TS 分片的前 65,536 字节：206，`Content-Range: bytes 0-65535/111108`。文件头按 188 字节分包，348 个完整包都具有 TS 同步字节；读到的 PMT 只有 `stream_type=0x0f`，对应 AAC 音频。这只验证该分片样例。
- TS 响应的 `Content-Type` 却是 `text/vnd.trolltech.linguist`，与实际容器不符。不能只靠服务器 MIME 判断分片是不是媒体。
- `/58186/` 中 MP3 候选的前 1,024 字节：206，`Content-Type: audio/mpeg`，`Content-Range: bytes 0-1023/96707395`。
- 同一曲目的 M4A 候选：404，HTML。页面声明了此格式，不等于对应文件仍然存在。
- MP3 去掉 `Referer` 后的一次对照：403，HTML。该结果支持媒体请求保留站点 Referer；本次没有进一步判定拦截机制，也没有验证所有主机都要求相同请求头。

以上是播放器线路的样本抽查。当前 App 的候选选择和 HLS 封装见第 9 节。音质和编码以实际返回的媒体为准，作品介绍中列出的 WAV 等格式仅描述商品内容。

## 7. 其他请求与公开目录

### `admin-ajax.php` 是播放量统计线索

详情页的 `pvcArgsFrontend` 声明 `mode: "js"`，请求地址为 `/wp-admin/admin-ajax.php`。`post-views-counter/js/frontend.js` 对应分支发送：

```http
POST /wp-admin/admin-ajax.php
Content-Type: application/x-www-form-urlencoded

action=pvc-check-post&pvc_nonce=<页面当前值>&id=<文章编号>
```

这是 Post Views Counter 插件的统计请求，不是搜索、曲目列表或播放地址 API。本次仅阅读源码，没有发送该 POST，也未保存实际 nonce 到本文。脚本还包含 REST 模式分支，但不能据此推断站点当前启用了哪个统计 REST 路由。[来源：站点统计脚本](https://japaneseasmr.com/wp-content/plugins/post-views-counter/js/frontend.js?ver=1.3.7)。

### 站点地图与 RSS

`GET /wp-sitemap.xml` 返回标准 XML 站点地图索引，本次包含 28 个子地图地址，类型包括文章、分类、标签和用户。例如文章子地图为 `/wp-sitemap-posts-post-1.xml`。本次只核对索引，没有下载子地图或遍历作品；索引本身不提供曲目、封面或播放地址。[来源：站点地图](https://japaneseasmr.com/wp-sitemap.xml)。

`GET /feed/` 本次返回 HTTP 500、XML 类型，不能记录为已验证可用的结构化目录入口。[实测入口](https://japaneseasmr.com/feed/)。

### 抓取频率与排除路径

`robots.txt` 对通用客户端声明：

```text
User-agent: *
Crawl-delay: 10
Disallow: /request
Disallow: *?*orderby*
Disallow: *?*date_query*
```

后续自动目录读取应采用至少 10 秒的请求间隔，并跳过上述排除路径。站点未向本次请求提供可确认的 API 配额或限流契约；遇到验证页不应把它解析成「没有搜索结果」或循环重试。[来源：robots.txt](https://japaneseasmr.com/robots.txt)。

## 8. 第三方源码的可用线索与误判

以下是解析器作者的公开源码，可与站点页面交叉核对，但不是站点官方接口规范：

- [Map9876/japaneseasmr-webui 的 parser.py，固定版本 `5dd8d16`](https://github.com/Map9876/japaneseasmr-webui/blob/5dd8d16b1bcce30e727af9bcc6cbd272ac2d060a/japanese-asmr-parser/src/japanese_asmr/parser.py)：优先读取 `.cleanPlayer`、`.audio_main` 的媒体元素，解析章节表。相关页面结构已由本次直接请求核对。源码把媒体地址摊平成列表；接入时仍应保留同一媒体元素下的格式分组。
- [noNameCome/Japanese_ASMR_Downloader 的 audio_downloader.py，固定版本 `6da96cf`](https://github.com/noNameCome/Japanese_ASMR_Downloader/blob/6da96cf3ff0940e1f17a0214df1e2b4d7f21d441/audio_downloader.py)：包含 HTML 媒体提取，也会在没有地址时依次猜测常见 API 路径。

后一个文件列出的 `/api/audio/{postId}`、`/api/download/{postId}`、`/download.php?id=...`、`/get_audio.php?id=...` 属于探测候选，没有提供调用成功证据。本次也没有验证这些路径，不能把它们列为站点已存在的 API。

同样，第三方工具自己提供的 `localhost:5000/api/fetch-page`、`/api/download-full` 是其本地服务接口，不属于 `japaneseasmr.com`。第三方工具中的全页媒体正则回退还可能捞到广告，应优先限定主播放器范围。

## 9. Android 当前实现

### 来源、播放与下载

项目以 DLsite 商品目录为 RJ 作品全集，asmr.one 和 Japanese ASMR 各自收录。作品按 RJ 归并，各来源独立判断文件可用性。DLsite Play 需要验证已购。未购买且两站均未收录的作品仍可显示 DLsite 商品资料。RJ 按原始字符串保存，保留前导零。JA 的文章编号和媒体键共同标识音轨，文章发布日期与商品发售日分别处理。

`JapaneseAsmrClient` 分开使用页面与媒体客户端。页面请求串行，搜索和列表分页请求之间至少间隔 10 秒。App 从文章主播放器 `.cleanPlayer` 的 `video` 或 `audio` 元素选择媒体，请求时携带站点 `Referer`。每个媒体元素的多个 `<source>` 属于同一条音轨。仅有旧式 `#basic-playlist` 播放列表时会提示页面结构暂不支持。同一 RJ 的不同文章保持独立。临时媒体地址只留在内存中，数据库保存文章地址及音轨身份。[读取客户端](../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JapaneseAsmrClient.kt#L63) · [文章解析](../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JapaneseAsmrParse.kt#L105)

- **直链音频。** App 按格式候选尝试请求前 1,024 字节，选中可读候选后以一次完整请求保存音频。探测成功与否用于筛选候选。当前直链下载中断会清理本次半成品，重试从头读取。[直链下载](../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L934)
- **HLS 音频。** 播放时，媒体清单和下级资源使用带站点请求头的数据源。下载支持含 `EXT-X-ENDLIST` 的独立、未加密 AAC/TS 分片清单。当前按顺序获取分片，重试时按清单摘要复用已完整下载的分片。按原时间戳封装为一个 M4A，再检查音轨和时长。封装使用 [Media3 TsExtractor](https://developer.android.com/reference/androidx/media3/extractor/ts/TsExtractor) 和 [Media3 Mp4Muxer](https://developer.android.com/reference/androidx/media3/muxer/Mp4Muxer)。[HLS 下载实现](../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JapaneseAsmrHlsDownload.kt#L41)
- **本地文件。** 成品保存到 App 私有目录 `filesDir/japaneseasmr/<文章编号>/japaneseasmr-<曲目编号>/`，并按文章编号、媒体键关联原曲目。播放优先读取可用的本地副本。仅在文章恰好有一个媒体元素时解析章节，线上和离线共用对应跳转时间。生成字幕读取已下载的成品音频。[下载发布](../app/src/main/java/com/nanahoshi/audioplayer/data/DownloadCenter.kt#L866) · [播放文件选择](../app/src/main/java/com/nanahoshi/audioplayer/playback/MediaItemFactory.kt#L40)

### 人工验证与缓存

原生篇目请求使用站点 HTTPS 地址。媒体直链、媒体清单和下级资源使用公网 HTTPS。客户端在请求和每次重定向前校验目标，并仅连接通过校验的解析地址。整部作品下载保留各托管站点的主机限制。

作品详情和剪贴板预览遇到站点验证时，可以点「在 App 内验证」。在内嵌网页通过验证、等页面显示播放器，再点「读取音轨」。App 校验文章编号和已知 RJ，只从当前文章读取公开播放器、章节和作品资料。验证会话由内嵌网页处理，原生页面及媒体客户端不接收网页的验证 Cookie。页面验证、篇目不存在、连接失败、站点错误、无法确认 RJ、页面结构不可用和媒体不可用分别反馈。媒体服务器本身要求验证时，在线播放会报告媒体层错误。[验证页实现](../app/src/main/java/com/nanahoshi/audioplayer/ui/asmr/JapaneseAsmrVerificationScreen.kt#L81) · [验证响应判断](../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JapaneseAsmrClient.kt#L161)

普通文章解析结果和改写后的播放清单缓存约 5 分钟。人工验证后读取的**文章解析结果**按文章编号保存在最多 64 篇的内存缓存中，没有 5 分钟的有效期。只要该条缓存仍在，播放、拖动、切歌和下载解析可复用。App 进程重启、缓存被淘汰、主动「刷新JA篇目」或媒体请求失败后触发的地址重取，都可能再次访问原网页。详情收录查询和首次打开未缓存的文章也会发出新请求。[缓存与失效](../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JapaneseAsmrClient.kt#L120) · [播放时重新解析](../app/src/main/java/com/nanahoshi/audioplayer/playback/BiliPlaybackDataSource.kt#L112)

App 没有固定的人工验证间隔。只有站点返回验证页时才提示验证。用户真机反馈在一次人工验证后，隔夜继续获取音轨和读取网页都未再次遇到验证。已缓存的文章可以直接复用，未缓存的文章或刷新请求在站点放行时同样可以直接读取。再次进入内嵌网页时，是否出现人工操作由站点和当时的网页会话决定。

### 收录查询与篇目刷新

在线作品搜索和翻页只访问 DLsite、asmr.one。打开详情后优先使用库内已知篇目，再按完整 RJ 向 Japanese ASMR 核对。收录查询首页有匹配即停，按照站点节奏读取必要的后续页。读取失败可在详情重试，保存作品使用已知篇目和用户打开的文章。

音轨优先使用有效播放器标题与描述组成的媒体键。缺项或键重复时用首个候选 URL 的路径摘要，保留独立 `pathHash` 用于比对。同步先在整篇按精确媒体键配对，再对剩余条目做一对一路径匹配。匹配后的曲目沿用原媒体键、曲目编号、字幕身份和已有本地路径。有效标题可以更新显示。旧 `t:` 键缺少路径资料时保留，等精确匹配后补写。播放、单曲下载和验证返回共用此匹配规则。队列的路径摘要用于播放定位。显式刷新也用它将未关联曲目编号的队列项与同篇目中唯一匹配的旧音轨关联。

音频库作品页和在线详情都支持显式刷新。只有文章 RJ 相符且播放器列表完整时，才清理确认消失、没有有效本地文件且当前未在播放器选中的曲目。资料不足、有歧义或临时读取失败的曲目保留。清理会移除关联的非当前队列和播放列表条目，并保留字幕和任务记录。播放失败时提示检查网络、重试或刷新篇目。原篇目要求验证时可进入网页验证，媒体服务器要求验证时提示失败原因。

### 整作下载

作品的「下载整部作品」优先从文章读取站内 Download 地址，缺失时使用 `/dlc.php?f={RJ}`。同一作品有多个篇目时先选择一篇。该篇包含不同下载入口时选择一个入口。准备阶段显示可取消的等待框。取消会中止正在读取的请求，并结束本次入队前的准备。首次确认前解析托管文件清单，遇到验证码可用内嵌网页的「读取下载文件」。旧版 MEGA 入口在验证后显示 10 秒跳转提示，此时页面已包含下载地址，提示会显示「已识别下载入口」，无需等文件清单出现。已入队任务在清单解析或实际文件传输遇到验证时标记 `FAILED + VERIFY`，在下载页继续。在当前进程内，Buzzheavier 验证从失败的文件托管页开始。重新打开应用后，仍有效且在排队的整作任务会自动接手。已经开工的任务标为中断，可手动重试。任务持久化所选文章编号和入口摘要，临时地址、验证会话及 MEGA 密钥留在内存。

Buzzheavier 的列表入口通常先跳转到托管文件页。应用解析页面上的「Download File」和「Copy download link」，核对可用的 SHA-256 和长度。按钮使用 `hx-get` 时，点击可能由网页 JS 发出 XHR，响应是 `204 No Content` + `HX-Redirect`，而不是直接文件；App 的监听脚本会在网页会话内按**普通请求**继续跟随该跳转，直到拿到 `audio/mp4` 等文件响应。托管页需要验证时，内建浏览器打开 Buzzheavier 页面；验证完成后重新读取当前失败的文件页，不退回 JA 入口。浏览器确认的文件地址（即使仍是 `/<编号>/download?t=...`）交给原生；原生首次使用普通 GET，确认可读后再用 `Range` 续传。页面提供「Copy download link」时，优先提取其中带 `t` 令牌的地址作为后备。自动点击没有反应时，用户直接在网页里点「Download File」也可被捕获。每次读取后继续打开待处理的文件页，读完后恢复清单解析。托管页返回 503 时显示状态并允许重试。文件请求被拒绝或服务暂不可用时显示下载阶段的错误。网页会话保留在内建浏览器；为了让验证后的原生请求继续通过 Buzzheavier 的会话检查，App 仅在内存中把 WebView 的 Buzzheavier Cookie 和 User-Agent 用于同一托管主机的请求，不写入数据库、日志或任务记录，也不把 JA 站点的 Cookie 传给原生客户端。若最终文件地址仍要求网页会话，显示站点错误，整作下载准备阶段可改用播放器音轨。续传核对强版本信息和 `Content-Range`。

#### Buzzheavier 下载链路结论（2026-11-20 真机确认）

- 「Download File」可能是 `<a hx-get>`；点击产生 XHR。响应可能是 `204 No Content` + `HX-Redirect`，页面自身不一定执行跳转。
- `HX-Redirect`/`Location` 的目标必须按普通文档/文件请求跟随，不能继续携带 `HX-Request`、`HX-Current-URL` 等 HTMX 请求头，否则服务端会继续返回 204 + 跳转，形成循环。
- 跳转目标即使仍是 `/<编号>/download?t=...` 形式，也可能在普通请求下直接返回 `audio/mp4`。不能仅凭 `/download` 路径把它当作中间端点丢弃；浏览器确认非 HTML/音频响应后，应记录为最终文件地址。
- 「Copy download link」通常提供带当前 `t` 的地址，可作为后备入口；`t`、临时地址、Cookie 和 User-Agent 只驻内存，不落库、不写日志。
- 原生 OkHttp 直接请求中间按钮地址可能收到验证/拒绝；浏览器验证完成后，仅对 Buzzheavier 主机在内存中复用 WebView 的 Cookie 和 User-Agent，JA 站点 Cookie 不传给原生客户端。
- 原生对浏览器确认的最终地址首次使用普通 GET（探测不带 `Range`），确认可读后再用 `Range` 续传；探测成功后缓存最终地址，避免反复消耗一次性令牌。
- 验证失败时重新打开当前失败的 Buzzheavier 文件页，而不是 JA `/dlc.php` 入口；清单读取最多重试 3 轮，超过后提示改用播放器音轨。
- 进入托管页后自动依次读取待处理的文件页，浏览器保持打开；只有真正出现验证时才需要用户操作，不要求逐页点「读取下载文件」。

MEGA 支持公开单文件分享，解析属性、按字节偏移进行 CTR 解密，并校验长度及 MAC。要求网页会话时显示站点错误。ZIP 和 RAR 可解压，支持 RAR 分卷。包内嵌套文件保留供浏览。MEGA 文件夹、加密包和 7z 会提示限制。

下载结果按 `(rj, relativePath)` 记录在整作文件数据库清单，作品内统一显示在「下载文件」。目录、曲目、非音频文件以及包版本在同一事务发布。发布失败时保留此前版本。重下先读取远端清单，校验大小和可用摘要后只补必要文件。已完整发布的包版本在解压文件齐全时复用。路径有变化且改选入口时可保留旧文件，拒绝替换的内容下次仍会检查。清单中已消失的旧文件保留。单曲本地副本删除后清单和曲目保留。整作文件的字幕和翻译使用稳定的 `japaneseasmr://file?rj={rj}&path={relativePath}` 身份。

源码见[文件入口与传输](../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JaWorkClient.kt)、[清单与发布](../app/src/main/java/com/nanahoshi/audioplayer/data/JaWorkFiles.kt)和[音轨匹配](../app/src/main/java/com/nanahoshi/audioplayer/japaneseasmr/JaMediaMatches.kt)。

### 真机观察

Realme RMX5010 上，用户确认在 App 内验证并读取音轨后可以原生在线播放和下载。一条直链下载首轮约 368 秒收到了 20.1 MiB，平均约 56 KiB/s，随后任务失败。重新下载时的短时采样约 87 KiB/s。一条分段下载在约 29 秒收到约 1.45 MB 后中止。另一次在线播放分段音频时没有并行下载，拖动进度后约 20 秒的接收增量折合约 39 KiB/s。该值仅表示 App 层网络接收速率，各次测量的传输阶段和采样窗口分别记录。

## 10. 网页 Download 入口与模拟器验证

两个页面样式的 Download 都可进入站点的 `/dlc.php?f=<RJ作品号>`。它的结果随作品而异。整作下载以实际文件列表为准，单曲下载仍从播放器获取音频。

### 新版页面与 Buzzheavier

新版样例《理容室こころ》（`RJ01752865`）在两个播放器选项旁显示 **Download**。点开后再点 **Download RJ01752865**，页面列出 5 条分轨 M4A 和 1 个以作品号命名的 M4A。文件由 Buzzheavier 托管。Safari 实测下载第一轨成功，下载列表显示 21.7 MB。作品号命名的文件只确认了列表项，内容未单独检查。[新版作品样例](https://japaneseasmr.com/152156/)

Android 16 模拟器上的独立原生探针验证了以下结果。

- 无网页验证会话时，JA 的 `/dlc.php` 返回 403 验证页。已从网页得到的 Buzzheavier 文件入口可独立访问。样例先从 `ts.buzzheavier.com` 跳到托管文件页。文件页通过「复制下载链接」提供 `/<文件编号>/download?t=<页面当前值>` 形式的地址。该段请求不使用浏览器会话。
- 下载请求跟随托管方的 308 跳转后收到 `audio/mp4`。第一轨完整下载 21,713,362 字节，文件校验值与托管页公布的一致。从发起该文件下载请求到收完约 3.1 秒，计入该请求的跳转和响应时间。
- 请求第 1 MiB 起的下一段时返回 206，范围和文件总长度正确。响应内容与完整文件对应片段一致，验证了按指定偏移续读的能力。

整作下载按网页文件列表保存整段文件和分轨文件。上述测量来自独立探针，App 的文件清单、续传和发布另外使用本地样例覆盖。

### 旧版页面与 MEGA

旧版样例《佐渡島物語》（`RJ202757`）需在 **Select Source** 选择 **Download**，页面才显示指向 `/dlc.php` 的下载链接。更早测试的《OshigotoNeiro ~Streamer~》（`RJ366031`）的入口曾显示 10 秒跳转页，用户核对该次对应的 MEGA 文件已失效。模拟器直接请求 `RJ202757` 时，文章页返回 200，`/dlc.php` 返回 403 验证页。模拟器能独立访问 MEGA 首页，该次检查仍未取得作品分享链接。[旧版作品样例](https://japaneseasmr.com/606/) · [早期跳转样例](https://japaneseasmr.com/58186/)

随后用用户确认有效的 `RJ202757` MEGA 分享链接，在同一 Android 16 模拟器使用独立原生探针完整下载 `RJ202757.mp3`。探针获取 MEGA 文件信息和传输地址，在本机解密，并核对文件完整性。文件大小为 97,342,905 字节，下载、解密和写盘共约 42.3 秒，平均约 2.3 MB/s。Android 媒体读取器读到了 MP3 音频帧，识别时长约 1 小时 7 分 36 秒。另从 128 KiB 偏移单独读取并解密的片段与前一次读取的数据一致。

MEGA 的测试从有效分享链接开始。App 使用 Kotlin 实现公开单文件协议和校验，协议参考见 MEGA 官方 SDK。旧链接失效时仍需站点提供有效文件。两个托管入口和真机播放器线路的测量用了不同样例或设备，同一音轨在相同条件下的提速比例仍待验证。[MEGA 官方 SDK](https://github.com/meganz/sdk)

网页验证 Cookie、MEGA 分享密钥及托管方临时下载地址不得写入日志、文档或任务记录。Android 当前已实现的范围见第 9 节。原有播放器接入范围记录于[接入实施计划](plans/2026-11-18-japanese-asmr-integration.md)。

## 11. 最小复查方式

以下标准库脚本可复查单个入口的状态及响应类型，不输出正文、Cookie 或媒体地址。它使用系统默认网络设置；若需代理，应在运行环境中配置，不把账号或代理凭证写入文档。

```python
import urllib.error
import urllib.request

url = "https://japaneseasmr.com/wp-json/"
request = urllib.request.Request(
    url,
    headers={"User-Agent": "Mozilla/5.0", "Accept": "*/*"},
)
try:
    response = urllib.request.urlopen(request, timeout=20)
except urllib.error.HTTPError as error:
    response = error

with response:
    body = response.read(1_000_000)
    print(response.status, response.headers.get("Content-Type"), len(body))
```

首次记录的输出为 `401 application/json; charset=UTF-8 96`。复查时按第 7 节的间隔逐个请求，以当次响应为准。
