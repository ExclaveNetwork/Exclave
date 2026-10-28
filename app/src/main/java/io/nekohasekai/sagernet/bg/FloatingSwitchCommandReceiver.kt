package io.nekohasekai.sagernet.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Routes overlay actions to the VPN process, which exclusively owns the system window. */
class FloatingSwitchCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FloatingSwitchButton.handleCommand(context.applicationContext, intent.action)
    }
}
