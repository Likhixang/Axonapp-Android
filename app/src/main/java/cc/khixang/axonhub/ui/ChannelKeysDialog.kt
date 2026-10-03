package cc.khixang.axonhub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cc.khixang.axonhub.AxonHubApplication
import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.gateway.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable internal fun gt(zh: String, en: String): String = if (LocalConfiguration.current.locales[0].language == "zh") zh else en

/** Shared native iOS-style operation surface; a failed write blocks retries until reread. */
@Composable internal fun ChannelToolSurface(app: AxonHubApplication, title: String, dismiss: () -> Unit,
    content: @Composable ColumnScope.(ChannelToolRunner) -> Unit) {
    val fence = remember { app.repository.currentFence() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var uncertain by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ChannelToolCommand?>(null) }
    val verifiedText = gt("已写入并读回验证", "Saved and verified by readback")
    val uncertainText = gt("写入可能已生效。先重新读取确认，禁止直接重试。", "The write may have taken effect. Reload before retrying.")
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val genericError = gt("操作未完成。敏感错误详情已隐藏；请重新读取确认。", "Operation not completed. Sensitive error details are hidden; reload to confirm.")
    fun execute(command: ChannelToolCommand) {
        if (busy || (uncertain && command.write)) return
        busy = true; message = null; pending = null
        scope.launch {
            try {
                app.repository.verify(fence)
                val result = command.run()
                app.repository.verify(fence)
                if (command.recovery) uncertain = false
                message = result ?: if (command.write) verifiedText else null
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
                uncertain = uncertain || command.write
                message = if (chinese) gatewayErrorMessage(error) else genericError
            } finally { busy = false }
        }
    }
    val runner = ChannelToolRunner(busy, uncertain) { command ->
        if (!busy && !(uncertain && command.write)) { if (command.confirm) pending = command else execute(command) }
    }
    GatewaySheet(onDismissRequest = { if (!busy) dismiss() }, title = { Text(title) }, text = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val target = app.repository.instances.collectAsState().value.firstOrNull { it.id == fence.instanceId }
            GatewayGroupedCard { Text(target?.name.orEmpty()); Text(target?.address.orEmpty(), style = MaterialTheme.typography.bodySmall); Text(fence.projectId ?: gt("全局范围", "Global scope"), style = MaterialTheme.typography.bodySmall) }
            if (busy) GatewayBusy(gt("正在读取或执行并验证…", "Loading or executing and verifying…"))
            if (uncertain) GatewayNotice(uncertainText, true)
            message?.let { GatewayNotice(it, uncertain) }
            // SchemaValueEditor uses Material controls, not LocalGatewayEnabled; disable its whole subtree.
            if (!busy) content(runner)
        }
    }, confirmButton = { GatewayTextButton(onClick = dismiss, enabled = !busy) { Text(gt("完成", "Done")) } })
    pending?.let { command ->
        GatewaySheet(onDismissRequest = { pending = null }, title = { Text(gt("确认执行操作", "Confirm operation")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val instance = app.repository.instances.collectAsState().value.firstOrNull { it.id == fence.instanceId }
                Text(instance?.name.orEmpty()); Text(instance?.address.orEmpty()); Text(fence.projectId ?: gt("全局范围", "Global scope"))
                Text(command.title)
                Text(gt("目标实例与项目已锁定；操作完成后读回验证。测试请求会消耗上游额度。", "The instance and project are fixed. Writes are read back. Test requests consume upstream quota."))
                if (command.destructive) GatewayNotice(gt("此操作可能中断路由或永久删除配置，无法撤销。", "This may interrupt routing or permanently delete configuration. It cannot be undone."), true)
            }
        }, confirmButton = { GatewayButton(onClick = { execute(command) }, destructive = command.destructive) { Text(gt("授权并执行", "Authorize and execute")) } }, dismissButton = { GatewayTextButton(onClick = { pending = null }) { Text(gt("取消", "Cancel")) } })
    }
}
internal data class ChannelToolCommand(val title: String, val write: Boolean = false, val confirm: Boolean = write,
    val destructive: Boolean = false, val recovery: Boolean = false, val run: suspend () -> String?)
internal class ChannelToolRunner(val busy: Boolean, val uncertain: Boolean, val submit: (ChannelToolCommand) -> Unit)

@Composable internal fun ChannelKeysDialog(app: AxonHubApplication, id: String, dismiss: () -> Unit) {
    val tools = remember { GatewayToolsService(app.repository, app.adminCatalog) }
    var record by remember { mutableStateOf<JsonElement>(JsonNull) }
    var authorized by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val readTitle = gt("授权读取密钥", "Authorize key read")
    val successText = gt("测试成功", "Test succeeded")
    val failureText = gt("测试失败", "Test failed")
    ChannelToolSurface(app, gt("密钥管理", "Channel keys"), dismiss) { runner ->
        Text(gt("密钥仅在授权后读取，保留在内存中，不以明文显示。离开页面即丢弃。", "Keys are read only after authorization, held in memory and never displayed in plaintext. Leaving discards them."), style = MaterialTheme.typography.bodySmall)
        GatewayOutlineButton(onClick = { runner.submit(ChannelToolCommand(readTitle, confirm = true, recovery = true) {
            record = tools.channelKeys(id, true); authorized = true; selected = emptySet(); key = ""; null
        }) }, modifier = Modifier.fillMaxWidth()) { Text(if (authorized) gt("重新读取密钥", "Reload keys") else readTitle) }
        if (authorized) {
            GatewayField(key, { key = it }, label = { Text(gt("指定密钥（可选）", "Specific key (optional)")) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            GatewayField(model, { model = it }, label = { Text(gt("测试模型", "Test model")) }, modifier = Modifier.fillMaxWidth())
            Text(gt("测试会向上游发送请求并消耗额度。", "Tests send upstream requests and consume quota."), style = MaterialTheme.typography.bodySmall)
            ChannelKeyAction.entries.filter { it !in setOf(ChannelKeyAction.ENABLE_SELECTED, ChannelKeyAction.DELETE_DISABLED) }.forEach { action ->
                val label = keyActionLabel(action)
                val enabled = !runner.uncertain && (action in setOf(ChannelKeyAction.TEST_ALL, ChannelKeyAction.ENABLE_ALL) || key.isNotBlank())
                GatewayOutlineButton(onClick = {
                    val capturedKey = key; val capturedModel = model
                    runner.submit(ChannelToolCommand(label, write = action !in setOf(ChannelKeyAction.TEST, ChannelKeyAction.TEST_ALL), confirm = true) {
                        val result = tools.keyAction(id, action, true, capturedKey, model = capturedModel)
                        when (action) {
                            ChannelKeyAction.TEST_ALL -> "${result["successCount"].text} / ${result["total"].text} · ${result["failedCount"].text}"
                            ChannelKeyAction.TEST -> if (result["success"].boolOrNull == true) successText else failureText
                            else -> { record = result; key = ""; selected = emptySet(); null }
                        }
                    })
                }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
            IosSectionTitle(gt("已禁用密钥", "Disabled keys"))
            record["disabledAPIKeys"].arr.forEachIndexed { index, row ->
                val secret = row["key"].text
                GatewayGroupedCard {
                    Row { Text("${gt("密钥", "Key")} ${index + 1} · HTTP ${row["errorCode"].text} · ${if (secret == "__oauth__") "OAuth" else "API Key"}", Modifier.weight(1f)); Checkbox(secret in selected, { selected = if (it) selected + secret else selected - secret }, enabled = !runner.uncertain) }
                    Text(row["expiresAt"].text, style = MaterialTheme.typography.bodySmall)
                }
            }
            listOf(ChannelKeyAction.ENABLE_SELECTED, ChannelKeyAction.DELETE_DISABLED).forEach { action ->
                val label = keyActionLabel(action)
                GatewayOutlineButton(onClick = {
                    val keys = selected.sorted()
                    runner.submit(ChannelToolCommand(label, write = true, destructive = action == ChannelKeyAction.DELETE_DISABLED) {
                        record = tools.keyAction(id, action, true, keys = keys); selected = emptySet(); key = ""; null
                    })
                }, enabled = selected.isNotEmpty() && !runner.uncertain && (action != ChannelKeyAction.DELETE_DISABLED || "__oauth__" !in selected), modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        }
    }
}
@Composable private fun keyActionLabel(action: ChannelKeyAction): String = gt(action.label, when (action) {
    ChannelKeyAction.TEST_ALL -> "Test all keys"; ChannelKeyAction.TEST -> "Test specific key"
    ChannelKeyAction.ENABLE -> "Enable specific key"; ChannelKeyAction.DISABLE -> "Disable specific key"
    ChannelKeyAction.ENABLE_ALL -> "Enable all keys"; ChannelKeyAction.ENABLE_SELECTED -> "Enable selected keys"
    ChannelKeyAction.DELETE_DISABLED -> "Delete selected keys"
})
