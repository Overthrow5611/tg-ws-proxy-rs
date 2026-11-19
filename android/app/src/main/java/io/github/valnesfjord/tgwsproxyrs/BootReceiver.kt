package io.github.valnesfjord.tgwsproxyrs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the proxy back after a reboot or an app update if the user had left
 * it running, so Telegram is not stuck on "Connecting…" until the app is
 * opened.  Both broadcasts are exempt from the background foreground-service
 * start restrictions, and `specialUse` is not among the types Android 15
 * forbids from BOOT_COMPLETED.  Vendor ROMs (MagicOS, MIUI) only deliver
 * them when the app's autostart permission is on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val prefs = context.getSharedPreferences(ProxyService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ProxyService.PREF_WAS_RUNNING, false)) return
        val args = prefs.getString(ProxyService.PREF_ARGS, null) ?: return
        if (NativeProxy.nativeIsRunning()) return
        try {
            ProxyService.start(context, args)
        } catch (_: RuntimeException) {
            // ForegroundServiceStartNotAllowedException on a ROM that does not
            // honour the exemption; the user starts it from the app instead.
        }
    }
}
