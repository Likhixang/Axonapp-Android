package cc.khixang.axonhub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.khixang.axonhub.AxonHubApplication
import cc.khixang.axonhub.R
import cc.khixang.axonhub.core.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable fun DashboardScreen(app: AxonHubApplication) {
    val state by app.repository.snapshot.collectAsState()
    val instance by app.repository.selected.collectAsState()
    val scope = rememberCoroutineScope()
    var analytics by remember { mutableStateOf(false) }
    if (analytics) {
        AnalyticsScreen(app) { analytics = false }
        return
    }
    val busy = state is LoadState.Loading
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            IosPageHeader(stringResource(R.string.ws_dashboard), instance?.name ?: "AxonHub") {
                IconButton(onClick = { scope.launch { app.repository.refresh() } }, enabled = !busy && instance != null) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.ws_refresh))
                }
            }
            (state as? LoadState.Ready<*>)?.let {
                Text(stringResource(R.string.ws_updated, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.updatedAt))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (instance == null) item { WorkspaceEmpty(stringResource(R.string.ws_connect_title), stringResource(R.string.ws_connect_help), Icons.Default.CloudOff) }
        else when (val current = state) {
            LoadState.Idle, LoadState.Loading -> item { WorkspaceLoading() }
            is LoadState.Failed -> item { ErrorState(current.message) { scope.launch { app.repository.refresh() } } }
            is LoadState.Ready -> {
                val d = current.value.dashboard
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricCard(stringResource(R.string.ws_requests), format(d.totalRequests), stringResource(R.string.ws_all_time), Icons.Default.SwapVert, Modifier.weight(1f))
                        MetricCard(stringResource(R.string.ws_success), if (d.totalRequests != null && d.totalRequests > 0 && d.failedRequests != null) "%.1f%%".format(100.0 * (d.totalRequests - d.failedRequests) / d.totalRequests) else "—", stringResource(R.string.ws_all_time), Icons.Default.VerifiedUser, Modifier.weight(1f))
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricCard(stringResource(R.string.ws_tokens), if (d.inputTokensToday != null && d.outputTokensToday != null) format(d.inputTokensToday + d.outputTokensToday) else "—", stringResource(R.string.ws_today), Icons.Default.AutoAwesome, Modifier.weight(1f))
                        MetricCard(stringResource(R.string.ws_cost), d.totalCost?.let { "$%.4f".format(it) } ?: "—", stringResource(R.string.ws_all_time), Icons.Default.Paid, Modifier.weight(1f))
                    }
                }
                item {
                    IosSectionTitle(stringResource(R.string.ws_request_activity))
                    IosCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PeriodValue(stringResource(R.string.ws_today), d.requestsToday, Modifier.weight(1f))
                            PeriodValue(stringResource(R.string.ws_week), d.requestsThisWeek, Modifier.weight(1f))
                            PeriodValue(stringResource(R.string.ws_month), d.requestsThisMonth, Modifier.weight(1f))
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.ws_latency), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(d.averageResponseTime?.let { "%.0f ms".format(it) } ?: "—", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                item { DailyActivityCard(current.value.daily) }
                item { IosSectionTitle(stringResource(R.string.ws_channel_health), stringResource(R.string.ws_channel_health_help)) }
                if (current.value.channelPerformance.isEmpty()) item { WorkspaceEmpty(stringResource(R.string.ws_no_channel_data), stringResource(R.string.ws_missing_help), Icons.Default.MonitorHeart) }
                else items(current.value.channelPerformance, key = { it.channelId.ifBlank { it.name } }) { channel ->
                    IosCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(channel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (channel.type.isNotBlank()) Text(channel.type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(channel.successRate?.let { "%.2f%%".format(it) } ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                            channel.successRate?.takeIf(Double::isFinite)?.let { rate ->
                                LinearProgressIndicator(progress = { (rate / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            }
                            Text(stringResource(R.string.ws_health_counts, format(channel.success), format(channel.failed)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (channel.disabled == true) Text(stringResource(R.string.ws_disabled), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                item {
                    IosCard(Modifier.fillMaxWidth(), onClick = { analytics = true }) {
                        Row(Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.QueryStats, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(stringResource(R.string.ws_analytics), fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.ws_analytics_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun MetricCard(label: String, value: String, period: String, icon: ImageVector, modifier: Modifier = Modifier) {
    IosCard(modifier) {
        Column(Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), maxLines = 1)
            Text(period, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun PeriodValue(label: String, value: Long?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(format(value), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
    }
}

@Composable private fun DailyActivityCard(items: List<DailyStat>) {
    var metric by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    val sorted = remember(items) { items.sortedBy { it.date } }
    val labels = listOf(stringResource(R.string.ws_requests), stringResource(R.string.ws_tokens), stringResource(R.string.ws_cost))
    IosSectionTitle(stringResource(R.string.ws_trend), stringResource(R.string.ws_trend_help))
    IosCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            IosSegmentedControl(labels, metric, { metric = it })
            val samples = sorted.map { when (metric) { 0 -> it.requests?.toDouble(); 1 -> it.tokens?.toDouble(); else -> it.cost }?.takeIf(Double::isFinite) }
            if (samples.all { it == null }) {
                Text(stringResource(R.string.ws_no_metric_data), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                DailyChart(samples, sorted.map { it.date }, labels[metric], Modifier.fillMaxWidth().height(180.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(sorted.firstOrNull()?.date.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(sorted.lastOrNull()?.date.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ expanded = !expanded }) { Text(stringResource(if (expanded) R.string.ws_hide_values else R.string.ws_show_values)) }
                if (expanded) sorted.forEachIndexed { index, day ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(day.date, style = MaterialTheme.typography.bodySmall)
                        Text(samples[index]?.let { if (metric == 2) "$%.4f".format(it) else NumberFormat.getIntegerInstance().format(it) } ?: "—", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun format(value: Long?): String = value?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—"

/** Keep missing days as gaps. Null metrics are never plotted as zero. */
@Composable private fun DailyChart(samples: List<Double?>, dates: List<String>, label: String, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val values = samples.filterNotNull()
    val min = minOf(0.0, values.minOrNull() ?: 0.0)
    val max = maxOf(0.0, values.maxOrNull() ?: 0.0)
    val range = (max - min).takeIf { it > 0 } ?: 1.0
    val summary = dates.indices.joinToString("; ") { "${dates[it]}: ${samples[it] ?: "—"}" }
    Text(stringResource(R.string.ws_chart_range, NumberFormat.getNumberInstance().format(min), NumberFormat.getNumberInstance().format(max)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(modifier.semantics { contentDescription = "$label. $summary" }) {
        repeat(4) { index -> val y = size.height * index / 3f; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
        var last: Offset? = null
        samples.forEachIndexed { index, sample ->
            if (sample == null) last = null else {
                val x = if (samples.size == 1) size.width / 2 else size.width * index / (samples.size - 1)
                val point = Offset(x, size.height * (1 - (sample - min) / range).toFloat())
                last?.let { previous ->
                    val area = Path().apply { moveTo(previous.x, size.height); lineTo(previous.x, previous.y); lineTo(point.x, point.y); lineTo(point.x, size.height); close() }
                    drawPath(area, color.copy(alpha = 0.10f))
                    drawLine(color, previous, point, strokeWidth = 2.dp.toPx())
                }
                drawCircle(color, 3.dp.toPx(), point)
                last = point
            }
        }
    }
}

@Composable fun ModelsScreen(app: AxonHubApplication) {
    val state by app.repository.snapshot.collectAsState()
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IosPageHeader(stringResource(R.string.ws_models)) {
            IconButton(onClick = { scope.launch { app.repository.refresh() } }, enabled = state !is LoadState.Loading) { Icon(Icons.Default.Refresh, stringResource(R.string.ws_refresh)) }
        }
        IosSearchField(query, { query = it }, stringResource(R.string.ws_search_models))
        when (val current = state) {
            LoadState.Loading, LoadState.Idle -> WorkspaceLoading()
            is LoadState.Failed -> ErrorState(current.message) { scope.launch { app.repository.refresh() } }
            is LoadState.Ready -> {
                val models = current.value.models.filter { query.isBlank() || it.modelId.contains(query, true) || it.name.contains(query, true) }
                if (models.isEmpty()) WorkspaceEmpty(stringResource(R.string.ws_no_results), stringResource(R.string.ws_search_help), Icons.Default.SearchOff)
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(models, key = { it.id }) { model ->
                        IosCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(model.name.ifBlank { model.modelId }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(model.modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (model.developer.isNotBlank()) Text(model.developer, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun WorkspaceLoading() {
    IosCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.ws_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun WorkspaceEmpty(title: String, detail: String, icon: ImageVector = Icons.Default.Inbox) {
    IosCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun WorkspaceBack(back: () -> Unit) {
    TextButton(back, contentPadding = PaddingValues(end = 12.dp)) {
        Icon(Icons.Default.ChevronLeft, null)
        Text(stringResource(R.string.ws_back))
    }
}

@Composable fun ErrorState(message: String, retry: () -> Unit) {
    IosCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.ws_load_failed), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = retry, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) { Text(stringResource(R.string.retry)) }
        }
    }
}
