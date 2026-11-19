package io.github.valnesfjord.tgwsproxyrs

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProxyViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(ProxyService.PREFS, 0)

    private val _args = MutableStateFlow(
        prefs.getString(ProxyService.PREF_ARGS, null) ?: ProxyService.DEFAULT_ARGS,
    )
    val args: StateFlow<String> = _args.asStateFlow()

    val running = ProxyBridge.running
    val restarting = ProxyBridge.restarting
    val tgLink = ProxyBridge.tgLink
    val error = ProxyBridge.error

    /**
     * Bounded log buffer the UI observes directly.  A `StateFlow<List<String>>`
     * copied the whole list to append and copied it again to trim, i.e. up to
     * a thousand element copies per line on a chatty proxy; a snapshot list
     * shares its backing structure between versions and lets Compose observe
     * the single append, so only the rows on screen recompose.
     */
    private val _logs = mutableStateListOf<LogLine>()
    val logs: List<LogLine> = _logs

    /** Never reused, never reordered — see [LogLine]. */
    private var nextLogId = 0L

    private var autoOpenPending = false

    init {
        ProxyBridge.syncFromNative()
        viewModelScope.launch {
            ProxyBridge.logs.collect { line ->
                _logs.add(LogLine(nextLogId++, line))
                if (_logs.size > MAX_LOG_LINES) {
                    _logs.removeAt(0)
                }
            }
        }
        viewModelScope.launch {
            ProxyBridge.tgLink.collect { link ->
                if (link != null && autoOpenPending) {
                    autoOpenPending = false
                    openLink()
                }
            }
        }
    }

    fun updateArgs(value: String) {
        _args.value = value
        prefs.edit { putString(ProxyService.PREF_ARGS, value) }
    }

    /** Swap in a preset, keeping the current choice of detailed logging. */
    fun applyPreset(preset: Preset) {
        updateArgs(if (isVerbose(_args.value)) withoutQuiet(preset.args) else preset.args)
    }

    fun setVerbose(verbose: Boolean) {
        val current = _args.value
        if (verbose == isVerbose(current)) return
        updateArgs(if (verbose) withoutQuiet(current) else "--quiet $current")
    }

    fun copyLog() {
        val app = getApplication<Application>()
        val clipboard = app.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(app.getString(R.string.log_label), logText()))
        // Android 13+ confirms clipboard writes itself.
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(app, R.string.log_copied, Toast.LENGTH_SHORT).show()
        }
    }

    fun shareLog() {
        val app = getApplication<Application>()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logText())
        }
        val chooser = Intent.createChooser(send, app.getString(R.string.share_log_title)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            app.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun logText() = _logs.joinToString("\n") { it.text }

    fun start() {
        ProxyBridge.clearError()
        autoOpenPending = true
        ProxyService.start(getApplication(), _args.value)
    }

    fun stop() {
        ProxyService.stop(getApplication())
    }

    fun openLink() {
        val link = tgLink.value ?: return
        val intent = Intent(Intent.ACTION_VIEW, link.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            getApplication<Application>().startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            ProxyBridge.reportMessage(getApplication<Application>().getString(R.string.telegram_missing))
        }
    }

    fun openRepo() {
        val uri = "https://github.com/valnesfjord/tg-ws-proxy-rs".toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            getApplication<Application>().startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            ProxyBridge.reportMessage(getApplication<Application>().getString(R.string.browser_missing))
        }
    }

    data class Preset(@StringRes val label: Int, val args: String)

    companion object {
        private const val MAX_LOG_LINES = 500

        val PRESETS = listOf(
            Preset(R.string.preset_direct, ProxyService.DEFAULT_ARGS),
            Preset(R.string.preset_direct_cf, ProxyService.DIRECT_CF_ARGS),
        )

        private val QUIET = Regex("""(^|\s)--quiet(?=\s|$)""")
        private val SPACES = Regex("""\s+""")

        fun isVerbose(args: String) = !QUIET.containsMatchIn(args)

        fun withoutQuiet(args: String) = QUIET.replace(args, " ").replace(SPACES, " ").trim()

        /** Whether [args] is [preset] up to `--quiet` and spacing. */
        fun matches(args: String, preset: Preset) =
            withoutQuiet(args) == withoutQuiet(preset.args)
    }
}
