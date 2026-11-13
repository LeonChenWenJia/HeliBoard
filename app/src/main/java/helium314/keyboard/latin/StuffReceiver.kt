// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.ContactsContract
import android.provider.UserDictionary.Words
import androidx.core.content.ContextCompat
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.EmojiSearchActivity
import helium314.keyboard.latin.dictionary.ExpandableDictionaryUtils
import helium314.keyboard.latin.dictionary.UserBinaryDictionary
import helium314.keyboard.latin.dictionary.contacts.ContactsUtils
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.AppsUtils
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.FoldableUtils.FoldableObserver
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StuffReceiver(latinIME: LatinIME) {
    private val dictionaryChangeReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION) return
            Log.i(TAG, "dictionary changed")
            latinIME.resetSuggestMainDict()
        }
    }

    private val emojiSearchReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        private fun isImeClosed(intent: Intent) = intent.getBooleanExtra(EmojiSearchActivity.IME_CLOSED_KEY, false)

        private fun getSelectedEmoji(intent: Intent) = intent.getStringExtra(EmojiSearchActivity.EMOJI_KEY)

        private fun isEmojiSearch() = EmojiSearchActivity.decodePrivateImeOptions(latinIME.currentInputEditorInfo).height > 0

        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(
                TAG, "after activity closing. isEmojiSearch: ${isEmojiSearch()}. Intent: $intent" +
                    intent?.let { ". imeClosed: ${isImeClosed(intent)}. selected emoji: ${getSelectedEmoji(intent)}" }
            )
            if (intent?.action != EmojiSearchActivity.EMOJI_SEARCH_DONE_ACTION || isEmojiSearch()) return
            if (isImeClosed(intent)) {
                latinIME.requestHideSelf(0)
            } else {
                latinIME.mHandler.postDelayed({ KeyboardSwitcher.getInstance().setEmojiKeyboard() }, 100)
                if (intent.hasExtra(EmojiSearchActivity.EMOJI_KEY)) {
                    latinIME.onTextInput(intent.getStringExtra(EmojiSearchActivity.EMOJI_KEY))
                }
            }
        }
    }

    private val voiceInputReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_VOICE_INPUT) return
            Log.i(TAG, "voice input requested by broadcast")
            latinIME.startVoiceInput()
        }
    }

    private val floatingAppReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_OPEN_FLOATING_APP || context == null) return
            // TEMPORARY test hook: opt_keys/opt_vals put arbitrary int launch options into the bundle
            if (intent.getBooleanExtra("bubble_notif", false)) { bubbleNotification(context, intent.getStringExtra(EXTRA_PACKAGE)); return }
            if (intent.getBooleanExtra("bubble", false)) { bubbleTest(context, intent.getStringExtra(EXTRA_PACKAGE)); return }
            if (intent.getBooleanExtra("zoom_direct", false)) { zoomDirectTest(context, intent.getStringExtra(EXTRA_PACKAGE)); return }
            val keys = intent.getStringArrayExtra("opt_keys")
            val vals = intent.getIntArrayExtra("opt_vals")
            if (keys != null && vals != null) openFloatingAppTest(context, intent.getStringExtra(EXTRA_PACKAGE), keys, vals)
            else openFloatingApp(context, intent.getStringExtra(EXTRA_PACKAGE))
        }
    }

    private var appsReceiver: BroadcastReceiver? = null
    private var foldableObserver: FoldableObserver? = null
    private var personalDictObserver: ContentObserver? = null
    private var contactsObserver: ContentObserver? = null

    fun register(context: Context) {
        context.registerReceiver(ringerModeChangeReceiver, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION))

        val dictFilter = IntentFilter(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
        // RECEIVER_EXPORTED is necessary because apparently Android 15 (and others?) don't recognize
        // if the sender and receiver are the same app, see https://github.com/HeliBorg/HeliBoard/pull/1756
        ContextCompat.registerReceiver(context, dictionaryChangeReceiver, dictFilter, ContextCompat.RECEIVER_EXPORTED)

        ContextCompat.registerReceiver(context, emojiSearchReceiver, IntentFilter(EmojiSearchActivity.EMOJI_SEARCH_DONE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)

        // exported on purpose: lets automation apps (e.g. MacroDroid) trigger voice input
        ContextCompat.registerReceiver(context, voiceInputReceiver, IntentFilter(ACTION_VOICE_INPUT), ContextCompat.RECEIVER_EXPORTED)

        ContextCompat.registerReceiver(context, floatingAppReceiver, IntentFilter(ACTION_OPEN_FLOATING_APP), ContextCompat.RECEIVER_EXPORTED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            context.registerReceiver(restartAfterDeviceUnlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))

        if (FoldableUtils.isFoldable)
            foldableObserver = FoldableObserver(context)

        updateDictionaryObservers(context)
    }

    fun unregisterAll(context: Context) {
        context.unregisterReceiver(ringerModeChangeReceiver)
        foldableObserver?.unregister(context)
        foldableObserver = null
        context.unregisterReceiver(dictionaryChangeReceiver)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            context.unregisterReceiver(restartAfterDeviceUnlockReceiver)
        context.unregisterReceiver(emojiSearchReceiver)
        context.unregisterReceiver(voiceInputReceiver)
        unregisterAppsReceiver(context)
        unregisterPersonalDictObserver(context)
        unregisterContactsObserver(context)
    }

    fun updateDictionaryObservers(context: Context) {
        if (Settings.getValues().mUseAppsDictionary) registerAppsReceiver(context)
        else unregisterAppsReceiver(context)

        if (Settings.getValues().mUsePersonalizedDicts) registerPersonalDictObserver(context)
        else unregisterPersonalDictObserver(context)

        if (Settings.getValues().mUseContactsDictionary) registerContactsObserver(context)
        else unregisterContactsObserver(context)
    }

    private fun registerAppsReceiver(context: Context) {
        if (appsReceiver != null) return
        val appsFilter = IntentFilter(Intent.ACTION_PACKAGE_ADDED)
        appsFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        appsFilter.addDataScheme(SCHEME_PACKAGE)
        appsReceiver = Companion.appsReceiver
        context.registerReceiver(appsReceiver, appsFilter)
    }

    private fun unregisterAppsReceiver(context: Context) {
        if (appsReceiver == null) return
        context.unregisterReceiver(appsReceiver)
        appsReceiver = null
    }

    private fun registerPersonalDictObserver(context: Context) {
        if (personalDictObserver != null) return
        personalDictObserver = Companion.personalDictObserver
        context.contentResolver.registerContentObserver(Words.CONTENT_URI, true, Companion.personalDictObserver)
    }

    private fun unregisterPersonalDictObserver(context: Context) {
        if (personalDictObserver == null) return
        context.contentResolver.unregisterContentObserver(personalDictObserver!!)
        personalDictObserver = null
    }

    private fun registerContactsObserver(context: Context) {
        if (contactsObserver != null || !PermissionsUtil.checkAllPermissionsGranted(context, Manifest.permission.READ_CONTACTS))
            return
        contactsObserver = ContactsObserver(context)
        context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactsObserver!!)
    }

    private fun unregisterContactsObserver(context: Context) {
        if (contactsObserver == null) return
        context.contentResolver.unregisterContentObserver(contactsObserver!!)
        contactsObserver = null
    }

    private inner class ContactsObserver(val context: Context): ContentObserver(null) {
        override fun onChange(self: Boolean, uri: Uri?) {
            scope.launch {
                if (PermissionsUtil.checkAllPermissionsGranted(context, Manifest.permission.READ_CONTACTS))
                    ContactsUtils.onContactsChanged(context)
                else unregisterContactsObserver(context)
            }
        }
    }

    companion object {
        private val TAG = StuffReceiver::class.java.simpleName
        const val ACTION_VOICE_INPUT = "helium314.keyboard.latin.ACTION_VOICE_INPUT"
        const val ACTION_OPEN_FLOATING_APP = "helium314.keyboard.latin.ACTION_OPEN_FLOATING_APP"
        const val EXTRA_PACKAGE = "package"
        private const val KEEP_PACKAGE = "com.google.android.keep"
        // Oppo/ColorOS zoom window: ActivityOptions key, and OplusZoomWindowManager.FLAG_ON_START_DEFAULT_ZOOM_FROM_APP
        private const val OPLUS_ZOOM_LAUNCH_FLAGS = "android:activity.mZoomLaunchFlags"
        private const val OPLUS_ZOOM_FROM_APP = 18

        /** starts the launcher activity of [pkg] (default Google Keep) in an Oppo floating (zoom) window */
        fun zoomDirectTest(context: Context, pkg: String?) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg ?: KEEP_PACKAGE) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                val cls = Class.forName("com.oplus.zoomwindow.OplusZoomWindowManager")
                val mgr = cls.getMethod("getInstance").invoke(null)
                val m = cls.getMethod("startZoomWindow", Intent::class.java, android.os.Bundle::class.java, Int::class.javaPrimitiveType, String::class.java)
                val r = m.invoke(mgr, launch, android.os.Bundle(), android.os.Process.myUserHandle().hashCode(), context.packageName)
                Log.w(TAG, "FLOATTEST zoomDirect result=$r")
            } catch (e: Throwable) { Log.w(TAG, "FLOATTEST zoomDirect failed", e) }
        }

        /** TEMPORARY experiment: hidden ActivityOptions bubble keys, no reflection. Result must be checked by eye. */
        fun bubbleTest(context: Context, pkg: String?) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg ?: KEEP_PACKAGE) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = android.app.ActivityOptions.makeBasic().toBundle()
            options.putBoolean("android.activity.launchTypeBubble", true)
            options.putBoolean("android:activity.applyActivityFlagsForBubbles", true)
            Log.w(TAG, "BUBBLETEST launching ${launch.component}")
            try { context.startActivity(launch, options) } catch (e: Throwable) { Log.w(TAG, "BUBBLETEST failed", e) }
        }

        /** EXPERIMENT (option A): public notification bubble pointing at the launcher activity of [pkg]. Result must be checked by eye. */
        fun bubbleNotification(context: Context, pkg: String?) {
            val target = pkg ?: KEEP_PACKAGE
            var launch = context.packageManager.getLaunchIntentForPackage(target) ?: return
            // open the specific note; Keep claims keep.google.com links
            if (target == KEEP_PACKAGE)
                launch = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(KEEP_NOTE_URL)).setPackage(KEEP_PACKAGE)
            if (Build.VERSION.SDK_INT < 30) { Log.w(TAG, "BUBBLEA needs API 30"); return }
            try {
                val nm = context.getSystemService(android.app.NotificationManager::class.java)
                val channel = android.app.NotificationChannel(BUBBLE_CHANNEL, "Bubble launch", android.app.NotificationManager.IMPORTANCE_HIGH)
                channel.setAllowBubbles(true)
                nm.createNotificationChannel(channel)
                val shortcutId = "bubble_$target"
                val person = android.app.Person.Builder().setName("Keep").setKey(target).setImportant(true).build()
                val icon = targetIcon(context, target)
                val shortcut = android.content.pm.ShortcutInfo.Builder(context, shortcutId)
                    .setLongLived(true).setShortLabel("Keep").setIntent(launch).setPerson(person).setIcon(icon).build()
                context.getSystemService(android.content.pm.ShortcutManager::class.java).pushDynamicShortcut(shortcut)
                val pi = android.app.PendingIntent.getActivity(context, 0, launch,
                    android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                val meta = android.app.Notification.BubbleMetadata.Builder(pi, icon)
                    .setDesiredHeight(1200).setAutoExpandBubble(true).setSuppressNotification(true).build()
                val n = android.app.Notification.Builder(context, BUBBLE_CHANNEL)
                    .setSmallIcon(context.applicationInfo.icon)
                    .setShortcutId(shortcutId)
                    .setBubbleMetadata(meta)
                    .setStyle(android.app.Notification.MessagingStyle(person).addMessage("Open Keep", System.currentTimeMillis(), person))
                    .build()
                Log.w(TAG, "BUBBLEA posting, notifications enabled=${nm.areNotificationsEnabled()} bubbles=${nm.bubblePreference}")
                // a dismissed bubble is not re-shown when the same notification id is updated: cancel it and use a fresh id
                nm.cancel(lastBubbleId)
                lastBubbleId = BUBBLE_NOTIFICATION_ID + (System.currentTimeMillis() % 100000).toInt()
                nm.notify(lastBubbleId, n)
            } catch (e: Throwable) { Log.w(TAG, "BUBBLEA failed", e) }
        }
        // cancels our active bubble notification(s); returns true if there was one
        fun closeBubble(context: Context): Boolean {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            val active = nm.activeNotifications.filter { it.notification.channelId == BUBBLE_CHANNEL }
            active.forEach { nm.cancel(it.id) }
            return active.isNotEmpty()
        }
        // the target app's own launcher icon, drawn as an adaptive bitmap (falls back to our icon)
        private fun targetIcon(context: Context, pkg: String): android.graphics.drawable.Icon = try {
            val d = context.packageManager.getApplicationIcon(pkg)
            val size = 432
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            if (d is android.graphics.drawable.AdaptiveIconDrawable) {
                for (layer in listOf(d.background, d.foreground)) {
                    layer?.setBounds(0, 0, size, size)
                    layer?.draw(c)
                }
            } else {
                d.setBounds(0, 0, size, size)
                d.draw(c)
            }
            android.graphics.drawable.Icon.createWithAdaptiveBitmap(bmp)
        } catch (e: Throwable) {
            android.graphics.drawable.Icon.createWithResource(context, context.applicationInfo.icon)
        }
        private const val KEEP_NOTE_URL = "https://keep.google.com/u/0/#NOTE/1Sryq33-TdTU-CuDstkDdyfUE8SUqS_6C7F9jk5hXQ2_a-ao5YVhLIvbGmNJgZw"
        private const val BUBBLE_CHANNEL = "bubble_launch"
        private const val BUBBLE_NOTIFICATION_ID = 4242
        private var lastBubbleId = BUBBLE_NOTIFICATION_ID

        fun openFloatingAppTest(context: Context, pkg: String?, keys: Array<String>, vals: IntArray) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg ?: KEEP_PACKAGE) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = android.app.ActivityOptions.makeBasic().toBundle()
            keys.forEachIndexed { i, k -> if (i < vals.size) options.putInt(k, vals[i]) }
            Log.w(TAG, "FLOATTEST launching with ${keys.toList()} ${vals.toList()}")
            try { context.startActivity(launch, options) } catch (e: Exception) { Log.w(TAG, "FLOATTEST failed", e) }
        }

        fun openFloatingApp(context: Context, pkg: String?) {
            val target = pkg ?: KEEP_PACKAGE
            val launch = context.packageManager.getLaunchIntentForPackage(target)
            if (launch == null) {
                Log.w(TAG, "no launch intent for $target")
                return
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = android.app.ActivityOptions.makeBasic().toBundle()
            options.putInt(OPLUS_ZOOM_LAUNCH_FLAGS, OPLUS_ZOOM_FROM_APP)
            try {
                context.startActivity(launch, options)
            } catch (e: Exception) {
                Log.w(TAG, "could not open $target in floating window", e)
            }
        }
        // The name of the scheme used by the Package Manager to warn of a new package installation, replacement or removal.
        private const val SCHEME_PACKAGE: String = "package"
        private val scope = CoroutineScope(Dispatchers.IO)

        private val ringerModeChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action: String? = intent.action
                if (AudioManager.RINGER_MODE_CHANGED_ACTION == action) {
                    var dnd: Boolean
                    try {
                        dnd = android.provider.Settings.Global.getInt(context.contentResolver, "zen_mode") != 0
                    } catch (_: android.provider.Settings.SettingNotFoundException) {
                        dnd = false
                        Log.w(TAG, "zen_mode setting not found, assuming disabled")
                    }
                    Log.i(TAG, "ringer mode changed, zen_mode on: $dnd")
                    AudioAndHapticFeedbackManager.getInstance().onRingerModeChanged(dnd)
                }
            }
        }

        private val restartAfterDeviceUnlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                val action = intent.action
                // Restart the keyboard if credential encrypted storage is unlocked. This reloads the
                // dictionary and other data from credential-encrypted storage (with the onCreate()
                // method).
                if (Intent.ACTION_USER_UNLOCKED == action) {
                    val myPid = Process.myPid()
                    Log.i(TAG, "Killing my process: pid=$myPid")
                    Process.killProcess(myPid)
                } else {
                    Log.e(TAG, "Unexpected intent $intent")
                }
            }
        }

        private val appsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.getBooleanExtra(Intent.EXTRA_REPLACING, false) != true)
                    scope.launch { AppsUtils.onAppsChanged(context.packageManager) }
            }
        }

        private val personalDictObserver = object : ContentObserver(null) {
            override fun onChange(self: Boolean, uri: Uri?) {
                // just always reload
                ExpandableDictionaryUtils.setNeedsUpdate(UserBinaryDictionary.NAME)
            }
        }
    }
}
