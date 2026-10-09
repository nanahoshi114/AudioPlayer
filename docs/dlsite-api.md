# DLsite 接口说明

给以后对照商店搜索、商品页、登录，以及**自己已经买过的作品**怎么播放、怎么下载。播放器现在的在线列表仍走 asmr.one，还不直接请求这里。

没买的作品没有播放地址。本文不写绕过付费，也不写把被打乱的图片再拼回去的做法。

对照来源：

- 公开客户端 [dlsite-async](https://github.com/bhrevol/dlsite-async)（登录、商品、已购列表、Play 文件）
- 公开客户端 [dlsite](https://docs.rs/dlsite)（商店搜索的路径怎么拼）
- 2026-10-07 对 `RJ243448` 的 `product.json` 只读请求
- 2026-10-08 对商店搜索、登录页，以及未登录时的 Play 地址做的只读请求

下面凡是这次请求里看见的，标「实测」。只在客户端源码里看见、这次没亲自打到返回正文的，标「客户端」。没见过的取值不编。

## 名词对照

- API（网站给程序用的取数入口，不是给人点的网页）
- JSON（一种把字段和值写成文本的格式，程序直接读）
- GET / POST（两种要数据的方式：GET 把条件写在网址上；POST 把条件放在请求正文里）
- HTML（人打开网页时看到的那一页排版文本）
- cookie（网站让浏览器记住的一小段通行证。登录成功后，后面的请求要带上它）
- Referer（请求头里写「我从哪一页过来」，商店用来认是不是自己的页面在要数据）

## 站点和请求头

商店按内容分站点。路径中间的那一段就是站点名：

| 站点 | 页面 |
| --- | --- |
| `home` | 全年龄同人 |
| `maniax` | 成人同人 |
| `books` | 成人漫画 |
| `soft` | 全年龄游戏 |
| `pro` | 商业美少女游戏 |
| `appx` | 应用 |
| `girls` | 女性向成人 |
| `comic` | Comipo 漫画。客户端也认 `comipo.app` |

读成人页时，客户端会带 cookie `adultchecked=1`。这只表示「年龄确认过了」，不能代替购买。

常见请求头：

```http
User-Agent: <一套常见浏览器标识>
Accept: application/json
Referer: https://www.dlsite.com/{站点}/
```

去 Play（网页里直接听、直接看已购作品的那套接口）时，`Referer` 改成 `https://play.dlsite.com/`。

## 1. 商店搜索

实测，2026-10-08。关键词 `ASMR`、类型收成音声，HTTP 200，正文是 JSON。

```http
GET https://www.dlsite.com/{站点}/fsr/ajax/=/language/jp/keyword/ASMR/order/trend/work_type_category[0]/movie_audio/per_page/2/page/1
```

条件不写在问号后面，而是一段一段接在 `/=/` 后面：`名字/值/名字/值`。同一类要选多项时，名字带序号：`genre[0]/497/genre[1]/123`。关键词里的空格和非英文要先做网址编码。

这次返回的顶层有 `search_result`。它不是一条条 JSON 作品，而是一整段 HTML。作品卡片在 `#search_result_img_box > li` 里面。公开客户端再从这段 HTML 里取出：

| 卡片上的位置 | 内容 |
| --- | --- |
| `data-product_id` 或 `data-list_item_product_id` | RJ 号 |
| `.work_name a` 的 `title` | 标题 |
| `.maker_name a` | 社团名。链接最后一段、去掉 `.html` 就是社团编号 |
| `.author` | 作者或声优。没有这一格就是没有 |
| `.work_price` | 价格。划掉的旧价和现价可能同时在 |
| `.work_genre span` 的 `title` | 年龄。客户端见过「全年龄」「R-15」；没有这格时按成人处理 |
| `.work_category` 的 class 里 `type_` 后面那段 | 类型代码，例如音声是 `SOU` |
| `.work_thumb_inner img` 的 `src` 或 `data-src` | 缩略图。开头常是 `//`，使用时补 `https:` |
| `.dl_count` | 销量 |
| `.work_review` | 评价数 |
| `.star_rating` 的 class 里 `star_` 后面的数字 | 评分。客户端把它除以 10 |

总数在 `page_info.count`。这是客户端读取的字段；2026-10-08 那次正文很长，没有单独把这一段打印出来核对。

商店网页自己还会在页面里放另一个地址：`https://www.dlsite.com/{站点}/sapi/=/.../format/json/`。2026-10-08 用缩短后的这条去要，返回的是空数组 `[]`。搜索以上面的 `fsr/ajax` 为准。

路径里常见的筛选项（名字来自商店页和 `dlsite` 客户端）：

| 名字 | 作用 | 见过的值 |
| --- | --- | --- |
| `language` | 搜索页语言 | `jp` |
| `keyword` | 关键词 | 自由文本 |
| `keyword_creator` | 按作者名 | 自由文本 |
| `sex_category[n]` | 向 | `male`、`female` |
| `age_category[n]` | 年龄 | `general`、`r15`、`adult` |
| `work_category[n]` | 卖场 | `doujin`、`books`、`pc`、`app` |
| `work_type[n]` | 具体类型 | 见下一节的类型代码，例如 `SOU` |
| `work_type_category[n]` | 类型大类 | `game`、`comic`、`illust`、`novel`、`movie_audio`、`music`、`tool`、`etc` |
| `genre[n]` | 标签编号 | 数字。显示用的名字是另一段 `genre_name[n]`，搜索本身只认数字 |
| `order` 或 `order[0]` | 排序 | `trend`、`release`、`release_d`、`dl`、`dl_d`、`price`、`price_d`、`rate_d`、`review_d`。商店搜索页默认写 `order[0]/release_d`。这次没有用发售日对过每个词的方向 |
| `price_low` / `price_high` | 价格区间 | 整数，日元 |
| `rate_average[0]` | 最低评分 | 客户端按整数传 |
| `ana_flg` | 是否包含预告 | `off`、`on`、`reserve`、`all` |
| `options[n]` | 必须带的标记 | 见下表 |
| `options_not[n]` | 排除的标记 | 同上 |
| `options_and_or` | 多个标记怎么组合 | `and`、`or` |
| `file_type[n]` | 文件格式 | 客户端示例用过 `PNG`、`EXE` |
| `per_page` | 每页条数 | 客户端写 30、50 或 100 |
| `page` | 页码 | 从 1 开始 |
| `is_free` | 只要免费 | 值为 `1` |
| `soon` | 24 小时内结束贩售 | 值为 `1` |
| `is_pointup` | 点数加成中 | 值为 `1` |
| `regist_date_end` | 发售日不晚于 | `2022-08-25` 这种日期 |
| `release_term` | 发售远近 | `week`、`month`、`year`、`old` |

`options` 里和听音声有关、并且在商店网址或客户端里出现过的：

| 代码 | 含义 |
| --- | --- |
| `JPN` | 日语作品 |
| `ENG` | 英语 |
| `CHI_HANS` | 简体中文 |
| `CHI_HANT` | 繁体中文 |
| `KO_KR` | 韩语 |
| `NM` | 商店页把这项标成「语言不问」 |
| `SND` | 有声音 |
| `DLP` | 可以在浏览器里看 |
| `TRI` | 有试听、试玩 |
| `AIG` | 整份由生成模型做的 |
| `AIP` | 一部分由生成模型做的 |

客户端还认识更多语言代码。这里不把每一种都列成「这次见过」。

## 2. 商品详情

详情有三层，由少到多：`product.json` 是一份完整商品 JSON；`product/info/ajax` 是更短的一份；作品网页是给人看的表格，客户端再从表格里补社团、声优、简介。

### 2.1 `product.json`（实测）

2026-10-07，`RJ243448`。

```http
GET https://www.dlsite.com/{站点}/api/=/product.json?workno=RJ243448&locale=zh_CN
```

`{站点}` 用过 `home` 和 `maniax`。`locale` 用过 `zh_CN` 和 `ja_JP`，都是 HTTP 200。标签名和类型说明会跟着语言变。

成功时正文是数组，取第一项。找不到时数组是空的：`RJ00000000` 在 `maniax` 上返回 `[]`，状态仍是 200。同一条作品用两个站点去要，正文里的 `site_id` 都以返回为准。

| 字段 | 这次见到的形状 | 怎么用 |
| --- | --- | --- |
| `workno` / `product_id` | `RJ243448` | RJ 号 |
| `work_name` | 字符串 | 标题 |
| `maker_name` | 字符串 | 社团名。这条示例返回的是 `DLsite`，不要当成每条都是这个名字 |
| `maker_id` / `circle_id` | `RG` 加数字 | 社团编号 |
| `creaters.voice_by` | 数组 | 声优。每一项有 `id`、`name`，还有 `classification`、`sub_classification` |
| `creaters.illust_by` | 数组 | 画师。播放器用不到 |
| `genres` | 数组 | 标签。每一项有 `id`、`name` |
| `regist_date` | `2019-01-13 16:00:00` | 发售时间 |
| `age_category` | 整数 `1` | 和下面的字符串一起出现 |
| `age_category_string` | `general` | 这条是全年龄。2、3 这次没在这条返回里见到 |
| `work_type` | `SOU` | 类型代码 |
| `work_type_string` | 字符串 | 类型的可读名字，随 `locale` 变 |
| `image_main.url` | `//img.dlsite.jp/...` | 封面。补上 `https:` |
| `price` | 整数 | 价格。这条免费作品是 `0` |
| `file_type` | `WAV` | 主要文件格式 |
| `file_type_special` | 字符串 | 附带格式说明 |
| `contents_file_size` | 整数 | 文件总字节数 |
| `site_id` | `home` | 作品实际所在站点 |
| `contents` | 数组 | 文件名清单。每一项有 `file_name`、`extension`、`file_size`、`workno`，没有网址 |

`contents` 不能拿来播放或下载。

客户端把 `age_category` 收成：`1` 全年龄，`2` R-15，`3` 成人。`work_type` 收成：

| 代码 | 客户端起的名字 |
| --- | --- |
| `SOU` | 音声、ASMR |
| `MUS` | 音乐 |
| `VCM` | 有声漫画 |
| `MOV` | 影像 |
| `MNG` | 漫画 |
| `ICG` | CG、插图 |
| `NRE` | 小说 |
| `DNV` | 数字小说 |
| `WBT` | 条漫 |
| `ADV` | 冒险 |
| `RPG` | 角色扮演 |
| `SLN` | 模拟 |
| `ACN` | 动作 |
| `STG` | 射击 |
| `QIZ` | 问答 |
| `PZL` | 解谜 |
| `TBL` | 桌面 |
| `TYP` | 打字 |
| `SCM` | 剧画 |
| `IMT` | 插图素材 |
| `AMT` | 音乐素材 |
| `PBC` | 出版物 |
| `TOL` | 工具 |
| `ETC` | 其它游戏 |
| `ET3` | 其它 |

书籍还有 `book_type`：`comic`、`magazine`、`publication`、`oneshot`。音声作品不一定有这项。

### 2.2 `product/info/ajax`（客户端）

```http
GET https://www.dlsite.com/maniax/product/info/ajax?product_id=RJ243448
```

客户端把返回当成一个 JSON 对象，用 RJ 号当键，取出这一条。它读取的字段：

| 字段 | 客户端怎么用 |
| --- | --- |
| `age_category` | 整数，对照上一节 |
| `work_type` | 类型代码 |
| `book_type.value` | 书籍形态。没有就跳过 |
| `regist_date` | `2019-01-13 16:00:00` 这种时间 |
| `options` | 用 `#` 拼起来的标记代码，例如语言。对不上上一节代码表的片段会丢掉 |
| `site_id`、`maker_id`、`work_name` | 和 `product.json` 同一类资料 |

2026-10-08 对这个地址的请求没有连上，所以不把「这次返回里还有哪些键」写死。要完整字段时用 `product.json`。

### 2.3 作品网页（客户端）

```http
GET https://www.dlsite.com/{site_id}/work/=/product_id/{RJ号}.html/
```

`site_id` 用上一节返回里的值。作品页没有时再试把 `work` 换成 `announce`（预告页）。HTTP 200 才算有页面。

客户端从表格 `#work_maker`、`#work_outline` 里按行首文字取：

| 行首 | 收成 |
| --- | --- |
| サークル名 / Circle | 社团 |
| ブランド名 / Brand | 品牌 |
| 出版社名 / Publisher | 出版社 |
| レーベル / Label | 厂牌 |
| 声優 / Voice Actor | 声优，一格里可能有多人 |
| 作者 / 著者 / Author | 作者 |
| イラスト / Illustration | 画师 |
| シナリオ / Scenario | 剧本 |
| 音楽 / Music | 音乐 |
| 作家 / Writer | 作家 |
| ジャンル / Genre | 标签 |
| イベント / Event | 活动 |
| ファイル形式 / File format | 文件格式 |
| ファイル容量 / File size | 容量，原文照收 |
| ページ数 / Page count | 页数 |
| シリーズ名 / Series name | 系列 |
| 予告開始日 / Published date | 预告开始 |
| 最終更新日 / Last updated | 最后更新 |

简介来自页面 `<meta name="description">`。封面以外的样品图来自 `.product-slider-data`。这些是客户端要找的行，不是 2026-10-08 重新打开网页核对过的清单。

### 2.4 社团页（客户端）

```http
GET https://www.dlsite.com/maniax/circle/profile/=/maker_id/{社团编号}.html/
```

社团编号形如 `RG` 加数字。客户端打开这页是为了补社团资料，不是为了拿音频。

## 3. 登录

登录用账号自己的邮箱和密码。客户端写明不支持用社交账号登录。2026-10-08 只打开了登录页，没有提交账号。

```http
GET https://login.dlsite.com/login?user=self
```

实测，这一页是 HTML，里面有三项表单：`_token`、`login_id`、`password`。`_token` 每次打开都会变，要拿这一次页面里的值。

```http
POST https://login.dlsite.com/login
```

正文用表单，不是 JSON：

| 字段 | 内容 |
| --- | --- |
| `_token` | 上一页里的那一段 |
| `login_id` | 登录名 |
| `password` | 密码 |

客户端用返回的 HTML 里有没有「ログイン中です」判断成功。失败就停，不继续往下要已购作品。成功后，这一次往返带回来的 cookie 要留给后面的请求。不要把密码写进日志。

接着还要打开两次，让 Play 那边也认出这个登录（客户端，这次没有登录所以没看返回）：

```http
GET https://play.dlsite.com/login/
GET https://play.dlsite.com/api/authorize
```

第二次带 `Referer: https://play.dlsite.com/`。

## 4. 已购买作品

下面都要带第 3 节留下的 cookie。没带时，2026-10-08 实测这两个地址都是 HTTP 401，正文是：

```json
{"status":401,"message":"Unauthorized"}
```

```http
GET https://play.dlsite.com/api/v3/content/count?last=0
GET https://play.dl.dlsite.com/api/v3/download/sign/cookie?workno=RJ243448
```

没买、或登录失效，就停在这里。不要改参数去试别人的作品。

Play 拿到的是网页优化过的文件：图可能被缩小，音声可能被改成 MP3。这不是商店「下载原装压缩包」那一条。原装压缩包的地址不在下面两个客户端里，这里不写。

### 4.1 已购列表（客户端）

`last` 是 Unix 时间（从 1970 年起的秒数）。`0` 表示从头列。想只看某天之后买的，就把那一天的秒数传进去。

```http
GET https://play.dlsite.com/api/v3/content/count?last=0
```

客户端读取 `user`（买过多少）、`page_limit`、`concurrency`。`user` 小于 1 就不用再问。

```http
GET https://play.dlsite.com/api/v3/content/sales?last=0
```

返回数组。每一项客户端读取 `workno` 和 `sales_date`（购买时间）。

作品详情再按每 100 个 RJ 号问一次：

```http
POST https://play.dlsite.com/api/v3/content/works
```

正文是 JSON 数组，里面是 RJ 号，不是包在别的字段里。返回里客户端读 `works` 数组。每一项它会取：

| 字段 | 怎么用 |
| --- | --- |
| `workno` | RJ 号 |
| `name` | 标题。按语言分成几份，没有指定语言时用 `ja_JP` |
| `maker.id` | 社团或品牌编号。以 `R` 开头当成社团，否则当成品牌 |
| `maker.name` | 社团名，同样按语言取 |
| `age_category` | 文字，客户端转成大写后再对照全年龄 / R-15 / 成人 |
| `work_type` | 类型代码 |
| `regist_date` | 发售时间 |
| `sales_date` | 购买时间。列表接口里已经有一份，这里可能再出现 |
| `upgrade_date` | 作品更新时间 |
| `author_name` | 作者，多人用 `/` 分开 |
| `tags` | 数组。`class` 决定身份，`name` 是人名 |
| `work_files.main` | 封面 |
| `work_files` 里除 `main` 以外 | 样品图 |

`tags[].class` 客户端对得上的：

| class | 身份 |
| --- | --- |
| `voice_by` | 声优 |
| `created_by` | 作者 |
| `scenario_by` | 剧本 |
| `illust_by` | 画师 |
| `music_by` | 音乐 |

已经从 Play 下架、或社团账号整个删掉的作品，不会出现在这张列表里。限时赠品、商店页已经下架但账号里还能听的，仍可能出现。这里的字段比 `product.json` 少，两边都有的那些（标题、社团、类型、发售日）客户端认为一致。

### 4.2 播放凭证（客户端）

```http
GET https://play.dl.dlsite.com/api/v3/download/sign/cookie?workno={RJ号}
```

客户端从 JSON 里取：

| 字段 | 怎么用 |
| --- | --- |
| `url` | 这件作品文件所在的目录，后面要接着拼 |
| `expires` | 这段凭证什么时候失效 |

未登录时就是上一节的 401。买过才会发 `url`。

### 4.3 文件树（客户端）

```http
GET {url}ziptree.json
```

`{url}` 就是上一节的 `url`，自己已经带了结尾的 `/`。客户端读取：

| 字段 | 怎么用 |
| --- | --- |
| `hash` | 这一份文件树的标记 |
| `workno` | RJ 号 |
| `version` / `revision` | 版本 |
| `updated_at` | `2019-01-13 16:00:00` 这种时间 |
| `tree` | 目录。每一项有 `type` |
| `playfile` | 用文件内部名字当键的一张表，真正的播放信息在这里 |

`tree` 里 `type` 客户端认三种：

| type | 字段 | 含义 |
| --- | --- | --- |
| `folder` | `name`、`path`、`children` | 文件夹 |
| `file` | `name`、`hashname` | 一个文件。`hashname` 用来到 `playfile` 里找播放信息 |
| `hidden` | 同文件 | 不展示的文件 |

给人看的路径用文件夹的 `path` 加上文件的 `name`，用 `/` 接起来。

`playfile` 里每一项：

| 字段 | 怎么用 |
| --- | --- |
| `type` | 文件种类。客户端单独处理过 `image`、`text`、`epub`、`epub_reflowable`、`ebook_fixed`、`ebook_voicecomic`、`ebook_webtoon`、`voicecomic_v2` |
| `length` | 原文件字节数 |
| 和 `type` 同名的那一层 | 各种成品。能直接下的在 `optimized` |

`optimized` 里客户端读取 `name`（文件名）和 `length`（字节数）。图片还可能有 `crypt`。`crypt` 为真时，下下来的图不是普通图片。本文不写怎么把图还原。

音声没有单独的种类分支。只要这项里有 `optimized.name`，就用下一节的地址去下。客户端说明里写：音声可能被重新压成 MP3。影像如果给的是一份播放列表而不是单个文件，这个客户端不会把里面的小段再下下来。

### 4.4 下载或播放一个文件（客户端）

```http
GET {url}optimized/{optimized.name}
```

这就是已购作品里单个文件的地址。音频播放器把这个地址交给播放器即可，请求仍要带登录 cookie，并带 `Referer: https://play.dlsite.com/`。凭证过期就回到 4.2 重要一次。

没有 `optimized` 的文件，客户端会跳过，不另找一条「原装文件」地址。

漫画、电子书页面是另一套查看器，地址不在 `optimized/` 下面。听音声用不到，这里不展开。

## 5. 和 asmr.one 的关系

asmr.one 作品上的 `source_type` 可以是 `DLSITE`，`source_id` 就是这里的 RJ 号。社团、声优、标签、发售日，asmr.one 的作品详情里已经有。现在的播放器读列表和保存作品时用 asmr.one。

要听 DLsite 上自己买过的文件，才需要第 3 节和第 4 节。那一套要登录，而且只对这个账号买过的作品发文件地址。

## 名词解释

- **API**：程序向网站要数据用的入口。人打开的是网页，程序打开的是这里的网址。
- **JSON**：把「标题、社团、标签」写成一段文本，程序能按字段名取出来。
- **GET**：条件写在网址里的要数据方式。
- **POST**：条件放在请求正文里的要数据方式。搜索不用它；登录和已购详情要用。
- **HTML**：人眼看到的那一页。搜索结果有一部分是嵌在 JSON 里的这种网页片段。
- **cookie**：登录成功后网站交回来的一小段通行证。后面要已购作品时得带上，否则对方当没登录。
- **Referer**：请求里附带的「我从哪个网页来」。不带的话，有的地址会拒绝。
