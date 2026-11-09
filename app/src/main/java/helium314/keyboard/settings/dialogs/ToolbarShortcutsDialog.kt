// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.addToolbarShortcutFromResult
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.queryShortcutCreators
import helium314.keyboard.latin.utils.readToolbarShortcuts
import helium314.keyboard.latin.utils.removeToolbarShortcut

/** Lists the custom shortcut keys, lets the user remove them, and add new ones via an app's CREATE_SHORTCUT activity. */
@Composable
fun ToolbarShortcutsDialog(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) } // bump to re-read the shortcut list
    var showPicker by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        if (addToolbarShortcutFromResult(ctx, result.data)) {
            version++
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        } else Toast.makeText(ctx, R.string.toolbar_shortcuts_unsupported, Toast.LENGTH_LONG).show()
    }
    val shortcuts = readToolbarShortcuts(ctx.prefs()).also { version.let { _ -> } }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        cancelButtonText = stringResource(R.string.dialog_close),
        confirmButtonText = stringResource(R.string.toolbar_shortcuts_add),
        onConfirmed = { showPicker = true },
        title = { Text(stringResource(R.string.toolbar_shortcuts)) },
        content = {
            if (shortcuts.isEmpty()) Text(stringResource(R.string.toolbar_shortcuts_empty))
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shortcuts, key = { it.id }) { shortcut ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(shortcut.label, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            removeToolbarShortcut(ctx, shortcut.id)
                            version++
                            KeyboardSwitcher.getInstance().setThemeNeedsReload()
                        }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        },
    )
    if (showPicker) {
        val creators = remember { queryShortcutCreators(ctx) }
        ThreeButtonAlertDialog(
            onDismissRequest = { showPicker = false },
            onConfirmed = { },
            confirmButtonText = null,
            title = { Text(stringResource(R.string.toolbar_shortcuts_add)) },
            content = {
                LazyColumn {
                    items(creators) { info ->
                        val pm = ctx.packageManager
                        Text(
                            // the activity label is often generic, so show the app name too
                            "${info.loadLabel(pm)} (${info.activityInfo.applicationInfo.loadLabel(pm)})",
                            modifier = Modifier.fillMaxWidth().clickable {
                                showPicker = false
                                launcher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT)
                                    .setClassName(info.activityInfo.packageName, info.activityInfo.name))
                            }.padding(vertical = 10.dp)
                        )
                    }
                }
            },
        )
    }
}
