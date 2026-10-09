# asmr.one 接口调用说明

给后续在播放器里接 asmr.one 用。本文只记**读作品、读音轨、拿音频地址**这条链路。登录、收藏夹、评论没有出现在下面两个客户端的请求里，也没有在这里实测，先不要照着猜。

对照来源：

- 下载器 [takoyune/asmr.one-downloader](https://github.com/takoyune/asmr.one-downloader)（`main/network.py`、`main/constants.py`、`main/app.py`、`main/ui/search.py`）
- 聊天插件 [YuzuharaYuka/koishi-plugin-asmrone](https://github.com/YuzuharaYuka/koishi-plugin-asmrone)（`src/services/api.ts`、`src/commands/handler.ts`、`src/common/types.ts`）
- 2026-09-29 对 `https://api.asmr-200.com` 的只读请求。站点页面上写着它基于 Kikoeru（一个在线听音声的网页壳）。当天站点自己的前端脚本下不下来，所以下面没有从网页包里再挖接口。

这些接口返回的作品里有大量成人向内容。调用时按自己的使用场合处理。

## 名词对照

后文第一次出现的难词，括号里是人话。文末还有一份同样的清单。

- API（网站给程序用的取数入口，不是给人点的网页）
- JSON（一种把字段和值写成文本的格式，程序直接读）
- GET / POST（两种要数据的方式：GET 把条件写在网址上；POST 把条件放在请求正文里）
- query（网址问号后面的条件，如 `page=1`）
- CDN（专门发图片、音频文件的机器，和上面那个取目录的 API 不是同一台）

## 基址和请求头

四个镜像（同一套接口的备用网址，一个打不开就换下一个），下载器按这个顺序试：

| 顺序 | 基址 |
| --- | --- |
| 1 | `https://api.asmr-200.com` |
| 2 | `https://api.asmr.one` |
| 3 | `https://api.asmr-100.com` |
| 4 | `https://api.asmr-300.com` |

插件默认只用第一个，配置项 `apiBaseUrl` 默认是 `https://api.asmr-200.com/api`（基址已经带了 `/api`）。下面的路径一律再加 `/api/...`。2026-09-29 四个基址的 `GET /api/workInfo/{数字 id}` 都返回了同一条作品。

下载器每次请求带这些头：

```http
User-Agent: <一套常见浏览器标识，随机换 Chrome / Firefox>
Referer: https://asmr.one/
Origin: https://asmr.one
Accept: application/json
```

插件只固定了 Chrome 130 的 `User-Agent`。读目录接口在实测里带上 `Referer` 和 `Origin` 即可。

下载器在每次成功的 API 请求后再等大约 0.3 秒。遇到 HTTP 429（对方说你要得太勤）就拉长等待再试。参数不合法时返回 400，正文是：

```json
{
  "errors": [
    { "value": "...", "msg": "Invalid value", "param": "order", "location": "query" }
  ],
  "error": "order: Invalid value"
}
```

作品不存在时 `workInfo` 和 `tracks` 都是 404，正文 `{"error":"未找到该音声"}`。

## 作品编号

同一条作品有两套号，不要混用：

| 字段 | 例子形状 | 用在哪 |
| --- | --- | --- |
| `id` | 整数，如 `243448` | `GET /api/tracks/{id}` 必须用这个，或它左边补零后的纯数字 |
| `source_id` | `RJ` 或 `VJ` 加数字，如 `RJ243448` | 给人看、拼网页 `https://asmr.one/work/RJ243448`。`workInfo` 也接受它 |

2026-09-29 实测，下面三种写法的 `workInfo` 都指向同一条作品：`243448`、`00243448`、`RJ243448`。

`tracks` 只接受数字。`/api/tracks/00243448` 可用；`/api/tracks/RJ243448` 返回 400，`param` 是 `id`。

两个客户端的习惯：

- 插件把用户输入收成 `RJ` + 至少 8 位数字，再去掉 `RJ`，用剩下的数字同时去调 `workInfo` 和 `tracks`。
- 下载器的主流程把 `RJ`/`VJ` 原样交给 `workInfo`，再拿返回 JSON 里的 `id` 去调 `tracks`。`network.py` 里另有一条旧路径会先剥掉 `RJ`/`VJ` 和前导 0。

后续播放器建议跟下载器主流程：先 `workInfo`，再用返回的 `id` 要音轨。

## 1. 搜索作品

两条路径返回的形状一样。关键词要做 URL 编码（把空格、`$`、中文变成 `%xx` 再放进网址）。

```http
GET /api/search/{关键词}?order=dl_count&sort=desc&page=1&pageSize=20&subtitle=0&includeTranslationWorks=true
GET /api/works?keyword={关键词}&order=dl_count&sort=desc&page=1&pageSize=20&subtitle=0
```

关键词可以是普通词，也可以是下面的筛选片段，用空格拼在一起。筛选片段两边各有一个 `$`：

| 片段 | 含义（按插件说明） | 2026-09-29 |
| --- | --- | --- |
| `$tag:舔耳$` | 必须带这个标签 | `$tag:ASMR$` 返回 200 |
| `$-tag:男性向け$` | 排除这个标签 | `$-tag:ASMR$` 返回 200 |
| `$va:名字$` | 声优 | 插件支持；本次未单测有结果的名字 |
| `$circle:社团名$` | 社团 | `$circle:test$` 返回 200，总数为 0 |
| `$rate:4.5$` | 评分大于等于 | 返回 200 |
| `$sell:1000$` | 销量大于等于 | 返回 200 |
| `$price:1000$` | 价格（日元）大于等于 | 返回 200 |
| `$duration:3600$` | 时长（秒）大于等于 | 返回 200 |
| `$age:general$` / `$age:r15$` / `$age:adult$` | 年龄分级 | 三个都返回 200，总数各不相同 |
| `$lang:JPN$` / `$lang:CHI_HANS$` / `$lang:ENG$` | 语言 | `JPN`、`CHI_HANS` 已实测；`ENG` 只在插件说明里 |

例子：搜「山田」，只要标签「舔耳」，按发售日从新到旧，第 2 页。

```http
GET /api/search/山田%20%24tag%3A%E8%88%94%E8%80%B3%24?order=release&sort=desc&page=2&pageSize=20&subtitle=0
```

### 排序

`order` 和 `sort` 分开传。`sort` 只有 `desc`（从大到小）和 `asc`（从小到大）。

| 插件里的中文 | `order` | `sort` |
| --- | --- | --- |
| 发售日 | `release` | `desc` |
| 最新收录 | `create_date` | `desc` |
| 发售日-正序 | `release` | `asc` |
| 销量（插件默认） | `dl_count` | `desc` |
| 价格-正序 | `price` | `asc` |
| 价格 | `price` | `desc` |
| 评分 | `rate_average_2dp` | `desc` |
| 评价数 | `review_count` | `desc` |
| RJ号 | `id` | `desc` |
| RJ号-正序 | `id` | `asc` |
| 随机 | `random` | `desc` |

上面这些 `order` 值在 2026-09-29 都返回了 200。

下载器搜索界面写成 `order=dl_count:desc` 这一个参数。同一天实测会被 400 拒绝，错误是 `order: Invalid value`。`/api/works` 同样要拆成 `order` 和 `sort`。

### 其它 query

| 参数 | 说明 |
| --- | --- |
| `page` | 从 1 开始 |
| `pageSize` | 插件限制 1–40，默认 10。实测 `50` 仍按 50 条返回，服务器上限没有写在这两个项目里 |
| `subtitle` | `0` 不限字幕，`1` 只要有字幕。实测两者总数不同。传 `true` 会 400 |
| `includeTranslationWorks` | 插件在 `/api/search` 上固定传 `true`。实测 `false` 也被接受。它会不会改结果集，这次没有对比 |
| `hasSubtitle` | 只有下载器的 `/api/works` 在「只要字幕」时额外传 `1`。本次没有单独验证 |
| `LowPrice` / `HighPrice` | 下载器按价格区间筛选时传。本次没有单独验证 |
| `type` | 下载器有这个参数，可选值没有在代码里写死。本次没有单独验证 |

关键词传一个空格时，接口会给出很宽的列表（实测总数约 6 万）。适合当「全部作品」的入口。

### 返回

```json
{
  "works": [ { "id": 0 } ],
  "pagination": {
    "currentPage": 1,
    "pageSize": 20,
    "totalCount": 0
  }
}
```

`works` 为空且 `totalCount` 为 0，就是没有结果。热门列表的 `totalCount` 实测是 100，不是全站作品数。

## 2. 热门

```http
POST /api/recommender/popular
Content-Type: application/json

{
  "keyword": " ",
  "page": 1,
  "pageSize": 20,
  "subtitle": 0,
  "localSubtitledWorks": [],
  "withPlaylistStatus": []
}
```

返回和搜索一样，是 `works` + `pagination`。插件默认每页条数跟搜索共用。这个接口不接受自定义排序。

## 3. 作品详情

```http
GET /api/workInfo/{id 或 source_id}
```

搜索结果里的单条作品，和 `workInfo` 的字段几乎相同。实测 `workInfo` 少了 `playlistStatus` 和 `userRating`。已经有搜索结果时，可以先用那一条；要封面、社团、标签的完整对象时再调详情。

字段（2026-09-29 一条真实返回里见到的名字；示例值是占位，不是某一条作品）：

| 字段 | 类型 | 怎么用 |
| --- | --- | --- |
| `id` | 整数 | 接着请求音轨 |
| `source_id` | 字符串 | `RJ`/`VJ` 编号 |
| `source_type` | 字符串 | 实测见过 `DLSITE` |
| `source_url` | 字符串 | 原始商品页链接 |
| `title` | 字符串 | 标题 |
| `name` | 字符串 | 社团名。和 `circle.name` 是同一层意思 |
| `circle_id` | 整数 | 社团编号 |
| `circle` | 对象 | `id`、`name`、`source_id`、`source_type` |
| `nsfw` | 布尔 | 是否成人向 |
| `age_category_string` | 字符串 | 实测见过 `general` |
| `release` | 字符串 | 发售日。下载器读的是 `release_date`，详情 JSON 里的名字是 `release` |
| `create_date` | 字符串 | 收录时间 |
| `duration` | 整数 | 整部作品时长，单位秒 |
| `dl_count` | 整数 | 销量 |
| `price` | 整数 | 价格，日元 |
| `review_count` | 整数 | 评价条数 |
| `rate_count` | 整数 | 打分人数 |
| `rate_average_2dp` | 小数 | 平均分，两位小数 |
| `rate_count_detail` | 数组 | 每一项有 `review_point`、`count`、`ratio` |
| `rank` | 整数或空 | 排名，可以是空 |
| `has_subtitle` | 布尔 | 有没有字幕 |
| `vas` | 数组 | 声优。每一项 `id`（字符串）、`name` |
| `tags` | 数组 | 见下面 |
| `mainCoverUrl` | 字符串 | 大封面，绝对地址 |
| `samCoverUrl` | 字符串 | 另一张封面 |
| `thumbnailCoverUrl` | 字符串 | 小图 |
| `work_attributes` | 字符串 | 一串用逗号分开的标记，原样保留即可 |
| `language_editions` | 数组 | 其它语言版本 |
| `original_workno` | 字符串或空 | 原作品编号 |
| `other_language_editions_in_db` | 数组 | 库里的其它语言版 |
| `translation_info` | 对象 | 是否翻译版、父作品编号等。字段名见插件类型和实测：`lang`、`is_child`、`is_parent`、`is_original`、`is_volunteer`、`child_worknos`、`parent_workno`、`original_workno`、`is_translation_agree`、`translation_bonus_langs`、`is_translation_bonus_child` |
| `userRating` | 空或数字 | 只在搜索结果里见到，未登录时是空 |
| `playlistStatus` | 对象 | 只在搜索结果里见到 |

标签 `tags[]`：

| 字段 | 说明 |
| --- | --- |
| `id` | 整数 |
| `name` | 当前语言下的名字 |
| `i18n` | 按语言再给一遍名字。键见过 `ja-jp`、`en-us`、`zh-cn`，里面是 `{ "name": "..." }`。`zh-cn` 还可能有 `history` 数组 |
| `upvote` / `downvote` / `voteRank` / `voteStatus` | 标签投票。未登录时 `voteStatus` 可以是空值 |

下载器显示标签时按配置的语言顺序取 `i18n`，默认优先 `ja-jp`，其次 `en-us`、`zh-cn`，都没有再用 `name`。

社团名同样有两种形状：有的返回是 `circle: { "name": "..." }`，有的只有顶层 `name`。下载器两种都读。

## 4. 音轨树

```http
GET /api/tracks/{数字 id}?v=2
```

`v=2` 是下载器加的。实测不带 `v` 时，同一条作品的字段名相同。返回是数组，不是包在 `works` 里。

文件夹节点：

```json
{ "type": "folder", "title": "mp3", "children": [] }
```

文件节点（实测到的 `audio`；插件还会遇到 `image`、`text`、`video`，并按扩展名再分一类字幕、文档）：

| 字段 | 说明 |
| --- | --- |
| `type` | `audio` 等 |
| `title` | 文件名，常带扩展名 |
| `duration` | 秒。文件夹没有这个字段 |
| `size` | 字节数 |
| `hash` | 文件校验用的字符串 |
| `mediaDownloadUrl` | 下载用的绝对地址 |
| `mediaStreamUrl` | 在线播放用的绝对地址。本次抽到的一条和下载地址是同一个文件 |
| `streamLowQualityUrl` | 更小的播放地址。本次这条是空字符串 |
| `work` | `{ "id", "source_id", "source_type" }` |
| `workTitle` | 作品标题 |

播放或下载时用 `mediaStreamUrl`，它为空再用 `mediaDownloadUrl`。这两个地址的主机是 CDN，不是上面四个 API 镜像。下载器只在主机属于那四个 API 域名时才换镜像，不会把 CDN 地址改写成 API 地址。

2026-09-29 对一条 mp3 发了 `Range: bytes=0-15`（只要文件开头 16 字节）：

- 返回 206，`Content-Type: audio/mpeg`，`Content-Range: bytes 0-15/<总字节>`
- 带不带 `Referer: https://asmr.one/` 这条地址都返回了这 16 字节

所以这条链路可以用分段请求做拖动播放。其它 CDN 主机是否同样放行，没有逐个测。

插件把一棵树摊平时，把 `type == audio` 或扩展名是 `mp3`、`flac`、`wav`、`m4a`、`ogg`、`aac` 的节点当成音轨。文件夹本身不能播。

## 建议的调用顺序

1. `GET /api/search/{关键词}` 或 `POST /api/recommender/popular`，拿到 `works[].id`。
2. 需要详情时 `GET /api/workInfo/{id}`。列表里的字段够用就可以跳过。
3. `GET /api/tracks/{id}?v=2`，深度优先走进 `children`，收集 `type == audio` 的节点。
4. 用 `mediaStreamUrl` 播放。请求带 `Range`，就能从中间开始播。
5. 封面直接用 `mainCoverUrl` 或 `thumbnailCoverUrl`，它们已经是完整图片地址。

读这三步不需要登录令牌，也不需要 Cookie（浏览器登录后留下的一串身份）。

## 还没写进本文的部分

- 登录、用户播放列表、评分提交。两个客户端的请求代码里没有。
- 下载器 `search.py` 里的 `hasSubtitle`、`type`、`LowPrice`、`HighPrice` 只记了参数名，没有逐个验证取值。
- `includeTranslationWorks` 对结果集的具体影响。
- `order=随机` 以外，有没有别的排序字段。
- 站点前端脚本里可能还有接口。2026-09-29 下载 `https://asmr.one/js/app.*.js` 时连接被重置，没有解析。

## 名词解释

- **API**：程序向网站要数据用的入口。人打开的是网页，程序打开的是这里的网址。
- **JSON**：把「标题、时长、地址」写成一段文本，程序能按字段名取出来。
- **GET**：条件写在网址里的要数据方式。
- **POST**：条件放在请求正文里的要数据方式。
- **query**：网址 `?` 后面那一串条件。
- **CDN**：专门把音频、图片发给你的机器。它和提供作品目录的那几个 API 网址不是同一个地方。
- **Range**：告诉对方「我只要文件的第几字节到第几字节」，用来从中间开始播，而不必先下完整首。
- **Cookie**：浏览器登录后保存的一串身份。本文这条读目录、读音轨的链路不用它。
