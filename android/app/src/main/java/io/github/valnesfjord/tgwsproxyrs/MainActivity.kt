package io.github.valnesfjord.tgwsproxyrs

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.valnesfjord.tgwsproxyrs.ui.ProxyScreen
import io.github.valnesfjord.tgwsproxyrs.ui.TgWsTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ProxyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { requestBatteryExemptionOnce() }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    requestBatteryExemptionOnce()
                }
            }

            TgWsTheme {
                ProxyScreen(viewModel)
            }
        }
    }

    /**
     * Stock Android leaves a foreground service its network in Doze, but vendor
     * ROMs such as MagicOS or MIUI still kill or restrict optimised apps while
     * the screen is off, which is when the proxy "randomly" stops.  The proxy
     * holds no wakelock, so the exemption costs no extra battery.  Asked once;
     * the user can still change it in the app's battery settings.
     */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemptionOnce() {
        val prefs = getSharedPreferences(ProxyService.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_BATTERY_ASKED, false)) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit { putBoolean(PREF_BATTERY_ASKED, true) }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:$packageName".toUri(),
        )
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }

    override fun onResume() {
        super.onResume()
        ProxyBridge.syncFromNative()
    }

    private companion object {
        const val PREF_BATTERY_ASKED = "battery_exemption_asked"
    }
}
