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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.*
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.asString
import com.nanahoshi.audioplayer.text.userTextOr
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class InstalledAppVersion(val name: String, val code: Long) {
    val display get() = name
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
    data class Failed(val message: TextRef) : UpdateStatus
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
                status = UpdateStatus.Failed(error.userTextOr(R.string.about_check_failed))
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit, viewModel: AboutViewModel = viewModel()) {
    val context = LocalContext.current
    AboutContent(viewModel.current, viewModel.status, onBack, viewModel::checkUpdates) { page ->
        try { openReleaseInBrowser(context, page) }
        catch (_: ActivityNotFoundException) { (context.applicationContext as Application).graph().show(R.string.about_no_browser) }
        catch (_: SecurityException) { (context.applicationContext as Application).graph().show(R.string.about_open_browser_failed) }
    }
}

@Composable
internal fun AboutContent(current: InstalledAppVersion, status: UpdateStatus, onBack: () -> Unit, onCheck: () -> Unit, onOpenPage: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.about_title), onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.about_app_name), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.about_version, current.display.ifBlank { stringResource(R.string.about_unknown_version) }, current.code), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.about_update_section), style = MaterialTheme.typography.titleMedium)
                    when (status) {
                        UpdateStatus.Idle -> Text(stringResource(R.string.about_update_idle))
                        UpdateStatus.Checking -> { Text(stringResource(R.string.about_checking)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        is UpdateStatus.Failed -> Text(status.message.asString(), color = MaterialTheme.colorScheme.error)
                        is UpdateStatus.Ready -> {
                            Text(when (status.relation) {
                                UpdateRelation.AVAILABLE -> stringResource(R.string.about_available, status.release.versionName ?: "")
                                UpdateRelation.CURRENT -> stringResource(R.string.about_current)
                                UpdateRelation.LOCAL_NEWER -> stringResource(R.string.about_local_newer)
                                UpdateRelation.UNKNOWN -> stringResource(R.string.about_unknown_relation)
                            }, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.about_latest_release, status.release.versionName ?: status.release.tag ?: ""))
                        }
                    }
                    Button(onClick = onCheck, enabled = status != UpdateStatus.Checking, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.about_check_action)) }
                    val page = (status as? UpdateStatus.Ready)?.release?.pageUrl ?: APP_RELEASES_URL
                    OutlinedButton(onClick = { onOpenPage(page) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.about_open_page)) }
                    Text(stringResource(R.string.about_install_note), style = MaterialTheme.typography.bodySmall)
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
        val chooser = Intent.createChooser(intent(browsers.first()), context.getString(R.string.about_browser_chooser))
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, browsers.drop(1).map(::intent).toTypedArray())
        context.startActivity(chooser)
    }
}
