package cc.khixang.axonhub.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import cc.khixang.axonhub.R
import java.util.Locale

/** Display-only translation. Never use translated labels as API values or Profile names.
 * Key-specific Japanese/Korean copy intentionally falls back to English.
 */
internal class KeyLocalization(private val locale: Locale, private val context: Context? = null) {
    fun text(english: String, vararg args: Any): String {
        val translated = when {
            context != null && english in sharedKeyResources -> context.getString(sharedKeyResources.getValue(english))
            locale.language != "zh" -> english
            locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO") -> keyTranslations[english]?.second ?: english
            else -> keyTranslations[english]?.first ?: english
        }
        return if (args.isEmpty()) translated else String.format(locale, translated, *args)
    }

    fun label(name: String): String = text(keyFieldLabels[name] ?: name
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replaceFirstChar(Char::uppercase))

    // The dropdown returns the original enum token; only its label is localized.
    fun option(value: String): String = text(keyOptionLabels[value] ?: value)
}

@Composable
internal fun keyLocalization(): KeyLocalization {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0] ?: Locale.getDefault()
    return remember(context, locale) { KeyLocalization(locale, context) }
}

/** Preserve non-Composable callers in management/schema forms. Explicit app language
 * takes precedence over the device locale, without caching the selected language.
 */
internal fun managementLabel(name: String): String = KeyLocalization(
    AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault(),
).label(name)

private val sharedKeyResources = mapOf(
    "Cancel" to R.string.ios_cancel,
    "Remove" to R.string.ios_remove,
    "Retry" to R.string.retry,
    "Status" to R.string.ws_status,
    "Search models" to R.string.ws_search_models,
    "Requests" to R.string.ws_requests,
    "Disabled" to R.string.ws_disabled,
)

private val keyFieldLabels = mapOf(
    "createAPIKey" to "Create API key",
    "updateAPIKey" to "Edit API key",
    "updateAPIKeyStatus" to "Change status",
    "updateAPIKeyProfiles" to "Configure Profiles",
    "updateProjectProfiles" to "Configure Profiles",
    "rotateAPIKey" to "Rotate API key",
    "bulkEnableAPIKeys" to "Enable selected keys",
    "bulkDisableAPIKeys" to "Disable selected keys",
    "bulkArchiveAPIKeys" to "Archive selected keys",
    "loadApiKeyProfileTemplate" to "Load policy template",
    "key" to "API key",
    "name" to "Name",
    "type" to "Type",
    "status" to "Status",
    "allowedIps" to "Allowed IPs / CIDRs",
    "scopes" to "Permissions",
    "projectID" to "Project",
    "projectId" to "Project",
    "activeProfile" to "Active Profile",
    "channelIDs" to "Allowed channels",
    "channelTags" to "Channel tags",
    "modelIDs" to "Allowed models",
    "loadBalanceStrategy" to "Load balancing",
    "traceStickyMode" to "Trace affinity",
    "channelTagsMatchMode" to "Tag matching",
    "requests" to "Requests",
    "requestCount" to "Requests",
    "totalTokens" to "Total tokens",
    "cost" to "Cost (USD)",
    "totalCost" to "Total cost (USD)",
    "inputTokens" to "Input tokens",
    "outputTokens" to "Output tokens",
    "cachedTokens" to "Cached tokens",
    "reasoningTokens" to "Reasoning tokens",
    "profiles" to "Profiles",
    "quota" to "Quota",
    "period" to "Period",
    "profileName" to "Profile",
    "modelMappings" to "Model mappings",
    "templateID" to "Template ID",
    "templateName" to "Template name",
    "apiKeyId" to "API key ID",
    "createdAt" to "Created at",
    "updatedAt" to "Updated at",
    "archivedAt" to "Archived at",
    "expiresAt" to "Expires at",
    "usage" to "Usage",
    "from" to "From",
    "to" to "To",
    "pastDuration" to "Duration",
    "calendarDuration" to "Calendar period",
    "value" to "Value",
    "unit" to "Unit",
)

private val keyOptionLabels = mapOf(
    "user" to "User",
    "service_account" to "Service account",
    "noauth" to "No authentication",
    "personal" to "Personal",
    "any" to "Any tag",
    "all" to "All tags",
    "none" to "No tags",
    "default" to "Default",
    "system_default" to "Default",
    "adaptive" to "Adaptive",
    "failover" to "Failover",
    "circuit-breaker" to "Circuit breaker",
    "round-robin" to "Round robin",
    "disabled" to "Disabled",
    "prefer_previous_channel" to "Prefer previous channel",
    "all_time" to "All time",
    "past_duration" to "Rolling window",
    "calendar_duration" to "Calendar period",
    "minute" to "Minute",
    "hour" to "Hour",
    "day" to "Day",
    "month" to "Month",
)

private val keyTranslations = mapOf(
    "API key" to ("API 密钥" to "API 金鑰"),
    "Key" to ("密钥" to "金鑰"),
    "Create API key" to ("创建 API 密钥" to "建立 API 金鑰"),
    "Edit API key" to ("编辑 API 密钥" to "編輯 API 金鑰"),
    "Change status" to ("更改状态" to "變更狀態"),
    "Configure Profiles" to ("配置策略" to "設定策略"),
    "Rotate API key" to ("轮换 API 密钥" to "輪替 API 金鑰"),
    "Enable selected keys" to ("启用所选密钥" to "啟用所選金鑰"),
    "Disable selected keys" to ("禁用所选密钥" to "停用所選金鑰"),
    "Archive selected keys" to ("归档所选密钥" to "封存所選金鑰"),
    "Load policy template" to ("加载策略模板" to "載入策略範本"),
    "Name" to ("名称" to "名稱"),
    "Type" to ("类型" to "類型"),
    "Status" to ("状态" to "狀態"),
    "Allowed IPs / CIDRs" to ("允许的 IP / CIDR" to "允許的 IP / CIDR"),
    "Permissions" to ("权限" to "權限"),
    "Project" to ("项目" to "專案"),
    "Active Profile" to ("当前策略" to "目前策略"),
    "Allowed channels" to ("允许的渠道" to "允許的通道"),
    "Channel tags" to ("渠道标签" to "通道標籤"),
    "Allowed models" to ("允许的模型" to "允許的模型"),
    "Load balancing" to ("负载均衡" to "負載平衡"),
    "Trace affinity" to ("追踪亲和性" to "追蹤親和性"),
    "Tag matching" to ("标签匹配" to "標籤比對"),
    "Requests" to ("请求数" to "請求數"),
    "Total tokens" to ("总 Token 数" to "總 Token 數"),
    "Cost (USD)" to ("费用（美元）" to "費用（美元）"),
    "Total cost (USD)" to ("总费用（美元）" to "總費用（美元）"),
    "Input tokens" to ("输入 Token 数" to "輸入 Token 數"),
    "Output tokens" to ("输出 Token 数" to "輸出 Token 數"),
    "Cached tokens" to ("缓存 Token 数" to "快取 Token 數"),
    "Reasoning tokens" to ("推理 Token 数" to "推理 Token 數"),
    "Basic information" to ("基本信息" to "基本資訊"),
    "Key type" to ("密钥类型" to "金鑰類型"),
    "Access restrictions" to ("访问限制" to "存取限制"),
    "Project keys use project permissions; only service-account keys have editable permissions." to ("项目密钥使用项目权限；只有服务账户密钥可以编辑权限。" to "專案金鑰使用專案權限；只有服務帳戶金鑰可以編輯權限。"),
    "One item per line; leave blank for no restriction" to ("每行一项；留空表示不限制" to "每行一項；留空表示不限制"),
    "Collapse" to ("收起" to "收合"),
    "Expand" to ("展开" to "展開"),
    "Profile name" to ("策略名称" to "策略名稱"),
    "Template: %s" to ("模板：%s" to "範本：%s"),
    "Unlink template" to ("解除模板关联" to "解除範本關聯"),
    "Channel restrictions" to ("渠道限制" to "通道限制"),
    "Refresh the workspace to load channel choices." to ("刷新工作区以加载可选渠道。" to "重新整理工作區以載入可選通道。"),
    "Model permissions and mappings" to ("模型权限与映射" to "模型權限與對應"),
    "Search models" to ("搜索模型" to "搜尋模型"),
    "Select all" to ("全选" to "全選"),
    "Deselect all" to ("取消全选" to "取消全選"),
    "Allowed model IDs (manual)" to ("允许的模型 ID（手动填写）" to "允許的模型 ID（手動輸入）"),
    "Requested model" to ("请求模型" to "請求模型"),
    "Actual model" to ("实际模型" to "實際模型"),
    "Remove mapping" to ("移除映射" to "移除對應"),
    "Add model mapping" to ("添加模型映射" to "新增模型對應"),
    "Routing" to ("路由" to "路由"),
    "Quota limits" to ("额度限制" to "額度限制"),
    "Enable quota limits" to ("启用额度限制" to "啟用額度限制"),
    "Remove Profile" to ("移除策略" to "移除策略"),
    "Add Profile" to ("添加策略" to "新增策略"),
    "Remove Profile?" to ("移除策略？" to "移除策略？"),
    "Remove" to ("移除" to "移除"),
    "Cancel" to ("取消" to "取消"),
    "Request limit" to ("请求数上限" to "請求數上限"),
    "Token limit" to ("Token 数上限" to "Token 數上限"),
    "Cost limit (USD)" to ("费用上限（美元）" to "費用上限（美元）"),
    "Quota period" to ("额度周期" to "額度週期"),
    "Window length" to ("时间窗口长度" to "時間視窗長度"),
    "Unit" to ("单位" to "單位"),
    "Select the key's project first" to ("请先选择密钥所属项目" to "請先選擇金鑰所屬專案"),
    "Template pagination did not advance" to ("模板分页未推进" to "範本分頁未前進"),
    "Policy template" to ("策略模板" to "策略範本"),
    "Loading a template immediately updates server policy. Save other changes first." to ("加载模板会立即更新服务器策略，请先保存其他更改。" to "載入範本會立即更新伺服器策略，請先儲存其他變更。"),
    "Template" to ("模板" to "範本"),
    "Choose a template" to ("选择模板" to "選擇範本"),
    "No templates in this project." to ("此项目没有模板。" to "此專案沒有範本。"),
    "Token and quota usage" to ("Token 与额度用量" to "Token 與額度用量"),
    "Retry" to ("重试" to "重試"),
    "Hide" to ("隐藏" to "隱藏"),
    "Show" to ("显示" to "顯示"),
    "Copied" to ("已复制" to "已複製"),
    "Copy" to ("复制" to "複製"),
    "Unable to read this API key" to ("无法读取此 API 密钥" to "無法讀取此 API 金鑰"),
    "User" to ("用户" to "使用者"),
    "Service account" to ("服务账户" to "服務帳戶"),
    "No authentication" to ("无需认证" to "無須驗證"),
    "Personal" to ("个人" to "個人"),
    "Any tag" to ("匹配任意标签" to "比對任一標籤"),
    "All tags" to ("匹配所有标签" to "比對所有標籤"),
    "No tags" to ("不匹配任何标签" to "不比對任何標籤"),
    "Default" to ("默认" to "預設"),
    "Adaptive" to ("自适应" to "自適應"),
    "Failover" to ("故障转移" to "容錯移轉"),
    "Circuit breaker" to ("熔断" to "斷路保護"),
    "Round robin" to ("轮询" to "輪詢"),
    "Disabled" to ("禁用" to "停用"),
    "Prefer previous channel" to ("优先使用上一渠道" to "優先使用上一通道"),
    "All time" to ("不限时间" to "不限時間"),
    "Rolling window" to ("滚动时间窗口" to "滾動時間視窗"),
    "Calendar period" to ("日历周期" to "日曆週期"),
    "Minute" to ("分钟" to "分鐘"),
    "Hour" to ("小时" to "小時"),
    "Day" to ("天" to "天"),
    "Month" to ("月" to "月"),
    "Profiles" to ("策略列表" to "策略清單"),
    "Quota" to ("额度" to "額度"),
    "Period" to ("周期" to "週期"),
    "Profile" to ("策略" to "策略"),
    "Model mappings" to ("模型映射" to "模型對應"),
    "Template ID" to ("模板 ID" to "範本 ID"),
    "Template name" to ("模板名称" to "範本名稱"),
    "API key ID" to ("API 密钥 ID" to "API 金鑰 ID"),
    "Created at" to ("创建时间" to "建立時間"),
    "Updated at" to ("更新时间" to "更新時間"),
    "Archived at" to ("归档时间" to "封存時間"),
    "Expires at" to ("过期时间" to "到期時間"),
    "Usage" to ("用量" to "用量"),
    "From" to ("源模型" to "來源模型"),
    "To" to ("目标模型" to "目標模型"),
    "Duration" to ("持续时间" to "持續時間"),
    "Value" to ("数值" to "數值"),
)
