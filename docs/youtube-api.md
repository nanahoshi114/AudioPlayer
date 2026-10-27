# YouTube 接口说明

播放器目前不请求 YouTube。本文记录 2026-10-27 的只读调查：怎样从一条公开视频得到标题、作者、时长和搜索结果，以及要音轨文件时接口实际返回了什么。

App 里没有 YouTube 的导入、搜索、播放或下载入口，`app/src/main` 中也没有相关代码。本文只作为以后接入时的资料，正文的请求都不代表播放器已经在调用。

没有登录，也没有发送 cookie。响应里没有音轨地址。公开客户端还提到一种播放器令牌，拿媒体文件之前可能要带上。这次没有生成、也没有发送这种令牌，本文不写它怎么生成。

对照来源：

- 公开客户端 [yt-dlp](https://github.com/yt-dlp/yt-dlp) 当天 `master` 上的 `yt_dlp/extractor/youtube/_base.py`、`yt_dlp/extractor/youtube/_video.py`
- 2026-10-27 对公开视频 `jNQXAC9IVRw`（《Me at the zoo》）的只读请求
- 官方说明 [Videos: list](https://developers.google.com/youtube/v3/docs/videos/list)，页面写着更新于 2026-10-15

下面凡是这次请求里看见的，标「实测」。只在客户端源码里看见、这次没亲自打到返回正文的，标「客户端」。没见过的取值不编。

## 视频编号

实测，这条视频的编号是 `jNQXAC9IVRw`，11 个字符。

| 地址 | 实测 |
| --- | --- |
| `https://www.youtube.com/watch?v=jNQXAC9IVRw` | 200，观看页 |
| `https://youtu.be/jNQXAC9IVRw` | 303，跳到 `https://www.youtube.com/watch?v=jNQXAC9IVRw&feature=youtu.be` |
| `https://www.youtube.com/shorts/jNQXAC9IVRw` | 303，跳到 `https://www.youtube.com/watch?v=jNQXAC9IVRw` |
| `https://www.youtube.com/embed/jNQXAC9IVRw` | 200，没有跳转 |
| `https://music.youtube.com/watch?v=jNQXAC9IVRw` | 200，没有跳转 |

## 观看页

实测。桌面浏览器标识请求 `GET https://www.youtube.com/watch?v=jNQXAC9IVRw&hl=en`，HTTP 200。

页面脚本里有这些配置名：

| 名字 | 这次看见的 |
| --- | --- |
| `INNERTUBE_API_KEY` | 有，39 个字符，用在后面的 `/youtubei/v1/` |
| `INNERTUBE_CLIENT_VERSION` | `2.20261026.01.00` |
| `INNERTUBE_CLIENT_NAME` | `WEB` |
| `VISITOR_DATA` | 有。它跟这次访问绑在一起，不要写进日志或文档 |

同一页里的 `ytInitialPlayerResponse` 这次不是可播放的数据。`playabilityStatus.status` 为 `LOGIN_REQUIRED`，`reason` 为 `Sign in to confirm you’re not a bot`。没有 `videoDetails`，也没有 `streamingData`。`<title>` 只有 ` - YouTube`，没有 `og:title`。

客户端把同时存在 `LOGIN_INFO` 和 `SAPISID`（或 `__Secure-1PAPISID`、`__Secure-3PAPISID`）当成已登录。这次没带这些 cookie。

`INNERTUBE_API_KEY` 和 `INNERTUBE_CLIENT_VERSION` 写在观看页里，会变。调用时从当次页面读，不要把某一次的值写死。yt-dlp 源码里的网页客户端版本还是 `2.20260708.00.00`，比这次页面上的旧。

## oEmbed

实测，不需要密钥，HTTP 200，正文是 JSON。

```http
GET https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=jNQXAC9IVRw&format=json
```

这次有的字段：`title`、`author_name`、`author_url`、`type`、`provider_name`、`provider_url`、`thumbnail_url`、`thumbnail_width`、`thumbnail_height`、`width`、`height`、`version`、`html`。

`title` 是 `Me at the zoo`，`author_name` 是 `jawed`，`type` 是 `video`。`thumbnail_url` 指向 `i.ytimg.com`。`html` 是一段嵌入用的 iframe，不是音轨地址。

## 播放

实测。各客户端都是：

```http
POST https://www.youtube.com/youtubei/v1/player?prettyPrint=false&key={INNERTUBE_API_KEY}
Content-Type: application/json
Origin: https://www.youtube.com
```

正文至少包括：

```json
{
  "videoId": "jNQXAC9IVRw",
  "context": {
    "client": {
      "clientName": "WEB",
      "clientVersion": "2.20261026.01.00",
      "hl": "en",
      "gl": "US"
    }
  },
  "contentCheckOk": true,
  "racyCheckOk": true
}
```

`WEB` 那次还带了观看页里的 `visitorData`。`contentCheckOk` 和 `racyCheckOk` 是客户端在请求播放时带上的两个布尔值。全部 HTTP 200，但都没有 `streamingData`。

| `clientName` | `clientVersion` 来源 | `playabilityStatus.status` | `reason` | 有没有 `videoDetails` |
| --- | --- | --- | --- | --- |
| `WEB` | 当天观看页 | `LOGIN_REQUIRED` | Sign in to confirm you’re not a bot | 无 |
| `MWEB` | 当天观看页 | `LOGIN_REQUIRED` | 同上 | 无 |
| `WEB_EMBEDDED_PLAYER` | 当天观看页 | `ERROR` | This video is unavailable | 无 |
| `TVHTML5` | yt-dlp 源码里的 `7.20260707.07.00` | `LOGIN_REQUIRED` | Sign in to confirm you’re not a bot | 无 |
| `TVHTML5_SIMPLY` | yt-dlp 源码里的 `1.0` | `UNPLAYABLE` | Sign in to confirm you’re not a bot | 有 |
| `ANDROID` | yt-dlp 源码里的 `21.26.364` | `LOGIN_REQUIRED` | Sign in to confirm you’re not a bot | 无 |
| `ANDROID_VR` | yt-dlp 源码里的 `1.65.10` | `LOGIN_REQUIRED` | Sign in to confirm you’re not a bot | 无 |
| `IOS` | yt-dlp 源码里的 `21.26.4` | `LOGIN_REQUIRED` | Sign in to confirm you’re not a bot | 无 |

`WEB` 被拒绝时，顶层有 `playabilityStatus`、`responseContext`、`trackingParams`、`adBreakHeartbeatParams`、`frameworkUpdates`。`playabilityStatus` 里有 `status`、`reason`、`contextParams`、`errorScreen`、`skip`。`errorScreen` 只有 `playerErrorMessageRenderer`，里面有 `reason`、`subreason`、`thumbnail`、`icon`、`proceedButton`。

`TVHTML5_SIMPLY` 虽然不能播，但 `videoDetails` 这次有这些字段：`videoId`、`title`、`author`、`channelId`、`lengthSeconds`、`viewCount`、`shortDescription`、`thumbnail`、`keywords`、`allowRatings`、`isLiveContent`、`isPrivate`、`isOwnerViewing`、`isCrawlable`、`isTvfilmVideo`、`isUnpluggedCorpus`。

这次的值：`title` 为 `Me at the zoo`，`author` 为 `jawed`，`channelId` 为 `UC4QobU6STFB0P71PMvOGN5A`，`lengthSeconds` 为 `19`，`viewCount` 为 `446749333`，`isLiveContent` 为 false，`isPrivate` 为 false。`thumbnail.thumbnails` 是一组图片，主机名都是 `i.ytimg.com`，看见的尺寸有 120×90、320×180、444×250、480×360。

### 音轨字段

客户端在拿到 `streamingData` 时，从 `formats` 和 `adaptiveFormats` 里读每一条。它使用的字段名有 `itag`、`mimeType`、`bitrate`、`averageBitrate`、`audioQuality`、`audioSampleRate`、`url`、`signatureCipher`、`contentLength`、`initRange`、`indexRange`。音频是 `mimeType` 以 `audio/` 开头的那些条。`signatureCipher` 出现时，地址不在 `url` 里，还要观看页引用的播放器脚本才能还原。

这次所有播放响应都没有 `streamingData`，所以上面这些字段没有核对到实例。yt-dlp 还写明，不少客户端即使返回了 `url`，取文件时仍可能要求那个播放器令牌；没有它，文件请求会 403。本文不写这个令牌怎么生成。

## 搜索

实测，HTTP 200。

```http
POST https://www.youtube.com/youtubei/v1/search?prettyPrint=false&key={INNERTUBE_API_KEY}
Content-Type: application/json
```

```json
{
  "context": {
    "client": {
      "clientName": "WEB",
      "clientVersion": "2.20261026.01.00",
      "hl": "en",
      "gl": "US"
    }
  },
  "query": "me at the zoo"
}
```

顶层有 `responseContext`、`estimatedResults`、`contents`、`trackingParams`、`topbar`、`targetId`。关键词 `me at the zoo` 的结果里有 8 处 `videoRenderer`，没有 `playlistRenderer`，也没有 `channelRenderer`。

第一条 `videoRenderer` 的 `videoId` 是 `jNQXAC9IVRw`。这条上能读到文字的字段：

| 字段 | 这次的文字 |
| --- | --- |
| `title` | Me at the zoo |
| `ownerText` | jawed |
| `lengthText` | 0:19 |
| `viewCountText` | 446,749,333 views |
| `publishedTimeText` | 21 years ago |

文字在 `simpleText`，或者在 `runs` 数组里逐段的 `text`。同一条还有 `longBylineText`、`shortBylineText`、`shortViewCountText`、`thumbnail`、`navigationEndpoint`、`publishedTimeText`。`lengthText` 是给人看的时长，不是秒数。

## 视频页补充数据

实测。`POST https://www.youtube.com/youtubei/v1/next`，正文是 `context` 加 `videoId`。HTTP 200。

顶层有 `contents`、`currentVideoEndpoint`、`engagementPanels`、`frameworkUpdates`、`microformat`、`onResponseReceivedEndpoints`、`pageVisualEffects`、`playerOverlays`、`responseContext`、`topbar`、`trackingParams`。正文里能找到 `videoPrimaryInfoRenderer`、`videoSecondaryInfoRenderer`，以及标题 `Me at the zoo`。这次没有继续拆这些渲染块里的字段。

## 缩略图

实测，不需要密钥。

```http
GET https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg
```

HTTP 200，`Content-Type` 为 `image/jpeg`。oEmbed 的 `thumbnail_url` 和 `videoDetails.thumbnail` 也在 `i.ytimg.com`。

## 字幕

实测，下面两条都是 HTTP 200，`Content-Type` 为 `text/html`，正文长度为 0，没有字幕列表，也没有一句台词：

```http
GET https://www.youtube.com/api/timedtext?type=list&v=jNQXAC9IVRw
GET https://www.youtube.com/api/timedtext?v=jNQXAC9IVRw&lang=en&fmt=json3
```

客户端在播放响应里读取 `captions.playerCaptionsTracklistRenderer.captionTracks`。每条有 `baseUrl`、语言代码，自动生成的字幕带 `kind`。它还从 `translationLanguages` 读可翻译的语言。这次的播放响应里没有 `captions`，所以这些字段没有核对到实例。

## 官方 Data API

这是另一套接口，主机是 `www.googleapis.com`，不使用上面的 `INNERTUBE_API_KEY`。

实测，不带任何密钥：

```http
GET https://www.googleapis.com/youtube/v3/videos?part=snippet&id=jNQXAC9IVRw
```

HTTP 403，正文是 JSON。`error.code` 为 403，`error.message` 为 `Method doesn't allow unregistered callers (callers without established identity). Please use API Key or other form of API consumer identity to call this API.`

官方页面写的成功调用是：

```http
GET https://www.googleapis.com/youtube/v3/videos?part={part}&id={视频编号}&key={自己的密钥}
```

`part` 可以包含 `snippet`、`contentDetails`、`statistics`、`status`、`player` 等。`player` 对应的是嵌入播放器，参数说明里把它放在 `player.embedHtml`。这次没有密钥，没有看到成功正文，也就没有看见文件地址。官方页面列出的返回结构是 `youtube#videoListResponse`，里面是 `items` 数组，没有音轨文件字段。
