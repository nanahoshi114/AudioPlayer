# 关于和检查更新

「设置 → 关于」是单独页面，带返回按钮，隐藏主页面顶栏、底栏和播放条。当前版本从 PackageManager 读取安装包的 `versionName` 和长整型 `versionCode`，不在界面硬编码，也不修改工程版本。

## 获取最新正式版本

只有用户点击「检查更新」才调用 `AppUpdateClient.latest()`：

```http
GET https://api.github.com/repos/nanahoshi114/AudioPlayer/releases/latest
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2026-03-10
User-Agent: AudioPlayer-Android-UpdateCheck
```

使用独立匿名 OkHttp 客户端，不读取应用内任何账号凭证，不带 Cookie／Authorization；连接超时 10 秒、读取超时 15 秒、整次请求 20 秒。不跟随 HTTP 重定向，防止版本请求被导向附件；退出页面取消正在进行的请求。

GitHub 的 [Get the latest release](https://docs.github.com/en/rest/releases/releases#get-the-latest-release) 返回最新公开正式发布。客户端再次排除 `draft` / `prerelease`，并确认 `assets` 中至少有一个名称以 `.apk` 结尾且 `state=uploaded` 的附件。只读取这些元数据，不使用 `browser_download_url`，不下载、解析或安装 APK。

HTTP 404、限流、无 APK、JSON 无效或网络失败均显示可重试错误，不当成「已是最新」。检查中按钮禁用，避免重复请求；检查结果在旋转时由 ViewModel 保留。

## 版本比较

以 `tag_name` 为版本，支持 `v1.2` / `1.2.3`。标签无法解析时，仅当发布名称本身为版本号才使用名称；不从任意文字或日期中猜版本。

- 各数字段按数值比较，缺失段为零：`1.10 > 1.2`，`1.2 = 1.2.0`。
- 正式版本高于同号预发布，预发布标识中的数字按数值比较；`+` 构建标识不参与排序。
- 最新版本更高显示「发现新版本」，相等显示「当前已是最新版本」，本机更高则明确显示「当前版本高于最新发布版本」。不能解析时提示前往发布页确认。
- GitHub 元数据没有 APK 内的 versionCode，本功能不比较构建号。发布新 APK 时应同步递增 Android `versionCode` 和 `versionName`，并以相同版本名创建发布标签；同版本名下替换 APK 不会被识别为新版本。

## 浏览器跳转

「在浏览器打开发布页」始终可用。检查成功后打开固定仓库的 `releases/tag/{tag}`，标签按路径段编码；未检查或检查失败时打开 `https://github.com/nanahoshi114/AudioPlayer/releases`。

先用 PackageManager 查询支持普通 HTTPS 页面的浏览器，再将发布页交给默认浏览器；没有默认选择时展示浏览器选择器。`https://browser.invalid/` 仅是本地 Intent 查询所用的保留域名，不发起网络连接。Manifest 的 HTTPS `<queries>` 用于 Android 包可见性，不新增下载或安装权限。没有浏览器时显示提示。

用户在外部浏览器的发布页自行下载 APK 并安装。应用中没有更新文件下载任务、更新 WebView、自动安装或后台检查。

## 验证记录

2026-11-02 使用上述请求头只读探测真实 `/releases/latest`，HTTP 200，标签为 `v1.1`，正式发布且存在 uploaded 的 `app-release.apk`。当时工程版本为 `1.2` / 构建号 3，因此比较结果应为本机版本更高，不提示升级到 `v1.1`。

Kotlin 编译、Debug APK 和设备测试 APK 构建通过，146 项定向单元测试全部通过，其中 8 项为版本检查测试。

单测覆盖数字段、缺省零、预发布、构建标识、未知版本、无 APK、未上传附件、错误和取消、只请求 JSON 且不跟随附件重定向。设备测试覆盖安装包版本读取、点击前不检查或打开浏览器、检查时禁用重复请求，以及更新入口只交出发布页链接。设备测试只构建，未运行；本轮未安装手机，实际浏览器选择和往返仍需上机验收。
