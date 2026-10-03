package cc.khixang.axonhub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.khixang.axonhub.AxonHubApplication
import cc.khixang.axonhub.R
import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.observability.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.time.LocalDate

/** Parent owns navigation. No gateway mutations or inference requests are issued here. */
@Composable
fun AnalyticsScreen(app: AxonHubApplication, initialMode: Int = 0, embedded: Boolean = false, back: () -> Unit) {
    if (!embedded) BackHandler(onBack = back)
    val instanceId by app.repository.selectedId.collectAsState()
    val projectId by app.repository.projectId.collectAsState()
    val instances by app.repository.instances.collectAsState()
    val snapshot by app.repository.snapshot.collectAsState()
    // Observe the selected object as well as ID: edits/relogin can advance the generation.
    val fence = remember(instanceId, projectId, instances, snapshot) { runCatching { app.repository.currentFence() }.getOrNull() }
    if (fence == null) {
        Column(Modifier.padding(16.dp)) { if (!embedded) TextButton(back) { Text(stringResource(R.string.ax_analytics_back)) }; Text(stringResource(R.string.ax_analytics_no_instance)) }
    } else key(fence) { AnalyticsBoundScreen(app, fence, back, initialMode, embedded) }
}

@Composable
private fun AnalyticsBoundScreen(app: AxonHubApplication, fence: TargetFence, back: () -> Unit, initialMode: Int, embedded: Boolean) {
    val service = remember(app) { app.analytics }
    var mode by remember { mutableStateOf(initialMode) }
    var dimension by remember { mutableStateOf(AnalyticsDimension.CHANNEL) }
    var metric by remember { mutableStateOf(AnalyticsMetric.CHANNEL_SUCCESS) }
    var window by remember { mutableStateOf(AnalyticsWindow.DAY) }
    var limit by remember { mutableStateOf("5") }
    var dates by remember { mutableStateOf(true) }
    var start by remember { mutableStateOf(LocalDate.now().minusDays(7).toString()) }
    var end by remember { mutableStateOf(LocalDate.now().toString()) }
    var filters by remember { mutableStateOf(AnalyticsResource.entries.associateWith { "" }) }
    var filterOpen by remember { mutableStateOf(initialMode == 0) }
    var selectedResource by remember { mutableStateOf<AnalyticsResource?>(null) }
    var bundle by remember { mutableStateOf<AnalyticsBundle?>(null) }
    var dashboard by remember { mutableStateOf<AnalyticsDashboard?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var appliedFilter by remember { mutableStateOf(AnalyticsFilter(startTime = start, endTime = end)) }
    var search by remember { mutableStateOf("") }
    var descending by remember { mutableStateOf(initialMode == 0) }
    var healthSortField by remember { mutableStateOf("successRate") }
    var channelType by remember { mutableStateOf("") }
    var warningOnly by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    var chartField by remember { mutableStateOf("totalTokens") }
    val invalidFilter = stringResource(R.string.ax_analytics_invalid_filter)
    val requestFailed = stringResource(R.string.ax_analytics_failed)

    LaunchedEffect(mode, metric, window, dimension, appliedFilter, refresh) {
        busy = true; error = null; bundle = null; dashboard = null; page = 0; channelType = ""
        try {
            app.repository.verify(fence)
            if (mode == 0) {
                val result = service.analytics(appliedFilter, dimension, fence)
                app.repository.verify(fence); bundle = result
            } else {
                val requestedLimit = if (metric.arguments == AnalyticsArguments.FASTEST) limit.toIntOrNull()?.takeIf { it in 1..100 }
                    ?: throw IllegalArgumentException(invalidFilter) else null
                val result = service.dashboard(metric, window, requestedLimit, fence)
                app.repository.verify(fence); dashboard = result
            }
            filterOpen = false
        } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { if (runCatching { app.repository.verify(fence) }.isSuccess) error = requestFailed } finally { busy = false }
    }
    val rows = if (mode == 0) bundle?.breakdown.orEmpty() else dashboard?.rows.orEmpty()
    val health = mode == 1 && metric == AnalyticsMetric.CHANNEL_SUCCESS
    val sortField = if (mode == 0) "totalTokens" else if (health) healthSortField else metric.chartField
    val visible = visibleDashboardRows(rows, search, if (health) channelType else "", health && warningOnly, sortField, descending)
    LaunchedEffect(search, descending, healthSortField, channelType, warningOnly) { page = 0 }
    val safePage = page.coerceAtMost(((visible.size - 1).coerceAtLeast(0)) / 25)

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                if (!embedded) WorkspaceBack(back)
                IosPageHeader(stringResource(if (mode == 1) R.string.ws_channel_health else R.string.ws_analytics)) {
                    TextButton({ refresh++ }, enabled = !busy) { Text(stringResource(R.string.ax_analytics_refresh)) }
                }
            }
            item {
                if (!embedded) IosSegmentedControl(listOf(stringResource(R.string.ax_analytics_analysis), stringResource(R.string.ax_analytics_statistics)), mode, { mode = it; descending = it == 0 })
                val selectedInstance = instancesName(app, fence.instanceId)
                Text(selectedInstance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ filterOpen = !filterOpen }) { Text(stringResource(R.string.ax_analytics_filters)) }
            }
            if (filterOpen) item {
                IosCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mode == 0) {
                        AnalyticsChoice(stringResource(R.string.ax_analytics_dimension), dimension, AnalyticsDimension.entries, { stringResource(dimensionLabel(it)) }) { dimension = it }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.ax_analytics_dates)); Switch(dates, { dates = it }) }
                        if (dates) {
                            OutlinedTextField(start, { start = it }, label = { Text(stringResource(R.string.ax_analytics_start)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(end, { end = it }, label = { Text(stringResource(R.string.ax_analytics_end)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        Text(stringResource(R.string.ax_analytics_date_help), style = MaterialTheme.typography.bodySmall)
                        AnalyticsResource.entries.forEach { resource ->
                            OutlinedTextField(filters[resource].orEmpty(), { value -> filters = filters + (resource to value) }, label = { Text(stringResource(resourceLabel(resource))) }, modifier = Modifier.fillMaxWidth())
                            TextButton({ selectedResource = resource }) { Text(stringResource(R.string.ax_analytics_select_resource)) }
                        }
                        Text(stringResource(R.string.ax_analytics_model_help), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            try {
                                val next = AnalyticsFilter(if (dates) start else null, if (dates) end else null,
                                    AnalyticsFilter.identifiers(filters[AnalyticsResource.PROJECT].orEmpty()), AnalyticsFilter.identifiers(filters[AnalyticsResource.CHANNEL].orEmpty()),
                                    AnalyticsFilter.identifiers(filters[AnalyticsResource.MODEL].orEmpty()), AnalyticsFilter.identifiers(filters[AnalyticsResource.API_KEY].orEmpty()), AnalyticsFilter.identifiers(filters[AnalyticsResource.USER].orEmpty()))
                                next.json(); appliedFilter = next; refresh++
                            } catch (_: Exception) { error = invalidFilter }
                        }, enabled = !busy) { Text(stringResource(R.string.ax_analytics_apply)) }
                    } else {
                        AnalyticsChoice(stringResource(R.string.ax_analytics_metric), metric, AnalyticsMetric.entries, { stringResource(metricLabel(it)) }) { metric = it }
                        if (metric.supportsWindow) AnalyticsChoice(stringResource(R.string.ax_analytics_window), window, AnalyticsWindow.entries, { stringResource(windowLabel(it)) }) { window = it }
                        else Text(stringResource(R.string.ax_analytics_fixed_range))
                        Text(stringResource(R.string.ax_analytics_stats_scope), style = MaterialTheme.typography.bodySmall)
                        if (metric.arguments == AnalyticsArguments.FASTEST) OutlinedTextField(limit, { limit = it }, label = { Text(stringResource(R.string.ax_analytics_limit)) }, singleLine = true)
                        Button({ refresh++ }, enabled = !busy) { Text(stringResource(R.string.ax_analytics_apply)) }
                    }
                }
            }
            }
            if (mode == 1) item {
                AnalyticsChoice(stringResource(R.string.ax_analytics_metric), metric, AnalyticsMetric.entries, { stringResource(metricLabel(it)) }) { metric = it }
                if (metric.supportsWindow) IosSegmentedControl(AnalyticsWindow.entries.map { stringResource(windowLabel(it)) }, window.ordinal, { window = AnalyticsWindow.entries[it] })
                else Text(stringResource(R.string.ax_analytics_fixed_range), style = MaterialTheme.typography.bodySmall)
            }
            if (busy) item { WorkspaceLoading() }
            error?.let { message -> item { ErrorState(message) { refresh++ } } }
            bundle?.let { data ->
                item {
                    Text(stringResource(R.string.ax_analytics_earliest, observabilityScalar(data.metadata["earliestDate"], "earliestDate")))
                    AnalyticsFields(stringResource(R.string.ax_analytics_overview), data.overview)
                }
                item {
                    Text(stringResource(R.string.ax_analytics_daily), style = MaterialTheme.typography.titleMedium)
                    AnalyticsChoice(stringResource(R.string.ax_analytics_chart_metric), chartField, listOf("totalTokens", "inputTokens", "cachedInputTokens", "uncachedInputTokens", "outputTokens", "requestCount", "cost"), { analyticsFieldLabel(it) }) { chartField = it }
                    AnalyticsChart(data.daily, chartField, true)
                    AnalyticsFields(stringResource(R.string.ax_analytics_daily_table), JsonArray(data.daily))
                }
            }
            dashboard?.let { data ->
                item { AnalyticsFields(stringResource(R.string.ax_analytics_global_overview), data.overview); AnalyticsFields(stringResource(R.string.ax_analytics_token_overview), data.tokens) }
            }
            if (bundle != null || dashboard != null) {
                item {
                    IosSearchField(search, { search = it }, stringResource(R.string.ax_analytics_search))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.ax_analytics_descending)); Switch(descending, { descending = it }) }
                    if (mode == 1 && metric == AnalyticsMetric.CHANNEL_SUCCESS) {
                        AnalyticsChoice(stringResource(R.string.ax_analytics_channel_type), channelType, listOf("") + rows.map { it["channelType"].text }.filter(String::isNotBlank).distinct().sorted(), { it.ifBlank { stringResource(R.string.ax_analytics_all) } }) { channelType = it }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.ax_analytics_warnings)); Switch(warningOnly, { warningOnly = it }) }
                        AnalyticsChoice(stringResource(R.string.ax_analytics_chart_metric), healthSortField, listOf("successRate", "failedCount", "successCount", "totalCount", "inputTokens", "outputTokens", "totalTokens"), { analyticsFieldLabel(it) }) { healthSortField = it }
                    }
                    Text(if (mode == 1) stringResource(metricLabel(metric)) else stringResource(R.string.ax_analytics_dimension), style = MaterialTheme.typography.titleMedium)
                    if (health && rows.isNotEmpty()) ChannelHealthSummary(rows)
                    if (!health) AnalyticsChart(visible.take(if (mode == 0) 10 else 15), sortField, false)
                    Text(stringResource(R.string.ax_analytics_page_count, visible.size, safePage + 1))
                    if (visible.isEmpty()) Text(stringResource(R.string.ax_analytics_empty))
                }
                itemsIndexed(visible.drop(safePage * 25).take(25)) { _, row ->
                    if (health) ChannelHealthCard(row) else AnalyticsFields(analyticsRowName(row), row)
                }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton({ page = safePage - 1 }, enabled = safePage > 0) { Text(stringResource(R.string.ax_analytics_previous)) }
                    OutlinedButton({ page = safePage + 1 }, enabled = (safePage + 1) * 25 < visible.size) { Text(stringResource(R.string.ax_analytics_next)) }
                } }
            }
        }
    }
    selectedResource?.let { resource ->
        AnalyticsResourceDialog(service, resource, fence, AnalyticsFilter.identifiers(filters[resource].orEmpty()), { selectedResource = null }) { ids ->
            filters = filters + (resource to ids.joinToString(", ")); selectedResource = null
        }
    }
}

@Composable
private fun ChannelHealthCard(row: JsonObject) {
    val rate = channelHealthRate(row["successCount"].longOrNull, row["failedCount"].longOrNull)
    IosCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(analyticsRowName(row), style = MaterialTheme.typography.titleMedium)
                    Text(row["channelType"].text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(DisplayFormat.percentage(rate), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
            }
            if (rate != null) LinearProgressIndicator(progress = { (rate / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.ws_health_counts, observabilityScalar(row["successCount"], "successCount"), observabilityScalar(row["failedCount"], "failedCount")), style = MaterialTheme.typography.bodySmall)
            if (row["channelDisabled"].boolOrNull == true) Text(stringResource(R.string.ws_disabled), color = MaterialTheme.colorScheme.error)
            AnalyticsFields(stringResource(R.string.ax_analytics_token_overview), row)
        }
    }
}

@Composable
private fun ChannelHealthSummary(rows: List<JsonObject>) {
    // Unknown counts never become synthetic zeroes, even in the summary.
    val known = rows.all { it["successCount"].longOrNull != null && it["failedCount"].longOrNull != null }
    val success = if (known) rows.sumOf { it["successCount"].longOrNull!!.coerceAtLeast(0).toDouble() } else null
    val failed = if (known) rows.sumOf { it["failedCount"].longOrNull!!.coerceAtLeast(0).toDouble() } else null
    val rate = if (success != null && failed != null && success + failed > 0) success / (success + failed) * 100 else null
    Text(stringResource(R.string.ax_analytics_metric_channel_success) + ": " + DisplayFormat.percentage(rate), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.ws_health_counts, success?.let { DisplayFormat.number(it) } ?: "—", failed?.let { DisplayFormat.number(it) } ?: "—"), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun instancesName(app: AxonHubApplication, id: String): String {
    val instances by app.repository.instances.collectAsState()
    return instances.firstOrNull { it.id == id }?.name ?: "—"
}

@Composable
private fun <T> AnalyticsChoice(label: String, selected: T, choices: List<T>, title: @Composable (T) -> String, change: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Text("$label：${title(selected)}") }
        DropdownMenu(expanded, { expanded = false }) { choices.forEach { value -> DropdownMenuItem(text = { Text(title(value)) }, onClick = { change(value); expanded = false }) } }
    }
}

@Composable
private fun AnalyticsFields(title: String, value: JsonElement) {
    var expanded by remember(title, value) { mutableStateOf(false) }
    IosCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton({ expanded = !expanded }) { Text(title) }
            if (expanded) AnalyticsValue(value)
        }
    }
}

@Composable
internal fun AnalyticsValue(value: JsonElement) {
    when (value) {
        is JsonObject -> value.keys.filterNot(::observabilityIdentifier).sorted().forEach { key ->
            val item = value[key] ?: JsonNull
            if (item is JsonObject || item is JsonArray) AnalyticsFields(analyticsFieldLabel(key), item) else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(analyticsFieldLabel(key), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(observabilityScalar(item, key), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
            }
        }
        is JsonArray -> if (value.isEmpty()) Text(stringResource(R.string.ax_analytics_empty)) else value.forEachIndexed { index, item -> Text("${index + 1}", style = MaterialTheme.typography.labelMedium); AnalyticsValue(item); HorizontalDivider() }
        is JsonNull -> Text("—")
        else -> Text(value.text)
    }
}

/** Null/non-finite metrics are skipped, not plotted at zero; daily gaps break the line. Exact values remain in the accessible table. */
@Composable
private fun AnalyticsChart(rows: List<JsonObject>, field: String, line: Boolean) {
    val samples = rows.map { it[field].doubleOrNull?.takeIf(Double::isFinite) }
    val values = samples.filterNotNull()
    if (values.isEmpty()) { Text(stringResource(R.string.ax_analytics_no_numeric)); return }
    val description = stringResource(R.string.ax_analytics_chart_description, analyticsFieldLabel(field)) + ". " + rows.joinToString("; ") { "${analyticsRowName(it)}: ${observabilityScalar(it[field], field)}" }
    val color = MaterialTheme.colorScheme.primary
    val low = minOf(0.0, values.min()); val high = maxOf(0.0, values.max())
    val range = (high - low).takeIf { it > 0.0 } ?: 1.0
    Text(stringResource(R.string.ax_analytics_chart_range, observabilityScalar(JsonPrimitive(values.min()), field), observabilityScalar(JsonPrimitive(values.max()), field)), style = MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }) {
        val yZero = size.height * (1.0 - (0.0 - low) / range).toFloat()
        drawLine(color.copy(alpha = 0.25f), Offset(0f, yZero), Offset(size.width, yZero))
        if (line) {
            var last: Offset? = null
            samples.forEachIndexed { index, value ->
                if (value == null) last = null else {
                    val point = Offset(size.width * index / (samples.size - 1).coerceAtLeast(1), size.height * (1.0 - (value - low) / range).toFloat())
                    last?.let { drawLine(color, it, point, strokeWidth = 3.dp.toPx()) }
                    drawCircle(color, 3.dp.toPx(), point); last = point
                }
            }
        } else {
            val width = size.width / samples.size.coerceAtLeast(1)
            samples.forEachIndexed { index, value -> if (value != null) {
                val y = size.height * (1.0 - (value - low) / range).toFloat()
                drawLine(color, Offset(width * (index + 0.5f), yZero), Offset(width * (index + 0.5f), y), strokeWidth = width * 0.65f)
            } }
        }
    }
    rows.take(15).forEach { row -> Text("${analyticsRowName(row)} · ${analyticsFieldLabel(field)}：${observabilityScalar(row[field], field)}", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun AnalyticsResourceDialog(service: AnalyticsService, kind: AnalyticsResource, fence: TargetFence, initial: List<String>, dismiss: () -> Unit, apply: (List<String>) -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(initial.toSet()) }
    var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var total by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    suspend fun load(next: Boolean) {
        if (busy) return
        busy = true; error = false
        try {
            val result = service.resources(kind, after = if (next) cursor else null, fence = fence)
            if (next && result.endCursor != null && result.endCursor == cursor) throw IllegalStateException("Repeated pagination cursor")
            rows = (if (next) rows + result.items else result.items).distinctBy { it["id"].text }
            cursor = result.endCursor; total = result.total; loaded = true
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { error = true } finally { busy = false }
    }
    LaunchedEffect(kind, fence) { load(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(resourceLabel(kind))) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ax_analytics_selected_count, selected.size))
            total?.let { Text(stringResource(R.string.ax_analytics_resource_total, it)) }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            if (error) { Text(stringResource(R.string.ax_analytics_failed), color = MaterialTheme.colorScheme.error); TextButton({ scope.launch { load(loaded && cursor != null) } }) { Text(stringResource(R.string.ax_analytics_retry)) } }
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                itemsIndexed(rows) { _, row ->
                    val id = kind.identifier(row)
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(id in selected, { checked -> selected = if (checked) selected + id else selected - id }, enabled = id.isNotBlank())
                        Column(Modifier.weight(1f).padding(top = 8.dp)) { Text(kind.label(row)); Text(id, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (loaded && rows.isEmpty()) Text(stringResource(R.string.ax_analytics_empty))
            if (cursor != null) TextButton({ scope.launch { load(true) } }, enabled = !busy) { Text(stringResource(R.string.ax_analytics_load_next)) }
            TextButton({ selected = emptySet() }) { Text(stringResource(R.string.ax_analytics_clear)) }
        }
    }, confirmButton = { TextButton({ apply(selected.toList()) }) { Text(stringResource(R.string.ax_analytics_apply)) } }, dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.ax_analytics_cancel)) } })
}

internal fun analyticsRowName(row: JsonObject): String {
    return observabilityRowName(row)
}

private fun dimensionLabel(value: AnalyticsDimension): Int = when (value) {
    AnalyticsDimension.CHANNEL -> R.string.ax_analytics_channel; AnalyticsDimension.MODEL -> R.string.ax_analytics_model
    AnalyticsDimension.API_KEY -> R.string.ax_analytics_key; AnalyticsDimension.USER -> R.string.ax_analytics_user
}
private fun resourceLabel(value: AnalyticsResource): Int = when (value) {
    AnalyticsResource.PROJECT -> R.string.ax_analytics_project; AnalyticsResource.CHANNEL -> R.string.ax_analytics_channel
    AnalyticsResource.MODEL -> R.string.ax_analytics_model; AnalyticsResource.API_KEY -> R.string.ax_analytics_key; AnalyticsResource.USER -> R.string.ax_analytics_user
}
private fun windowLabel(value: AnalyticsWindow): Int = when (value) { AnalyticsWindow.DAY -> R.string.ax_analytics_day; AnalyticsWindow.WEEK -> R.string.ax_analytics_week; AnalyticsWindow.MONTH -> R.string.ax_analytics_month }
private fun metricLabel(value: AnalyticsMetric): Int = when (value) {
    AnalyticsMetric.CHANNEL_SUCCESS -> R.string.ax_analytics_metric_channel_success
    AnalyticsMetric.REQUEST_CHANNEL -> R.string.ax_analytics_metric_request_channel
    AnalyticsMetric.REQUEST_MODEL -> R.string.ax_analytics_metric_request_model
    AnalyticsMetric.REQUEST_KEY -> R.string.ax_analytics_metric_request_key
    AnalyticsMetric.TOKEN_CHANNEL -> R.string.ax_analytics_metric_token_channel
    AnalyticsMetric.TOKEN_MODEL -> R.string.ax_analytics_metric_token_model
    AnalyticsMetric.TOKEN_KEY -> R.string.ax_analytics_metric_token_key
    AnalyticsMetric.COST_CHANNEL -> R.string.ax_analytics_metric_cost_channel
    AnalyticsMetric.COST_MODEL -> R.string.ax_analytics_metric_cost_model
    AnalyticsMetric.COST_KEY -> R.string.ax_analytics_metric_cost_key
    AnalyticsMetric.USER_USAGE -> R.string.ax_analytics_metric_user_usage
    AnalyticsMetric.DAILY_REQUESTS -> R.string.ax_analytics_metric_daily_requests
    AnalyticsMetric.TOP_PROJECTS -> R.string.ax_analytics_metric_top_projects
    AnalyticsMetric.MODEL_PERFORMANCE -> R.string.ax_analytics_metric_model_performance
    AnalyticsMetric.CHANNEL_PERFORMANCE -> R.string.ax_analytics_metric_channel_performance
    AnalyticsMetric.FASTEST_CHANNELS -> R.string.ax_analytics_metric_fastest_channels
    AnalyticsMetric.FASTEST_MODELS -> R.string.ax_analytics_metric_fastest_models
}

@Composable
private fun analyticsFieldLabel(field: String): String {
    val resource = when (field) {
        "totalTokens", "tokens" -> R.string.ax_analytics_field_tokens
        "inputTokens", "totalInputTokens" -> R.string.ax_analytics_field_input
        "cachedInputTokens", "totalCachedInputTokens", "cachedTokens" -> R.string.ax_analytics_field_cached
        "uncachedInputTokens", "totalUncachedInputTokens" -> R.string.ax_analytics_field_uncached
        "outputTokens", "totalOutputTokens" -> R.string.ax_analytics_field_output
        "count", "requestCount", "totalRequests" -> R.string.ax_analytics_field_requests
        "cost", "totalCost" -> R.string.ws_cost
        "throughput" -> R.string.ax_analytics_field_throughput
        "ttftMs" -> R.string.ax_analytics_field_ttft
        "latencyMs", "averageResponseTime" -> R.string.ax_analytics_field_latency
        "successRate" -> R.string.ws_success
        "failedCount", "failedRequests" -> R.string.ax_analytics_field_failed
        "successCount" -> R.string.ax_analytics_field_success
        "date", "earliestDate" -> R.string.ax_analytics_field_date
        else -> null
    }
    return resource?.let { stringResource(it) } ?: field
}
