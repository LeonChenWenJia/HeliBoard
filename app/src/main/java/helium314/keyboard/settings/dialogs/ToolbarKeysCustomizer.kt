// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.checkAndConvertCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.getDefaultCodeForToolbarKey
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.utils.getCodeForToolbarKeyLongClick
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.readCustomKeyCodes
import helium314.keyboard.latin.utils.defaultFlickCodes
import helium314.keyboard.latin.utils.readFlickCodes
import helium314.keyboard.latin.utils.writeFlickCodes
import helium314.keyboard.latin.utils.clearFlickCodes
import helium314.keyboard.latin.utils.writeCustomKeyCodes
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.latin.utils.previewDark
import androidx.core.content.edit
import helium314.keyboard.settings.GetIconOrEmpty

@Composable
fun ToolbarKeysCustomizer(
    key: String,
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showKeyCustomizer: ToolbarKey? by rememberSaveable { mutableStateOf(null) }
    var showDeletePrefConfirmDialog by rememberSaveable { mutableStateOf(false) }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        cancelButtonText = stringResource(R.string.dialog_close),
        confirmButtonText = null,
        onConfirmed = { },
        neutralButtonText = if (readCustomKeyCodes(prefs).isNotEmpty() || readFlickCodes(prefs).isNotEmpty()) stringResource(R.string.button_default) else null,
        onNeutral = { showDeletePrefConfirmDialog = true },
        title = { Text(stringResource(R.string.customize_toolbar_key_codes)) },
        content = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(ToolbarKey.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showKeyCustomizer = it }.fillParentMaxWidth()
                    ) {
                        KeyboardIconsSet.instance.GetIconOrEmpty(it.name)
                        Text(it.name.lowercase().getStringResourceOrName("", ctx))
                    }
                }
            }
        },
    )
    if (showKeyCustomizer != null) {
        val shownKey = showKeyCustomizer
        if (shownKey != null)
            ToolbarKeyCustomizer(shownKey) { showKeyCustomizer = null }
    }
    if (showDeletePrefConfirmDialog)
        ConfirmationDialog(
            onDismissRequest = { showDeletePrefConfirmDialog = false },
            onConfirmed = {
                showDeletePrefConfirmDialog = false
                onDismissRequest()
                prefs.edit { remove(key) }
                clearFlickCodes(prefs)
            },
            content = { Text(stringResource(R.string.customize_toolbar_key_code_reset_message)) }
        )
}

@Composable
private fun ToolbarKeyCustomizer(
    key: ToolbarKey,
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var code by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(getCodeForToolbarKey(key).toString())) }
    // null = no action
    var longPress: Int? by rememberSaveable { mutableStateOf(getCodeForToolbarKeyLongClick(key).takeIf { it != KeyCode.UNSPECIFIED }) }
    val flick = readFlickCodes(prefs)[key]
    var flickUp: Int? by rememberSaveable { mutableStateOf(flick?.first) }
    var flickDown: Int? by rememberSaveable { mutableStateOf(flick?.second) }
    var picking: Int by rememberSaveable { mutableStateOf(0) } // 1 long press, 2 flick up, 3 flick down
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = {
            val codes = readCustomKeyCodes(prefs)
            codes[key] = checkCode(code) to (longPress ?: KeyCode.UNSPECIFIED)
            writeCustomKeyCodes(prefs, codes)
            val flicks = readFlickCodes(prefs)
            if (flickUp == null && flickDown == null && key !in defaultFlickCodes) flicks.remove(key)
            else flicks[key] = flickUp to flickDown
            writeFlickCodes(prefs, flicks)
        },
        checkOk = { checkCode(code) != null },
        neutralButtonText = if (readCustomKeyCodes(prefs).containsKey(key) || readFlickCodes(prefs).containsKey(key))
                stringResource(R.string.button_default)
            else null,
        onNeutral = {
            onDismissRequest()
            val keys = readCustomKeyCodes(prefs)
            keys.remove(key)
            writeCustomKeyCodes(prefs, keys)
            val flicks = readFlickCodes(prefs)
            flicks.remove(key)
            writeFlickCodes(prefs, flicks)
        },
        title = { Text(key.name.lowercase().getStringResourceOrName("", ctx)) },
        content = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.key_code), Modifier.weight(0.5f))
                    TextField(
                        value = code,
                        onValueChange = { code = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.5f)
                    )
                }
                ActionRow(stringResource(R.string.long_press_code), longPress) { picking = 1 }
                ActionRow(stringResource(R.string.flick_up_code), flickUp) { picking = 2 }
                ActionRow(stringResource(R.string.flick_down_code), flickDown) { picking = 3 }
            }
        },
    )
    if (picking != 0)
        ToolbarActionPicker(
            current = when (picking) { 1 -> longPress; 2 -> flickUp; else -> flickDown },
            onDismissRequest = { picking = 0 },
            onPick = {
                when (picking) { 1 -> longPress = it; 2 -> flickUp = it; else -> flickDown = it }
                picking = 0
            }
        )
}

/** key code of the first (non-hidden) toolbar key that has this code, or null */
private fun toolbarKeyForCode(code: Int) = pickableToolbarKeys.firstOrNull { getDefaultCodeForToolbarKey(it) == code }

private val pickableToolbarKeys = ToolbarKey.entries.filter { it != ToolbarKey.CLOSE_HISTORY && it != ToolbarKey.BACKGROUND_GATHERING }

@Composable
private fun ActionRow(label: String, code: Int?, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(label, Modifier.weight(0.5f))
        Row(Modifier.weight(0.5f), verticalAlignment = Alignment.CenterVertically) {
            val toolbarKey = code?.let { toolbarKeyForCode(it) }
            if (toolbarKey != null) KeyboardIconsSet.instance.GetIconOrEmpty(toolbarKey.name)
            Text(
                when {
                    code == null -> stringResource(R.string.action_none)
                    toolbarKey != null -> toolbarKey.name.lowercase().getStringResourceOrName("", ctx)
                    else -> code.toString()
                }
            )
        }
    }
}

/** simple single-choice list of toolbar actions, styled like the toolbar key list; "None" first */
@Composable
private fun ToolbarActionPicker(current: Int?, onDismissRequest: () -> Unit, onPick: (Int?) -> Unit) {
    val ctx = LocalContext.current
    val currentKey = current?.let { toolbarKeyForCode(it) }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        cancelButtonText = stringResource(R.string.dialog_close),
        confirmButtonText = null,
        onConfirmed = { },
        title = { Text(stringResource(R.string.customize_toolbar_key_codes)) },
        content = {
            LazyColumn {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onPick(null) }.fillParentMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_none), Modifier.weight(1f))
                        RadioButton(selected = current == null, onClick = { onPick(null) })
                    }
                }
                items(pickableToolbarKeys) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onPick(getDefaultCodeForToolbarKey(it)) }.fillParentMaxWidth()
                    ) {
                        KeyboardIconsSet.instance.GetIconOrEmpty(it.name)
                        Text(it.name.lowercase().getStringResourceOrName("", ctx), Modifier.weight(1f))
                        RadioButton(selected = it == currentKey, onClick = { onPick(getDefaultCodeForToolbarKey(it)) })
                    }
                }
            }
        },
    )
}

private fun checkCode(code: TextFieldValue, longPress: Boolean = false) = runCatching {
    code.text.toIntOrNull()?.takeIf { it.checkAndConvertCode(longPress) <= Char.MAX_VALUE.code }
}.getOrNull()

@Preview
@Composable
private fun PreviewToolbarKeyCustomizer() {
    initPreview(LocalContext.current)
    ToolbarKeyCustomizer(ToolbarKey.CUT) { }
}

@Preview
@Composable
private fun PreviewToolbarKeysCustomizer() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        ToolbarKeysCustomizer("") { }
    }
}
