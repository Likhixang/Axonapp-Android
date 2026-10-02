package cc.khixang.axonhub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.khixang.axonhub.AxonHubApplication
import cc.khixang.axonhub.R
import cc.khixang.axonhub.core.*
import kotlinx.coroutines.launch
import java.text.NumberFormat

@Composable fun DashboardScreen(app: AxonHubApplication) {
    val state by app.repository.snapshot.collectAsState(); val scope = rememberCoroutineScope()
    Scaffold(floatingActionButton = { FloatingActionButton({ scope.launch { app.repository.refresh() } }) { Icon(Icons.Default.Refresh, "Refresh") } }) { padding ->
        when (val current = state) {
            LoadState.Idle, LoadState.Loading -> Box(Modifier.padding(padding).fillMaxSize()) { CircularProgressIndicator(Modifier.padding(32.dp)) }
            is LoadState.Failed -> ErrorState(current.message) { scope.launch { app.repository.refresh() } }
            is LoadState.Ready -> DashboardContent(current.value, Modifier.padding(padding))
        }
    }
}

@Composable private fun DashboardContent(snapshot: Snapshot, modifier: Modifier = Modifier) {
    val d = snapshot.dashboard
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Dashboard", style = MaterialTheme.typography.headlineMedium) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCard("Requests", format(d.totalRequests), Modifier.weight(1f)); MetricCard("Success", if (d.totalRequests != null && d.totalRequests > 0 && d.failedRequests != null) "%.1f%%".format(100.0 * (d.totalRequests - d.failedRequests) / d.totalRequests) else "—", Modifier.weight(1f)) } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCard("Tokens today", if (d.inputTokensToday != null && d.outputTokensToday != null) format(d.inputTokensToday + d.outputTokensToday) else "—", Modifier.weight(1f)); MetricCard("Cost", d.totalCost?.let { "$%.4f".format(it) } ?: "—", Modifier.weight(1f)) } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCard("Average latency", d.averageResponseTime?.let { "%.0f ms".format(it) } ?: "—", Modifier.weight(1f)); MetricCard("This month", format(d.requestsThisMonth), Modifier.weight(1f)) } }
        item { Text("Daily activity", style = MaterialTheme.typography.titleLarge); ElevatedCard(Modifier.fillMaxWidth()) { val daily = snapshot.daily.filter { it.requests != null }; if (daily.isEmpty()) Text("No daily request values available", Modifier.padding(20.dp)) else DailyChart(daily, Modifier.padding(16.dp).fillMaxWidth().height(190.dp)) } }
        item { Text("Channel performance", style = MaterialTheme.typography.titleLarge) }
        if (snapshot.channelPerformance.isEmpty()) item { EmptyCard("No channel performance data") }
        else items(snapshot.channelPerformance, key = { it.channelId.ifBlank { it.name } }) { item -> ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(item.name, style = MaterialTheme.typography.titleMedium); Text(item.successRate?.let { "%.2f%%".format(it) } ?: "—") }; item.successRate?.let { rate -> Spacer(Modifier.height(8.dp)); LinearProgressIndicator({ (rate / 100.0).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth()) }; Text("${format(item.success)} succeeded · ${format(item.failed)} failed", style = MaterialTheme.typography.bodySmall) } } }
    }
}

@Composable private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) { ElevatedCard(modifier) { Column(Modifier.padding(16.dp)) { Text(label, style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(8.dp)); Text(value, style = MaterialTheme.typography.headlineSmall) } } }
@Composable private fun EmptyCard(text: String) { OutlinedCard(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
private fun format(value: Long?): String = value?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—"

@Composable private fun DailyChart(items: List<DailyStat>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary; val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val values = items.mapNotNull { it.requests?.toFloat() }; val max = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        repeat(4) { i -> val y = size.height * i / 3f; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
        if (values.size == 1) drawCircle(color, 5f, Offset(size.width / 2, size.height - size.height * values[0] / max))
        else { val path = Path(); values.forEachIndexed { i, value -> val x = size.width * i / (values.size - 1); val y = size.height - (size.height * value / max); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }; drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(5f)) }
    }
}

@Composable fun ModelsScreen(app: AxonHubApplication) {
    val state by app.repository.snapshot.collectAsState(); var query by remember { mutableStateOf("") }; val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("Models", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f)); IconButton({ scope.launch { app.repository.refresh() } }) { Icon(Icons.Default.Refresh, "Refresh") } }
        OutlinedTextField(query, { query = it }, label = { Text("Search models") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        when (val current = state) {
            LoadState.Loading, LoadState.Idle -> CircularProgressIndicator()
            is LoadState.Failed -> ErrorState(current.message) { scope.launch { app.repository.refresh() } }
            is LoadState.Ready -> { val models = current.value.models.filter { query.isBlank() || it.modelId.contains(query, true) || it.name.contains(query, true) }; if (models.isEmpty()) Text("No models found", Modifier.padding(20.dp)) else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(models, key = { it.id }) { m -> ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(m.name.ifBlank { m.modelId }, style = MaterialTheme.typography.titleMedium); Text(m.modelId); if (m.developer.isNotBlank()) Text(m.developer, style = MaterialTheme.typography.bodySmall) } } } } }
        }
    }
}

@Composable fun ErrorState(message: String, retry: () -> Unit) { Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(message, color = MaterialTheme.colorScheme.error); Button(onClick = retry) { Text(stringResource(R.string.retry)) } } }
