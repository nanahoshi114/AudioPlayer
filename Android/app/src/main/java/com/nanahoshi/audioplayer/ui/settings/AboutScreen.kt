package com.nanahoshi.audioplayer.ui.settings

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.*
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class InstalledAppVersion(val name: String, val code: Long) {
    val display get() = if (name.isBlank()) "未知版本" else name
}

@Suppress("DEPRECATION")
internal fun installedAppVersion(context: Context): InstalledAppVersion {
    val info = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        else context.packageManager.getPackageInfo(context.packageName, 0)
    return InstalledAppVersion(info.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(info))
}

internal sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class Ready(val release: AppRelease, val relation: UpdateRelation) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

class AboutViewModel(app: Application) : AndroidViewModel(app) {
    internal val current = installedAppVersion(app)
    private val updates = AppUpdateClient()
    internal var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle); private set

    fun checkUpdates() {
        if (status == UpdateStatus.Checking) return
        status = UpdateStatus.Checking
        viewModelScope.launch {
            try {
                val release = updates.latest()
                status = UpdateStatus.Ready(release, updateRelation(current.name, release))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                status = UpdateStatus.Failed(if (error is AppUpdateException) error.message.orEmpty() else "检查更新失败，请稍后重试")
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit, viewModel: AboutViewModel = viewModel()) {
    val context = LocalContext.current
    AboutContent(viewModel.current, viewModel.status, onBack, viewModel::checkUpdates) { page ->
        try { openReleaseInBrowser(context, page) }
        catch (_: ActivityNotFoundException) { (context.applicationContext as Application).graph().messages.value = "没有可用的浏览器" }
        catch (_: SecurityException) { (context.applicationContext as Application).graph().messages.value = "无法打开浏览器，请稍后重试" }
    }
}

@Composable
internal fun AboutContent(current: InstalledAppVersion, status: UpdateStatus, onBack: () -> Unit, onCheck: () -> Unit, onOpenPage: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "关于", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("音频播放器", style = MaterialTheme.typography.headlineSmall)
                    Text("版本 ${current.display}（构建 ${current.code}）", style = MaterialTheme.typography.bodyLarge)
                }
            }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("版本更新", style = MaterialTheme.typography.titleMedium)
                    when (status) {
                        UpdateStatus.Idle -> Text("从 GitHub 检查最新正式版本")
                        UpdateStatus.Checking -> { Text("正在检查更新…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        is UpdateStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
                        is UpdateStatus.Ready -> {
                            Text(when (status.relation) {
                                UpdateRelation.AVAILABLE -> "发现新版本 ${status.release.versionName}"
                                UpdateRelation.CURRENT -> "当前已是最新版本"
                                UpdateRelation.LOCAL_NEWER -> "本机版本比发布版本新"
                                UpdateRelation.UNKNOWN -> "无法自动比较版本，请在发布页确认"
                            }, style = MaterialTheme.typography.titleSmall)
                            Text("最新发布：${status.release.versionName ?: status.release.tag}")
                        }
                    }
                    Button(onClick = onCheck, enabled = status != UpdateStatus.Checking, modifier = Modifier.fillMaxWidth()) { Text("检查更新") }
                    val page = (status as? UpdateStatus.Ready)?.release?.pageUrl ?: APP_RELEASES_URL
                    OutlinedButton(onClick = { onOpenPage(page) }, modifier = Modifier.fillMaxWidth()) { Text("在浏览器打开发布页") }
                    Text("请在浏览器的发布页下载 APK 并安装。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** 先以不属于任何 App Link 的域名查找网页浏览器，再把发布页交给选中的浏览器。探测地址不发起网络请求。 */
@Suppress("DEPRECATION")
private fun openReleaseInBrowser(context: Context, page: String) {
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://browser.invalid/")).addCategory(Intent.CATEGORY_BROWSABLE)
    val manager = context.packageManager
    val browsers = manager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.distinct()
    if (browsers.isEmpty()) throw ActivityNotFoundException()
    val preferred = manager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it in browsers }
    fun intent(pkg: String) = Intent(Intent.ACTION_VIEW, Uri.parse(page)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg)
    if (preferred != null || browsers.size == 1) context.startActivity(intent(preferred ?: browsers.single()))
    else {
        val chooser = Intent.createChooser(intent(browsers.first()), "选择浏览器")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, browsers.drop(1).map(::intent).toTypedArray())
        context.startActivity(chooser)
    }
}
