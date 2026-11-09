// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.edit
import helium314.keyboard.latin.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A custom toolbar key that starts an app shortcut (an intent returned by an app's CREATE_SHORTCUT activity). */
data class ToolbarShortcut(val id: String, val label: String, val intentUri: String)

private const val PREF_TOOLBAR_SHORTCUTS = "toolbar_shortcuts"

fun readToolbarShortcuts(prefs: SharedPreferences): List<ToolbarShortcut> = runCatching {
    val array = JSONArray(prefs.getString(PREF_TOOLBAR_SHORTCUTS, "[]"))
    (0 until array.length()).map {
        val o = array.getJSONObject(it)
        ToolbarShortcut(o.getString("id"), o.getString("label"), o.getString("intent"))
    }
}.getOrDefault(emptyList())

private fun writeToolbarShortcuts(prefs: SharedPreferences, shortcuts: List<ToolbarShortcut>) {
    val array = JSONArray()
    shortcuts.forEach { array.put(JSONObject().put("id", it.id).put("label", it.label).put("intent", it.intentUri)) }
    prefs.edit { putString(PREF_TOOLBAR_SHORTCUTS, array.toString()) }
}

private fun iconFile(context: Context, id: String) = File(File(context.filesDir, "shortcut_icons"), "$id.png")

/** Handles the result of a CREATE_SHORTCUT activity and saves the shortcut. Returns false if the result has no usable intent. */
fun addToolbarShortcutFromResult(context: Context, data: Intent?): Boolean {
    val intent = data?.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) ?: return false
    val label = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: intent.component?.packageName ?: "?"
    val id = System.currentTimeMillis().toString()
    val icon: Bitmap? = data.getParcelableExtra<Bitmap>(Intent.EXTRA_SHORTCUT_ICON) ?: run {
        val res = data.getParcelableExtra<Intent.ShortcutIconResource>(Intent.EXTRA_SHORTCUT_ICON_RESOURCE) ?: return@run null
        runCatching {
            val resources = context.packageManager.getResourcesForApplication(res.packageName)
            val d = resources.getDrawableForDensity(resources.getIdentifier(res.resourceName, null, null), 480, null)
            d?.toBitmap()
        }.getOrNull()
    }
    icon?.let {
        val file = iconFile(context, id)
        file.parentFile?.mkdirs()
        file.outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }
    }
    val prefs = context.prefs()
    writeToolbarShortcuts(prefs, readToolbarShortcuts(prefs) + ToolbarShortcut(id, label, intent.toUri(Intent.URI_INTENT_SCHEME)))
    return true
}

fun removeToolbarShortcut(context: Context, id: String) {
    val prefs = context.prefs()
    writeToolbarShortcuts(prefs, readToolbarShortcuts(prefs).filterNot { it.id == id })
    iconFile(context, id).delete()
}

/** Activities of installed apps that can create a shortcut. */
fun queryShortcutCreators(context: Context) =
    context.packageManager.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT), PackageManager.MATCH_DEFAULT_ONLY)
        .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }

fun createShortcutToolbarKey(context: Context, shortcut: ToolbarShortcut): ImageButton {
    val button = ImageButton(context, null, R.attr.suggestionWordStyle)
    button.scaleType = ImageView.ScaleType.CENTER
    button.tag = shortcut
    button.contentDescription = shortcut.label
    val file = iconFile(context, shortcut.id)
    val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    if (bitmap != null) {
        val size = context.resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height) * 2 / 3
        button.setImageBitmap(Bitmap.createScaledBitmap(bitmap, size, size, true))
        // keep the app's own colors, so no tint
        button.clearColorFilter()
    }
    return button
}

fun launchToolbarShortcut(context: Context, shortcut: ToolbarShortcut) {
    try {
        val intent = Intent.parseUri(shortcut.intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.w("ToolbarShortcuts", "could not start shortcut ${shortcut.label}", e)
        Toast.makeText(context, context.getString(R.string.toolbar_shortcut_failed, shortcut.label), Toast.LENGTH_SHORT).show()
    }
}

private fun Drawable.toBitmap(): Bitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap
    val w = intrinsicWidth.coerceAtLeast(1)
    val h = intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, w, h)
    draw(canvas)
    return bitmap
}
