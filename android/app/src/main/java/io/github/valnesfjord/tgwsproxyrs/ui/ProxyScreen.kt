package io.github.valnesfjord.tgwsproxyrs.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valnesfjord.tgwsproxyrs.AppIcon
import io.github.valnesfjord.tgwsproxyrs.ProxyViewModel
import io.github.valnesfjord.tgwsproxyrs.R
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyScreen(viewModel: ProxyViewModel) {
    val args by viewModel.args.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val restarting by viewModel.restarting.collectAsStateWithLifecycle()
    val tgLink by viewModel.tgLink.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val netCheck by viewModel.netCheck.collectAsStateWithLifecycle()
    val netChecking by viewModel.netChecking.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val active = running || restarting
    val context = LocalContext.current
    var appIcon by remember { mutableStateOf(AppIcon.current(context)) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportSettings) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importSettings) }

    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var showArgs by rememberSaveable { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(true) }

    var stats by remember { mutableStateOf<Triple<Long, Long, Long>?>(null) }
    LaunchedEffect(running) {
        stats = null
        while (running) {
            stats = viewModel.readStats()
            delay(STATS_POLL_MS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = { showAppearance = true }) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                    IconButton(onClick = viewModel::openRepo) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_github),
                            contentDescription = stringResource(R.string.view_on_github),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(
                running = running,
                restarting = restarting,
                stats = stats,
                error = error,
                hasLink = tgLink != null,
                onToggle = { if (active) viewModel.stop() else viewModel.start() },
                onOpenLink = viewModel::openLink,
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel(stringResource(R.string.mode_label))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProxyViewModel.PRESETS.forEach { preset ->
                        FilterChip(
                            selected = ProxyViewModel.matches(args, preset),
                            onClick = { viewModel.applyPreset(preset) },
                            label = { Text(stringResource(preset.label)) },
                            enabled = !active,
                        )
                    }
                }
                NetCheckRow(netCheck, netChecking, viewModel::runNetCheck)
            }

            Column {
                ExpandableHeader(stringResource(R.string.launch_settings), showArgs) {
                    showArgs = !showArgs
                }
                if (showArgs) {
                    OutlinedTextField(
                        value = args,
                        onValueChange = viewModel::updateArgs,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.args_label)) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        minLines = 3,
                        maxLines = 8,
                        enabled = !active,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.verbose_log),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = ProxyViewModel.isVerbose(args),
                            onCheckedChange = viewModel::setVerbose,
                            enabled = !active,
                        )
                    }
                }
            }

            Column(modifier = if (showLog) Modifier.weight(1f) else Modifier) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(modifier = Modifier.weight(1f)) {
                        ExpandableHeader(stringResource(R.string.log_label), showLog) {
                            showLog = !showLog
                        }
                    }
                    if (showLog) {
                        TextButton(onClick = viewModel::copyLog, enabled = viewModel.logs.isNotEmpty()) {
                            Text(stringResource(R.string.copy_log))
                        }
                        TextButton(onClick = viewModel::shareLog, enabled = viewModel.logs.isNotEmpty()) {
                            Text(stringResource(R.string.share_log))
                        }
                    }
                }
                if (showLog) {
                    LogList(viewModel)
                }
            }
        }
    }

    if (showAppearance) {
        SettingsDialog(
            appearance = appearance,
            appIcon = appIcon,
            update = update,
            currentVersion = viewModel.currentVersion,
            onMode = viewModel::setThemeMode,
            onAccent = viewModel::setAccent,
            onAmoled = viewModel::setAmoled,
            onIcon = { icon ->
                AppIcon.apply(context, icon)
                appIcon = icon
            },
            onExport = { exportLauncher.launch(BACKUP_FILE_NAME) },
            onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            onCheckUpdate = viewModel::checkForUpdate,
            onOpenBuilds = viewModel::openBuilds,
            onDismiss = { showAppearance = false },
        )
    }
}

@Composable
private fun NetCheckRow(result: ProxyViewModel.NetCheck?, checking: Boolean, onCheck: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = onCheck, enabled = !checking) {
            Text(stringResource(if (checking) R.string.net_checking else R.string.net_check))
        }
        if (result != null && !checking) {
            Text(
                stringResource(R.string.net_direct, pathText(result.direct)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.net_cloudflare, pathText(result.cloudflare)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(
                    when {
                        result.direct.ok * 3 >= result.direct.tries * 2 -> R.string.net_advice_direct
                        result.cloudflare.ok * 3 >= result.cloudflare.tries * 2 -> R.string.net_advice_cf
                        result.direct.ok > 0 -> R.string.net_advice_flaky
                        else -> R.string.net_advice_none
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun pathText(path: ProxyViewModel.PathResult): String =
    if (path.avgMs != null) {
        stringResource(R.string.net_path_ok, path.ok, path.tries, path.avgMs)
    } else {
        stringResource(R.string.net_path_dead, path.ok, path.tries)
    }

@Composable
private fun StatusCard(
    running: Boolean,
    restarting: Boolean,
    stats: Triple<Long, Long, Long>?,
    error: String?,
    hasLink: Boolean,
    onToggle: () -> Unit,
    onOpenLink: () -> Unit,
) {
    val active = running || restarting
    val colors = if (running) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(active)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(
                        when {
                            running -> R.string.status_running
                            restarting -> R.string.status_restarting
                            else -> R.string.status_stopped
                        },
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            if (running && stats != null) {
                val context = LocalContext.current
                Text(
                    stringResource(
                        R.string.stats_line,
                        stats.first,
                        Formatter.formatShortFileSize(context, stats.third),
                        Formatter.formatShortFileSize(context, stats.second),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(stringResource(if (active) R.string.stop else R.string.start))
            }
            if (hasLink) {
                OutlinedButton(onClick = onOpenLink, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.open_telegram_link))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ExpandableHeader(text: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (expanded) "▾" else "▸",
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LogList(viewModel: ProxyViewModel) {
    val logs = viewModel.logs
    val listState = rememberLazyListState()
    val following by remember {
        // Reflects the last layout pass, i.e. where the viewport was *before*
        // the line that triggers the effect below arrived — which is exactly
        // the question being asked: was the user reading the tail, or had they
        // scrolled back into the history that autoscrolling would yank away?
        derivedStateOf { !listState.canScrollForward }
    }

    // Keyed on the newest line's id rather than on `logs.size`: the buffer is
    // capped, and its collector appends and trims the oldest line back to back
    // with no suspension between them, so from the 501st line on the size is
    // permanently 500 and Compose never even observes the intermediate 501.
    // A key that stops changing there would freeze the view at the cap while
    // new lines kept arriving off-screen, with no way back into following.
    LaunchedEffect(logs.lastOrNull()?.id) {
        if (following && logs.isNotEmpty()) {
            listState.scrollToItem(logs.lastIndex)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        SelectionContainer(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                items(logs, key = { it.id }) { line ->
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    appearance: Appearance,
    appIcon: AppIcon,
    update: ProxyViewModel.UpdateState?,
    currentVersion: String,
    onMode: (ThemeMode) -> Unit,
    onAccent: (Accent) -> Unit,
    onAmoled: (Boolean) -> Unit,
    onIcon: (AppIcon) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenBuilds: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                SectionLabel(stringResource(R.string.theme_label))
                listOf(
                    ThemeMode.SYSTEM to R.string.theme_system,
                    ThemeMode.LIGHT to R.string.theme_light,
                    ThemeMode.DARK to R.string.theme_dark,
                ).forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = appearance.mode == mode,
                                onClick = { onMode(mode) },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = appearance.mode == mode, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(label))
                    }
                }

                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.accent_label))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    Accent.entries
                        .filter { it != Accent.DYNAMIC || dynamicColorAvailable }
                        .forEach { accent ->
                            AccentSwatch(accent, selected = appearance.accent == accent) {
                                onAccent(accent)
                            }
                        }
                }
                if (dynamicColorAvailable) {
                    Text(
                        stringResource(R.string.accent_dynamic_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.amoled), modifier = Modifier.weight(1f))
                    Switch(checked = appearance.amoled, onCheckedChange = onAmoled)
                }

                SettingsDivider()
                SectionLabel(stringResource(R.string.icon_label))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    AppIcon.entries.forEach { icon ->
                        IconSwatch(icon, selected = icon == appIcon) { onIcon(icon) }
                    }
                }
                Text(
                    stringResource(R.string.icon_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SettingsDivider()
                SectionLabel(stringResource(R.string.backup_label))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExport) { Text(stringResource(R.string.backup_export)) }
                    OutlinedButton(onClick = onImport) { Text(stringResource(R.string.backup_import)) }
                }
                Text(
                    stringResource(R.string.backup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SettingsDivider()
                SectionLabel(stringResource(R.string.update_label))
                Text(
                    stringResource(R.string.update_current, currentVersion),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onCheckUpdate,
                        enabled = update != ProxyViewModel.UpdateState.Checking,
                    ) { Text(stringResource(R.string.update_check)) }
                    if (update is ProxyViewModel.UpdateState.Available) {
                        TextButton(onClick = onOpenBuilds) { Text(stringResource(R.string.update_open)) }
                    }
                }
                val status = when (update) {
                    null -> null
                    ProxyViewModel.UpdateState.Checking -> stringResource(R.string.update_checking)
                    ProxyViewModel.UpdateState.UpToDate -> stringResource(R.string.update_latest)
                    ProxyViewModel.UpdateState.Failed -> stringResource(R.string.update_failed)
                    is ProxyViewModel.UpdateState.Available ->
                        stringResource(R.string.update_available, update.version)
                }
                if (status != null) {
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}

@Composable
private fun SettingsDivider() {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun IconSwatch(icon: AppIcon, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier
            .size(44.dp)
            .border(if (selected) 3.dp else 1.dp, ring, MaterialTheme.shapes.medium)
            .padding(4.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        shape = MaterialTheme.shapes.small,
        color = icon.background,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_notification),
                contentDescription = stringResource(icon.label),
                tint = icon.glyph,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun AccentSwatch(accent: Accent, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier
            .size(36.dp)
            .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
            .padding(4.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        shape = CircleShape,
        color = accent.swatch,
    ) {
        if (accent == Accent.DYNAMIC) {
            Box(contentAlignment = Alignment.Center) {
                Text("A", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    val color = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Surface(
        modifier = Modifier.size(12.dp),
        shape = CircleShape,
        color = color,
    ) {}
}

private const val STATS_POLL_MS = 2_000L
private const val BACKUP_FILE_NAME = "tg-ws-proxy-settings.json"
