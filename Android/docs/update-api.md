# 关于和检查更新

「设置 → 关于」是单独页面，带返回按钮，隐藏主页面顶栏、底栏和播放条。当前版本从 PackageManager 读取安装包的 `versionName` 和长整型 `versionCode`，不在界面硬编码，也不修改工程版本。

## 获取最新 Android 正式版本

只有用户点击「检查更新」才调用 `AppUpdateClient.latest()`：

```http
GET https://api.github.com/repos/nanahoshi114/AudioPlayer/releases?per_page=100
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2026-03-10
User-Agent: AudioPlayer-Android-UpdateCheck
```

使用独立匿名 OkHttp 客户端，不读取应用内任何账号凭证，不带 Cookie／Authorization；连接超时 10 秒、读取超时 15 秒、整次请求 20 秒。不跟随 HTTP 重定向，防止版本请求被导向附件；退出页面取消正在进行的请求。

GitHub 的 [List releases](https://docs.github.com/en/rest/releases/releases#list-releases) 返回公开发布列表，最新的排在最前。客户端只接受 `tag_name` 以 `android-` 开头、不是 `draft` / `prerelease`，且 `assets` 中至少有一个名称以 `.apk` 结尾、`state=uploaded` 的发布。桌面端发布即使版本更高也不参与 Android 更新检查。只读取这些元数据，不使用 `browser_download_url`，不下载、解析或安装 APK。

没有 `android-` 开头的发布、限流、无 APK、JSON 无效或网络失败均显示可重试错误，不当成「已是最新」。检查中按钮禁用，避免重复请求；检查结果在旋转时由 ViewModel 保留。

## 发布标签约定

Android 发布使用 `android-X.Y`，例如 `android-1.3`。桌面端使用自己的标签，例如 `v1.4` 或 `desktop-1.4`。Android 更新检查只匹配 `android-` 前缀，因此历史上的 `v1.0`～`v1.2` 不会被新版检查识别；从 1.3 起按 `android-1.3` 发布。发布新 APK 时应同步递增 Android `versionCode` 和 `versionName`，并使用相同版本创建 `android-X.Y` 标签；同版本名替换 APK 不会被识别为新版本。

## 版本比较

以 `tag_name` 去掉 `android-` 前缀后的部分为版本，支持 `v1.2` / `1.2.3`。去前缀后无法解析时，仅当发布名称本身为版本号才使用名称；不从任意文字或日期中猜版本。

- 各数字段按数值比较，缺失段为零：`1.10 > 1.2`，`1.2 = 1.2.0`。
- 正式版本高于同号预发布，预发布标识中的数字按数值比较；`+` 构建标识不参与排序。
- 最新版本更高显示「发现新版本」，相等显示「当前已是最新版本」，本机更高则明确显示「当前版本高于最新发布版本」。不能解析时提示前往发布页确认。
- GitHub 元数据没有 APK 内的 versionCode，本功能不比较构建号。发布新 APK 时应同步递增 Android `versionCode` 和 `versionName`。

## 浏览器跳转

「在浏览器打开发布页」始终可用。检查成功后打开固定仓库的 `releases/tag/{tag}`，标签按路径段编码；未检查或检查失败时打开 `https://github.com/nanahoshi114/AudioPlayer/releases`。

先用 PackageManager 查询支持普通 HTTPS 页面的浏览器，再将发布页交给默认浏览器；没有默认选择时展示浏览器选择器。`https://browser.invalid/` 仅是本地 Intent 查询所用的保留域名，不发起网络连接。Manifest 的 HTTPS `<queries>` 用于 Android 包可见性，不新增下载或安装权限。没有浏览器时显示提示。

用户在外部浏览器的发布页自行下载 APK 并安装。应用中没有更新文件下载任务、更新 WebView、自动安装或后台检查。

## 验证记录

2026-11-04 只读查询发布列表：仓库当前有 v1.0～v1.4 五个正式发布。v1.0～v1.2 的标签不带 `android-` 前缀，其中 v1.2 是最后一个 Android APK；v1.3、v1.4 只有桌面端的 `PlayerDemo-win-x64.exe`。在发布 `android-1.3` 之前，新版检查会提示没有找到 Android 发布。

单测覆盖 Android 标签筛选、桌面端标签排除、草稿/预发布跳过、数字段与预发布比较、无 APK、错误和取消、只请求 JSON 且不跟随附件重定向。设备测试覆盖安装包版本读取、点击前不检查或打开浏览器、检查时禁用重复请求，以及更新入口只交出发布页链接。设备测试需要真机执行后才能确认平台交互。
