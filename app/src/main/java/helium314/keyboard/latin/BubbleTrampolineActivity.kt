// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

// TEMPORARY: setAutoExpandBubble only works while the posting app is in the foreground,
// so post the bubble from a transparent activity and close it shortly after
class BubbleTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // flick again while the bubble is up: close it instead of reopening (no broadcast, so the macro doesn't run)
        if (StuffReceiver.closeBubble(this)) { finish(); return }
        StuffReceiver.bubbleNotification(this, intent.getStringExtra("package"))
        // lets a MacroDroid "Intent Received" trigger chain further actions
        sendBroadcast(Intent("helium314.keyboard.latin.ACTION_BUBBLE_POSTED"))
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 600)
    }
}
