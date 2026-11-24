# UI Design System (`DESIGN.md`)

> 本文档提炼自 Android 端现有 Jetpack Compose Material 3 实现（`Android/app/src/main/java/com/nanahoshi/audioplayer/ui/theme/` 与 `ui/components/`），作为**本仓库最高优先级 UI 设计与实现规范**。在本仓库内进行任何界面、样式、组件、布局、图标或动效修改，都必须优先严格遵守本文档；禁止脱离语义 Token 硬编码色值、字号、圆角或中文文案。

---

## 1. 色彩系统（Color System）

应用基于 Material 3 `ColorScheme`，由 `AudioPlayerTheme`（`ui/theme/Theme.kt`）统一供给，并通过 `LocalDarkTheme`（应用实际深浅模式，不可使用 `isSystemInDarkTheme()` 替代）与 `LocalSourceColors` 暴露扩展语义色。

### 1.1 主题色来源（`ThemeAccent`）
- `ThemeAccent.System`：Android 12+（`API >= 31`）使用系统动态取色（`dynamicDarkColorScheme` / `dynamicLightColorScheme`）；低版本回退至内置蓝紫主题。
- `ThemeAccent.Default`：使用内置蓝紫主系（`LightColorScheme` / `DarkColorScheme`）。
- `ThemeAccent.Seed(color)`：由 11 档预设强调色（`AccentPresets`：`indigo #4F5FD6`、`blue #1E88E5`、`teal #00897B`、`green #43A047`、`amber #F9A825`、`orange #F4511E`、`red #D32F2F`、`pink #D81B60`、`purple #8E24AA`、`brown #795548`、`slate #607D8B`）或封面取色 `rememberCoverAccent` 经 `seedColorScheme(seed, dark)` 按固定 HSL 明度档位生成整套配色，切换时通过 `Motion.long`（450ms）过渡。

### 1.2 内置默认色板与语义变量（`Color.kt` / `Theme.kt`）

| 语义角色（`MaterialTheme.colorScheme.*`） | 浅色 Token & 色值（Day） | 深色 Token & 色值（Night） | 用途 |
| --- | --- | --- | --- |
| `primary` | `DayPrimary` (`#3A4AD0`) | `NightPrimary` (`#B8C4FF`) | 主操作按钮、高亮字幕行、激活指示、搜索光标 |
| `onPrimary` | `DayOnPrimary` (`#FFFFFF`) | `NightOnPrimary` (`#0C1B6B`) | `primary` 底上的图标/文字（如主播放键图标） |
| `primaryContainer` | `DayPrimaryContainer` (`#DEE2FF`) | `NightPrimaryContainer` (`#2C3EBE`) | 强调容器底色、播放页顶部渐变起点 |
| `onPrimaryContainer` | `DayOnPrimaryContainer` (`#00115A`) | `NightOnPrimaryContainer` (`#DEE2FF`) | `primaryContainer` 上的文字/图标 |
| `secondary` | `DaySecondary` (`#00687A`) | `NightSecondary` (`#7AD7F0`) | 次级强调、辅助控件 |
| `onSecondary` | `DayOnSecondary` (`#FFFFFF`) | `NightOnSecondary` (`#003640`) | `secondary` 上的内容 |
| `secondaryContainer` | `DaySecondaryContainer` (`#B4EBFF`) | `NightSecondaryContainer` (`#004E5E`) | 次级容器（Segmented / FilterChip / TonalButton） |
| `onSecondaryContainer` | `DayOnSecondaryContainer` (`#001F27`) | `NightOnSecondaryContainer` (`#B6ECFF`) | 次级容器内容 |
| `tertiary` | `DayTertiary` (`#6C4E94`) | `NightTertiary` (`#D7BAFF`) | 第三强调色、成功状态徽章（`StatusTone.Success`） |
| `onTertiary` | `DayOnTertiary` (`#FFFFFF`) | `NightOnTertiary` (`#3E2068`) | `tertiary` 上的内容 |
| `tertiaryContainer` | `DayTertiaryContainer` (`#F0DBFF`) | `NightTertiaryContainer` (`#56387F`) | 第三容器底 |
| `onTertiaryContainer` | `DayOnTertiaryContainer` (`#27084C`) | `NightOnTertiaryContainer` (`#F0DBFF`) | 第三容器内容 |
| `background` / `surface` | `DayBackground` / `DaySurface` (`#F6F7FB`) | `NightBackground` / `NightSurface` (`#10141C`) | 页面主背景与默认顶栏底色 |
| `surfaceContainer` / `surfaceVariant` | `DaySurfaceContainer` (`#E8EBF4`) | `NightSurfaceContainer` (`#1C2433`) | 分组卡片（`SettingsGroup`、`WorkCard`、`AccountCard`、`MiniPlayerCard`）、无图封面占位底、抬升顶栏 |
| `onSurface` / `onBackground` | `DayOnSurface` (`#171C26`) | `NightOnSurface` (`#E6E9F2`) | 一级正文、主标题（文字最高层级） |
| `onSurfaceVariant` | `DayOnSurfaceVariant` (`#434B5C`) | `NightOnSurfaceVariant` (`#B7C0D0`) | 次级描述、作者/副标题、次要图标；静音提示等弱化引导可叠 `.copy(alpha = 0.6f)`，非激活字幕行 `.copy(alpha = 0.55f)` |
| `error` | M3 标准 `error` | M3 标准 `error` | 错误提示、校验失败提示、危险确认操作（删除/移除/取关）确认文字与图标 |

> 种子色模式（`seedColorScheme`）进一步细分 `surfaceContainerLowest`（浅 `#FFFFFF` / 深 `L=0.06`）→ `surfaceContainerLow` → `surfaceContainer` → `surfaceContainerHigh` → `surfaceContainerHighest` 以及 `outline`、`outlineVariant`。

### 1.3 来源与状态语义色（`LocalSourceColors` / `StatusTone`）

- **内容来源色板（`SourcePalette`，通过 `LocalSourceColors.current` 读取）**：
  - `bilibili`：浅色 `#C2185B` / 深色 `#FF9BB8`
  - `youtube`：浅色 `#B3261E` / 深色 `#FFB4AB`
  - `asmr`（含 `asmr.one` / `Japanese ASMR`）：浅色 `#4E3BB5` / 深色 `#C9BEFF`
  - `dlsite`：浅色 `#0B63B6` / 深色 `#8ECAFF`
  - `local`：浅色 `#3E4C5A` / 深色 `#C5D0DC`
- **任务状态徽章（`StatusTone`）**：
  - `Neutral` → `onSurfaceVariant`；`Active` → `primary`；`Success` → `tertiary`；`Error` → `error`。
- **徽章（`LabelBadge`）配色公式**：文字色 `color`，胶囊背景固定 `color.copy(alpha = 0.16f)`。
- **特定场景色彩约束**：
  - 播放页（`NowPlayingScreen`）背景从顶部垂直渐变至透明：起点为 `primaryContainer.copy(alpha = if (LocalDarkTheme.current) 0.28f else 0.35f)`。
  - 二维码弹窗（`BiliQrDialog`）图片容器强制白底 `Color.White` 与外围静区，禁止在深色模式下透出暗底。
  - 卡片内嵌 `ListItem`（如 `SettingsGroup` 内部）必须显式传入 `colors = ListItemDefaults.colors(containerColor = Color.Transparent)`，防止遮盖卡片 `surfaceContainer` 底色。
  - 所有用户可见中文文案与无障碍标签必须放在 `res/values/strings*.xml`，禁止 Kotlin 硬编码中文 UI 字符串。

---

## 2. 字体排版规范（Typography Scale）

全应用统一使用系统默认无衬线家族 `FontFamily.Default`（`ui/theme/Type.kt`），不引入额外自定义字体文件：

| 语义样式名 | 字号（`fontSize`） | 字重（`fontWeight`） | 行高（`lineHeight`） | 典型场景 |
| --- | --- | --- | --- | --- |
| `MaterialTheme.typography.titleLarge` | `22.sp` | `FontWeight.SemiBold` (600) | `30.sp` | 页面分区标题、作品详情主标题、章节弹窗头 |
| `MaterialTheme.typography.titleMedium` | `16.sp` | `FontWeight.SemiBold` (600) | `24.sp` | 卡片/列表项主标题、`MiniPlayer` 标题、设置项标题、弹窗标题 |
| `MaterialTheme.typography.bodyLarge` | `16.sp` | `FontWeight.Normal` (400) | `24.sp` | 常规正文阅读与输入内容 |
| `MaterialTheme.typography.bodyMedium` | `14.sp` | `FontWeight.Normal` (400) | `21.sp` | 默认说明文字、次要列表摘要、操作提示 |
| `MaterialTheme.typography.bodySmall` | `12.sp` | `FontWeight.Normal` (400) | `18.sp` | 次级辅助信息、时间戳、技术摘要、行内补充说明 |
| `MaterialTheme.typography.labelMedium` | `12.sp` | `FontWeight.Medium` (500) | `16.sp` | 徽章（`LabelBadge`）、次级按钮标签、进度条起止时间 |
| `SubtitleLarge`（自定义扩展） | `20.sp` | `FontWeight.Medium` (500) | `32.sp` | 播放页当前居中字幕展示 |
| `SubtitleReader`（自定义扩展） | `18.sp` | `FontWeight.Normal` (400) | `30.sp` | 全屏字幕阅读器逐行字幕（当前高亮行叠 `FontWeight.SemiBold` + `primary`） |

**排版与溢出铁律**：
- 徽章、分段按钮、页签标签和单行标题统一 `maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis`；卡片标题最多 `maxLines = 2, overflow = TextOverflow.Ellipsis`。
- 含多标签/时长元数据的列表行（如 `TrackRow`）必须使用 `FlowRow` 自动换行，确保 `360dp` 宽 + `2.0x` 字体缩放下不裁切。
- 播放页与 MiniPlayer 副标题（作者）为空时不渲染空占位 `Text`。

---

## 3. 间距与圆角规范（Spacing & Shape）

### 3.1 圆角体系（`AppShapes`，`ui/theme/Shape.kt`）

| 语义变量 / 场景 | 圆角半径 | 典型组件 |
| --- | --- | --- |
| `MaterialTheme.shapes.extraSmall` | `8.dp` | 小型控件、内置微容器 |
| `MaterialTheme.shapes.small` | `8.dp` | 列表缩略封面（`WorkPreviewRow` / B 站 / YouTube / 播放列表行）、标题点击裁剪 |
| `MaterialTheme.shapes.medium` | `12.dp` | `CoverImage` 默认圆角、`WorkCard` 封面、二维码白底容器、次级功能按钮点击区域 |
| `MaterialTheme.shapes.large` | `20.dp` | 主卡片（`SettingsGroup`、`WorkCard`、`AccountCard`）、播放页主封面阴影容器 |
| `MaterialTheme.shapes.extraLarge` | `28.dp` | 大弹窗、抽屉/大型圆角容器 |
| **特例固定圆角** | `16.dp` | `MiniPlayerCard` 外框圆角 |
| **胶囊形 / 全圆角** | `28.dp` / `99.dp` / `CircleShape` | `SearchField` 及搜索触发栏（`28.dp`）；`RoundedProgress`（`99.dp`，高 `6.dp`）；播放页主播放键 `72.dp` `CircleShape`；`LabelBadge` 固定 `8.dp` |

### 3.2 间距、内边距与触控尺寸

- **页面水平边距**：全应用列表及内容区统一 `16.dp`（顶栏内左右保留 Material 规范）；全屏空/加载提示内边距 `24.dp`；字幕阅读器行水平内边距 `24.dp`、垂直 `12.dp`。
- **卡片与容器内边距**：
  - `WorkCard`：外框 `12.dp`，封面（`84.dp`）与文字区间距 `12.dp`，徽章列距上 `8.dp`。
  - `AccountCard`：顶部外距 `16.dp`，内边距 `16.dp`。
  - `MiniPlayerCard`：外部 `padding(horizontal = 12.dp, vertical = 6.dp)`（须在 `.pressScale()` 之前应用）；内部行 `start = 10.dp, end = 4.dp, top = 8.dp, bottom = 4.dp`，封面 `44.dp`。
- **元素间距步进**：以 `4.dp` / `6.dp` / `8.dp` / `12.dp` / `16.dp` 为基准网格：
  - 徽章列与 `TrackRow` `FlowRow`：水平 `6.dp`，垂直 `4.dp`。
  - 弹窗正文段落与操作组、播放页底部动作 `FlowRow`：间距 `8.dp`。
  - 播放页 5 键控制栏（上一首 / 快退10s / 播放 / 快进10s / 下一首）：按钮间距 `6.dp`，主播放键（`72.dp`）左右各加 `10.dp` 呼吸边距，其余 4 键固定 `48.dp` 触控区（快进快退图标 `28.dp`，切歌图标 `32.dp`）。
- **列表底部安全留白（`contentPadding`）**：
  - 无悬浮操作按钮（FAB）的列表页：`PaddingValues(bottom = 24.dp)`。
  - 带 FAB 的页面（如 `LibraryWorkScreen`、`OnlineWorkScreen` 有入库/打开入口时）：`PaddingValues(bottom = 96.dp)`。
  - 全屏字幕阅读器：`PaddingValues(bottom = 72.dp)`；章节列表补充 `.navigationBarsPadding()`；网页验证页根布局补充 `.imePadding()`。
- **最小触控目标**：所有交互行、筛选开关行、弹窗单选行、色板选择与 `SecondaryAction` 必须满足 `>= 48.dp` 高度/宽度。
- **`BadgeLine` 布局约束**：因内部使用 `BoxWithConstraints`，严禁直接放入 Material 3 `ListItem` 槽位，必须用普通 `Row` / `Column` 承载。

---

## 4. 阴影、层级与边框风格（Elevation, Shadow & Borders）

- **层级优先策略**：默认采用 **Surface 色阶差**（`background` / `surface` `#F6F7FB` 或 `#10141C` 与 `surfaceContainer` `#E8EBF4` 或 `#1C2433`）区分信息卡片，避免在普通列表卡片上滥用阴影或描边。
- **阴影规范（克制使用）**：
  - 悬浮迷你播放条 `MiniPlayerCard`：`shadowElevation = 6.dp`。
  - 播放页全尺寸专辑封面：`.shadow(12.dp, MaterialTheme.shapes.large)`（外边距置于共享元素边界之外）。
  - 一般列表卡片（`AccountCard`、`WorkCard`、`SettingsGroup`）不使用额外 `tonalElevation` 或 `shadowElevation`（`OnlineScreens` 个别内部辅助区仅保留 `tonalElevation = 1.dp`）。
- **分隔线与边框**：
  - 同一 `SettingsGroup` 或设置分区内的连续条目使用 Material 3 `HorizontalDivider()`（无额外自定义硬边框）。
  - 桌面悬浮字幕窗（`DesktopSubtitleWindow`）在深色半透明浮层外周使用 `setStroke(dp(1), 0x2EFFFFFF)`（1dp 约 18% 白描边）提升多背景边缘可辨度。

---

## 5. 图标库规范（Iconography）

- **主图标库**：Material Icons Extended（`androidx.compose.material:material-icons-extended`）。
  - 常规使用 `Icons.Default.*`（Filled 风格，如 `PlayArrow`、`Pause`、`SkipPrevious`、`SkipNext`、`Replay10`、`Forward10`、`Search`、`Close`、`MoreVert`、`Check`、`ArrowDropDown`、`Visibility`、`VisibilityOff`、`Tune`、`Refresh`、`SelectAll`、`Deselect`、`LibraryAdd`、`CollectionsBookmark` 等）。
  - 具方向语义的图标优先使用 `Icons.AutoMirrored.Filled.*`（`ArrowBack`、`QueueMusic`、`PlaylistAdd`、`InsertDriveFile`）。
  - 轻量辅助指示使用 `Icons.Outlined.*`（如 `Icons.Outlined.Timer`、`Icons.Outlined.ErrorOutline`）。
- **原生视图矢量资源（`res/drawable/`）**：脱离 Compose 的 `WindowManager` 悬浮字幕窗统一使用内置 24dp 可着色矢量图标 `ic_desktop_skip_previous.xml`、`ic_desktop_play.xml`、`ic_desktop_pause.xml`、`ic_desktop_skip_next.xml`、`ic_desktop_lock.xml`、`ic_desktop_close.xml`，禁止直接引用样式不统一的 `android.R.drawable.*`。
- **图标无障碍**：带伴随文字的装饰性图标传 `contentDescription = null`；纯图标按钮与状态操作必须引用 `strings*.xml` 中的 `a11y_*` 字符串。

---

## 6. 动效原则（Motion & Transitions）

基于 `Motion`（`ui/theme/Motion.kt`）统一定义节奏与物理反馈：

- **时长分档**：
  - `Motion.short = 150ms`：元素退场、轻量切换。
  - `Motion.medium = 300ms`：元素入场、底栏/覆盖层显隐、字幕高亮色渐变。
  - `Motion.long = 450ms`：主题种子色（含封面取色）全局过渡。
  - 进度条平滑追随：`tween(400, easing = LinearEasing)`（`RoundedProgress` 与 `MiniPlayerCard`）。
- **缓动与弹簧曲线**：
  - 入场（`enterSpec`）：`Motion.enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)`（快启稳落）。
  - 退场（`exitSpec`）：`Motion.exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)`（干脆收尾）。
  - 按压反馈（`pressSpec`）：`spring(dampingRatio = 0.6f)`，配合 `Modifier.pressScale` 缩放至 `0.96f`。
  - 位移弹性（`moveSpec`）：`spring(stiffness = Spring.StiffnessMediumLow)`。
- **交互一致性与手势规范**：
  - **保留水波纹**：所有标准交互按钮（含 `PlayButton`、`SecondaryAction`）保留 Material 默认点击涟漪，禁止设置 `indication = null`。
  - **共享元素过渡**：`MiniPlayer` 与 `NowPlayingScreen` 封面共享 `"player-cover"`，间距装饰必须放在 `sharedElement` 修饰符外部，防止过渡跳变。
  - **双向嵌套滚动接力**：`WorkFileBrowser` 与 `WorkPageScrollConnection` 使用 Compose 样条衰减曲线（`rememberSplineBasedDecay()`），向下优先折叠外层再带动文件列表，向上优先滚回文件顶部再将剩余速度交给外层。
- **系统减动效尊重（强制）**：所有显式动画必须读取 `LocalReduceMotion.current`（读取系统 `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`），开启减少动效时动画规格立即退化为 `snap()`、转场退化为 `EnterTransition.None` / `ExitTransition.None` 并禁用图片 `crossfade`。
