package com.amurcanov.tgwsproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.amurcanov.tgwsproxy.DpiSession
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.LogEntry
import com.amurcanov.tgwsproxy.LogManager
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.SettingsStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsTab(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDpiLogs by rememberSaveable { mutableStateOf(false) }
    val tgLogs by LogManager.logs.collectAsStateWithLifecycle()
    val dpiLogs by DpiSession.logs.collectAsStateWithLifecycle()
    val currentLogs = if (showDpiLogs) dpiLogs else tgLogs

    val savedInfo by settingsStore.logShowInfo.collectAsStateWithLifecycle(initialValue = SettingsStore.DEFAULT_LOG_SHOW_INFO)
    val savedError by settingsStore.logShowError.collectAsStateWithLifecycle(initialValue = SettingsStore.DEFAULT_LOG_SHOW_ERROR)
    val savedNull by settingsStore.logShowNull.collectAsStateWithLifecycle(initialValue = false)

    val nullLogsDisabled = stringResource(R.string.null_logs_disabled)
    val filteredLogs = remember(currentLogs, savedInfo, savedError, savedNull, nullLogsDisabled) {
        if (savedNull) {
            listOf(LogEntry(
                key = "null_msg",
                message = nullLogsDisabled,
                count = 1,
                isError = false,
                priority = 4,
                isEssential = true
            ))
        } else {
            currentLogs.filter { entry ->
                entry.isEssential ||
                (savedInfo && entry.priority == 4) ||
                (savedError && entry.priority >= 5)
            }
        }
    }

    val listState = rememberLazyListState()
    var hasInitialScrolled by remember { mutableStateOf(false) }

    // Auto-scroll logic
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            if (!hasInitialScrolled) {
                // Absolute instant jump on first appearance
                listState.scrollToItem(filteredLogs.size - 1)
                hasInitialScrolled = true
            } else {
                // Smooth scroll only for new incoming logs
                listState.animateScrollToItem(filteredLogs.size - 1)
            }
        }
    }

    val copiedText = stringResource(R.string.copied)
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
        BrandHeader(section = stringResource(R.string.nav_logs))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScreenTitle(stringResource(R.string.nav_logs))
            Row {
                MonoAction(text = stringResource(R.string.clear), onClick = {
                    if (showDpiLogs) DpiSession.clearLogs() else LogManager.clearLogs()
                })
                MonoAction(
                    text = stringResource(R.string.copy),
                    onClick = {
                        val text = filteredLogs.joinToString("\n") { "${it.message} (x${it.count})" }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("TgWsProxy Logs", text))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        BrandSegmented(
            options = listOf(false to stringResource(R.string.dpi_logs_source_tg), true to stringResource(R.string.dpi_logs_source_dpi)),
            selected = showDpiLogs,
            onSelect = { showDpiLogs = it },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SegmentCell("INFO", savedInfo && !savedNull, modifier = Modifier.weight(1f)) {
                scope.launch { settingsStore.saveLogFilters(false, !savedInfo, savedError, false) }
            }
            SegmentCell("ERROR", savedError && !savedNull, modifier = Modifier.weight(1f)) {
                scope.launch { settingsStore.saveLogFilters(false, savedInfo, !savedError, false) }
            }
            SegmentCell("NULL", savedNull, modifier = Modifier.weight(1f)) {
                scope.launch { settingsStore.saveLogFilters(false, false, false, !savedNull) }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brand.surface)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(
                    items = filteredLogs,
                    key = { it.key }
                ) { entry ->
                    LogLine(entry)
                }
            }
        }
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    // Colour never works alone: every level has its own glyph, as the brand's voice rules require.
    val (glyph, color) = when (entry.priority) {
        6, 5 -> "[!]" to Brand.alert
        3 -> "// " to Brand.muted
        else -> ">  " to Brand.ink
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = glyph,
            color = if (entry.priority >= 5) Brand.alert else Brand.accent,
            style = MonoValueStyle.copy(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = entry.message,
            color = color,
            style = MonoValueStyle.copy(
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontWeight = if (entry.isError) FontWeight.Bold else FontWeight.Normal
            ),
            modifier = Modifier.weight(1f)
        )
        if (entry.count > 1) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "x${entry.count}",
                color = Brand.muted,
                style = MonoValueStyle.copy(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
            )
        }
    }
}
