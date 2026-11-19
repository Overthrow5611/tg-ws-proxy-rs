package io.github.valnesfjord.tgwsproxyrs

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color

/**
 * Launcher icon variants, one `<activity-alias>` each in the manifest.  Exactly
 * one alias is enabled at a time; [BLUE] is the one the manifest enables, so a
 * fresh install (or a launcher that ignores the switch) shows it.
 */
enum class AppIcon(
    private val alias: String,
    @StringRes val label: Int,
    val background: Color,
    val glyph: Color,
) {
    BLUE("IconBlue", R.string.icon_blue, Color(0xFF2A6FDB), Color.White),
    DARK("IconDark", R.string.icon_dark, Color(0xFF12161C), Color(0xFF64A8FF)),
    LIGHT("IconLight", R.string.icon_light, Color(0xFFF2F5FA), Color(0xFF2A6FDB)),
    GREEN("IconGreen", R.string.icon_green, Color(0xFF2E8B57), Color.White),
    ;

    private fun component(context: Context) =
        ComponentName(context.packageName, "io.github.valnesfjord.tgwsproxyrs.$alias")

    companion object {
        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull {
                pm.getComponentEnabledSetting(it.component(context)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } ?: BLUE
        }

        /**
         * Enable [icon]'s alias before disabling the others, so there is never
         * a moment with no launcher entry at all.  Launchers pick the change up
         * after a few seconds; some drop the old home-screen shortcut.
         */
        fun apply(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                icon.component(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            entries.filter { it != icon }.forEach {
                pm.setComponentEnabledSetting(
                    it.component(context),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
