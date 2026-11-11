# DLsite 接口说明

对照商店搜索、商品页、登录，以及**自己已经买过的作品**怎么播放、怎么下载。本文同时记录播放器现有调用与公开客户端的接口资料，实际使用情况以「播放器的调用方式」及代码为准。在线热门列表仍只来自 asmr.one；自己搜索时，asmr.one 和这里会一起查。

没买的作品没有播放地址。本文不写绕过付费，也不写把被打乱的图片再拼回去的做法。

对照来源：

- 公开客户端 [dlsite-async](https://github.com/bhrevol/dlsite-async)（登录、商品、已购列表、Play 文件）
- 公开客户端 [dlsite](https://docs.rs/dlsite)（商店搜索的路径怎么拼）
- 公开客户端 [dlsite-manager](https://github.com/AcrylicShrimp/dlsite-manager/blob/main/crates/dm-api/src/client.rs)、[dlsite-utils](https://github.com/bhrevol/dlsite-utils/blob/main/src/dlsite_utils/__main__.py)（原始压缩包、商店跳转与分卷下载）
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

## 播放器的调用方式

这一段记 App 当前怎么用上面的接口，不重复下面的字段表。

### 作品编号

输入可以是 `RJ`、`VJ`、`BJ` 加数字，也可以只有数字；只有数字时补成 `RJ` 开头，统一转大写。

- `worknoOf`：得到请求用的编号。
- `worknoCandidates`：再用原样、补到 8 位、补到 6 位、去掉前导 0 这几种写法去试 `product.json`、作品页和 chobit。商品号短于标准长度时，这几种写法可能指向同一条作品。
- `canonicalWorkno`：前缀加去掉前导 0 的数字。判断是否已购买、搜索结果合并、库里是否已有同一部作品时用它，所以 `RJ0243448` 和 `RJ243448` 算同一部。

### 商店搜索

自己搜索时 App 只查 `maniax`，固定 `language=jp`、`work_type=SOU`，每页 20 条。文字放进 `keyword`，声优或社团文字放进 `keyword_creator`；「全年龄」加 `age_category[0]=general`；标签用固定列表里的数字编号。排序对应关系：

| App 里的排序 | `order` |
| --- | --- |
| 销量 | `dl_d` |
| 发售日 | `release_d` |
| 发售日-正序 | `release` |
| 价格-正序 | `price` |
| 价格 | `price_d` |
| 评分 | `rate_d` |
| 评价数 | `review_d` |
| 其它 | `trend` |

搜索只在不是热门模式时发；没填搜索词，也没有标签、声优、社团、全年龄或排序时，热门列表只来自 asmr.one。其余情况 App 会同时查 asmr.one，再按 `canonicalWorkno` 合并，两边都有的用 asmr.one 的资料，并标「DLsite 也有」。

商店返回的 `search_result` 是一段 HTML，App 先从卡片里取 RJ 号、标题、社团和封面，再用 `product.json` 补齐声优、标签、发售日和类型。补到的类型不是 `SOU` 的会丢掉。`product.json` 也拿不到时，退回作品网页读标题、社团和封面。

### 商品页和试听

`showcase` 先请求 `product.json`，站点用返回的 `site_id`；然后打开作品网页取图库，图库为空时用 `product.json` 里的 `image_samples`。`product.json` 本身为空时，只从作品页读标题、社团、封面和试听。

剪贴板导入在 asmr.one 确认未收录时，后台请求 `product.json` 取得标题和封面。由纯数字推断的候选在用户点击「查找作品」后才启动查询。空数组或 `404` 表示商品未找到，主动查找时底部提示「没有找到这部作品」。连接失败时显示对应的网络提示。查到商品后用 Play 的已购记录核对作品编号。已购作品在用户确认后才读取 Play 文件树并加入音频库，未购作品提供「查看详情页」。

试听来自 chobit：

1. 用 `https://chobit.cc/api/v1/dlsite/embed?workno={编号}` 取嵌入页，编号会把 `worknoCandidates` 试一遍。
2. 作品网页里如果也写了 `chobit.cc/embed/...`，一起收进来。
3. 最多打开 4 个嵌入页，从 `<li>` 的 `data-src` 取音频地址，从 `data-playtime` 取时长。

试听请求带 chobit 的 `Referer`，不带 DLsite 登录凭证。压缩包形式的试听不能播；没有试听时显示「没有试听」。手机开着流量时图库先不加载，点「预览」再取；连着 Wi-Fi 时直接显示。

### 登录和已购

登录在 WebView 里完成，也可以粘贴整段 Cookie。两种方式成功后会再打开 Play 的登录页和 `api/authorize`，让 Play 也认这次登录；WebView 拿到的 Cookie 会先去掉 Cloudflare 项。Cookie 存在加密偏好里，App 不保存密码，也不把 Cookie 写进日志。

登录后不会自动列已购作品。打开「查看已购买作品」时：

1. `GET /api/v3/content/count?last=0`，`user` 小于 1 就结束。
2. `GET /api/v3/content/sales?last=0` 取编号和购买时间。
3. 每 100 个编号一次 `POST /api/v3/content/works` 取标题、社团、封面和标签。

判断某部作品是否已购买时，用 `canonicalWorkno` 对照这张表。作品详情里的「已购买内容」和「DLSite Play」文件区都靠它。

### Play 文件

打开已购作品的文件树：

1. `GET https://play.dl.dlsite.com/api/v3/download/sign/cookie?workno={编号}` 换带时效的目录地址和凭证。
2. `GET {url}ziptree.json`。
3. 播放或下载单个文件用 `{url}optimized/{optimized.name}`，带 Play 的 `Referer` 和登录 Cookie。

文件树的过滤：

- `hidden` 节点整枝丢掉；过滤后没有文件的文件夹不显示。
- 没有 `optimized` 的文件、`crypt` 为真的文件、文件名以 `.m3u8` 结尾的文件都跳过。
- 音频按 `type` 或扩展名判断，扩展名认 `mp3`、`m4a`、`aac`、`wav`、`ogg`。
- 非音频保留 `image`、`text` 类型及其它具有非加密 `optimized` 文件地址的条目；未知类型保留为普通文件，供外部应用打开。没有优化地址、加密或分段媒体的条目仍跳过。

播放和下载前都会重新签一次目录地址。遇到 `403` 或 `404` 会再签一次并重试；`401` 提示「登录已失效，请重新登录」。下完的文件放在 `filesDir/dlsite/{作品编号}/`，之后播放读本地文件。

试听行虽然来源也记成 DLsite，但 `fileKey` 为空。空 `fileKey` 的请求不带 DLsite 登录，避免把已购凭证发给试听地址。

文本文件同样最多读 512KB，超过时在末尾加「…（只显示前面一段）」；`401` 表示登录失效。

未知类型的远端文件点击「使用其他应用打开」后暂存到 `cacheDir/external-open`。预览请求在内存中保留作品号和优化文件身份，传输前重新获取授权，遇到 401/403 时最多再获取一次；Cookie 仅提供给本次签发地址的 HTTPS 主机及默认端口，跳转到其它主机时不携带。外部应用只收到暂存文件的临时只读 content URI，不收到地址或凭证。已有原始本地文件优先使用；缺少本地副本且没有单文件地址时提示重新下载完整包。

### 原始压缩包

「缓存到本地」选定 DLsite 后，使用第 4.5 节的原始压缩包入口；不先检查同名不同格式，也不要求先读到 Play 文件树。在作品子目录发起时，同样下载整个作品。asmr.one 仍使用原有按目录下载和格式选择。

音频库单曲、多选、在线作品详情的下载，以及没有本地文件时生成字幕的前置下载，先询问下载方式：

- 「下载完整包」：按作品去重，下载原始压缩包并自动解压。
- 「仅下载所选文件」：继续使用上面的 Play 优化音轨。
- 「取消」：不创建此次下载任务；混合来源多选也不提前创建其它来源的下载。

没有 Play 文件身份的原包独有曲目只能选择完整包或取消。试听不参与此选择。原始包只是作者上传的文件集合，可能只有 MP3；界面说明音质以作者提供的文件为准，不将原始包等同于无损编码。

完整包先下载到独立暂存目录 `filesDir/dlsite-archives/{作品号}/{任务编号}/`，解压到 `filesDir/dlsite/{规范作品号}/original/{本次目录编号}/`。全部校验、解压和入库关联成功后才登记完成，再删除压缩包；失败或中止不替换已有副本，保留已下载压缩包供重试；已确认损坏的包会清理并在重试时重新下载，不自动退回 Play 压缩音轨。

失败任务可在下载管理删除，确认后一并回收本任务的压缩包暂存，重试需从头下载；删除只作用于失败任务，进行中与已完成的任务不受影响。启动时按目录扫描暂存，只保留仍未完成的任务对应的目录（供断点续传）和已发布副本，其余包括没有任务引用的孤儿目录一并清理，空的作品目录同时删除。

解压后的文件以原始目录清单登记，按作品编号与完整相对路径保持身份。能匹配的 Play 曲目复用编号、播放列表和字幕关联，原包独有音轨补充入库；DLsite 文件区使用原始目录。在线详情和远程队列也按作品编号与 Play 文件身份优先查原始本地副本。移除音频库作品后，清单及磁盘下载仍保留，再次保存或缓存时，可从仍完整的原始目录重建关联。

包内某个文件已关联到其他曲目时跳过该文件，保留原有关联并在清单中记录真实占用者，其余文件照常发布；任务按完成登记，并提示「已发布原始文件；N 个文件已关联其他曲目，保持不变」。不再因为单个冲突整体回滚，避免重试必然再次失败。

选择完整包的听写任务记录具体下载任务编号，等解压及目标文件关联完成后才听写；目标没有匹配时明确失败，不被其它单文件下载提前唤醒。缺文件的手动重试同样先选择下载方式；取消听写不会中止共享的下载任务。

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

> 2026-11-01：`login.dlsite.com` 对非浏览器请求返回 Cloudflare 403 challenge。播放器改为在 WebView 里打开这一页，登录完成后读取 Cookie、去掉 Cloudflare 项，再用 Play 接口验证。下面保留账号密码表单的接口记录，但不再是播放器的登录路径。

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

Play 文件接口拿到的是网页优化过的文件：图可能被缩小，音声可能被改成 MP3。原始压缩包使用独立入口，见第 4.5 节。

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

Play 单文件模式跳过没有 `optimized` 的文件，不把原始相对路径当成 Play 文件名。完整原始包使用下一节的独立入口。

未入库的远端单文件下载到作品目录下的扁平文件名。`optimized.name` 不含目录，同一作品不同子目录可能出现同名文件：目标路径已被磁盘文件或同作品其他 `fileKey` 的下载记录占用时，改落到 `{fileKey 短哈希}/{文件名}` 子目录，避免互相覆盖后播放或听写指向另一个文件。同一 `fileKey` 始终复用已落盘路径，重试与旁挂字幕匹配不受影响；`fileKey` 为空的试听沿用扁平文件名。已入库曲目仍按曲目的相对目录保存，不走这条规则。

漫画、电子书页面是另一套查看器，地址不在 `optimized/` 下面。听音声用不到，这里不展开。

### 4.5 原始压缩包（客户端源码参考）

```http
GET https://play.dlsite.com/api/v3/download?workno={作品编号}
```

这条接口使用用户的 DLsite 登录会话，不使用 `download/sign/cookie` 签发的优化文件凭证。地址和跳转行为来自上文列出的两个公开客户端源码。开发机直连 DLsite 受限；普通单压缩包作品已确认可在真机下载，分卷页的官方下载链接和附件响应头已核对，分卷整包下载、解压及序列号产品流程仍待真机验证。

原始包下载使用独立的桌面浏览器 `User-Agent`。移动浏览器标识会让商店将单包和分卷请求都转到 `/download/filelist/` 手机文件列表；该页面不是原始压缩包入口。登录、Play 播放和其它商品请求继续使用各自现有标识。

普通下载会经商店下载页跳转到文件地址；也可能先进入商店的分卷页或序列号产品页。播放器以普通 GET 完整读取控制页面，不对登录、下载入口或分卷页发送只读取首字节的 Range 请求；读取其中属于当前作品的官方下载链接，兼容各卖场路径、链接属性及按钮中引用的地址，分卷页只接受含有效 `/number/{正整数卷号}/` 的实际下载入口，按卷号去重、排序；忽略语言切换、页面自身及其语言参数或尾斜杠变体，避免把导航链接作为下载继续递归。序列号页面只用于发现下载链接，不保存或展示序列号。

```http
GET https://www.dlsite.com/home/download/=/product_id/{作品编号}.html
GET https://download.dlsite.com/get/=/.../file/{压缩包文件名}/_/...
```

请求原始下载入口前，先将库内去除前导零的身份恢复为商店使用的 6 位或 8 位数字编号，例如 `RJ1042521` 请求为 `RJ01042521`；仅收到明确的 404 时再尝试其它编号写法。400 等失败会标明发生在下载入口、商店下载页还是文件服务器，不以权限错误概括所有未识别页面。

以上只表示路径形态；最终文件地址包含临时授权，不能保存为稳定作品地址，也不写日志或完整错误信息。播放器手动限制跳转最多八次，仅接受 DLsite 域下的 HTTPS 地址；登录会话初始只提供给 Play 和商店主机，其后 Cookie 按服务器设置的域和路径使用。登录页跳转、401、403、404、返回网页或无法识别文件时均明确失败，不把网页保存成压缩包。

分卷按顺序逐卷解析、立即下载，再进入下一卷，不预先获取所有分卷的临时文件地址，避免后续卷的授权请求替换当前文件状态。文件名优先从 `Content-Disposition` 读取，再参考最终地址的 `/file/{文件名}/`。每卷从文件响应头读取大小，只在实际文件传输时使用范围请求；下载中断时校验 `Content-Range` 的起点与总大小后继续暂存文件，服务器忽略 Range 时从头重下，不拼接重复正文。传输响应提供文件名时，还须与当前卷一致；起点不符、文件总大小变化、返回其他分卷分别报错，均不写入不匹配的响应正文。同名完整卷可在重试时复用，所有卷完成后统一解压。由于不提前请求后续卷，传输期间累计显示已下载字节，全部完成后才确认整个作品的下载总量。

当前解压支持单个 ZIP，以及 RAR、RAR 分卷和旧式 EXE 自解压 RAR。自解压文件只按归档读取，不执行程序；不支持的格式、加密、缺卷或损坏会报错。ZIP 使用已有 Commons Compress 并兼容日文文件名；RAR 使用 Junrar 8.1.2，字典上限为 64 MiB，以控制 Android 内存需求。解压还校验文件大小与校验码，拒绝路径越界、链接、重复路径及超过十万个条目的压缩包。嵌套压缩文件只保留，不递归展开。

下载任务以 `kind = DLSITE_ARCHIVE` 区分，按作品显示获取信息、下载、解压和关联文件阶段，支持中止、失败重试。任务完成后目录不作为单个音频播放。原始目录清单独立记录每个文件，下载管理据此展示已落盘音频及相关字幕；图片和文本优先从本地预览。完成清单缺少本地文件时，之后仍可重新请求完整包。

数据库 14→15 新增原始作品、原始条目清单，以及下载类型、阶段、本地预览路径和听写下载依赖等可空列。新增可空列不设置默认值。旧任务依赖为空时保留原有按曲目或地址等待下载的兼容逻辑。

## 5. 和 asmr.one 的关系

asmr.one 作品上的 `source_type` 可以是 `DLSITE`，`source_id` 就是这里的 RJ 号。社团、声优、标签、发售日，asmr.one 的作品详情里已经有。播放器搜索时会同时查 asmr.one 和这里，热门列表只用 asmr.one。作品详情里的图库和试听来自商品页。已登录且买过的作品，文件可以在 Play 和 asmr.one 之间切换。

要听 DLsite 上自己买过的文件，用第 3 节和第 4 节。那一套要登录，而且只对这个账号买过的作品发文件地址。

## 名词解释

- **API**：程序向网站要数据用的入口。人打开的是网页，程序打开的是这里的网址。
- **JSON**：把「标题、社团、标签」写成一段文本，程序能按字段名取出来。
- **GET**：条件写在网址里的要数据方式。
- **POST**：条件放在请求正文里的要数据方式。搜索不用它；登录和已购详情要用。
- **HTML**：人眼看到的那一页。搜索结果有一部分是嵌在 JSON 里的这种网页片段。
- **cookie**：登录成功后网站交回来的一小段通行证。后面要已购作品时得带上，否则对方当没登录。
- **Referer**：请求里附带的「我从哪个网页来」。不带的话，有的地址会拒绝。
