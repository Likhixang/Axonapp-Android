package cc.khixang.axonhub.management

import cc.khixang.axonhub.core.*
import kotlinx.serialization.json.*
import java.util.Locale

/** Shared by the native editors and the mutation boundary, including advanced forms. */
object KeyEditorPolicy {
    fun canEditScopes(type: String) = type == "service_account"
    fun channelNumericId(id: String): Int? {
        val guid = Regex("^gid://axonhub/Channel/([1-9][0-9]*)$").matchEntire(id)
        if (guid != null) return guid.groupValues[1].toIntOrNull()
        if (Regex("^[1-9][0-9]*$").matches(id)) return id.toIntOrNull()
        val decoded = runCatching { String(java.util.Base64.getDecoder().decode(id), Charsets.UTF_8) }.getOrNull() ?: return null
        val legacy = Regex("^Channel:([1-9][0-9]*)$").matchEntire(decoded) ?: return null
        return legacy.groupValues[1].toIntOrNull()
    }
    fun editableKeyInput(input: JsonObject, type: String): JsonObject = JsonObject(input.filterKeys { canEditScopes(type) || !it.contains("scopes", true) })
    fun prepareKeyPatch(input: JsonObject, type: String): JsonObject {
        require(canEditScopes(type) || input.keys.none { it.contains("scopes", true) }) { "Scopes can only be changed for service-account keys" }
        return JsonObject(input.toMutableMap().apply {
            listOf("scopes" to "clearScopes", "allowedIps" to "clearAllowedIps").forEach { (field, clear) ->
                if (this[field] is JsonArray && this[field].arr.isEmpty()) { remove(field); this[clear] = JsonPrimitive(true) }
            }
        })
    }
    fun normalizeProfile(value: JsonElement, isKey: Boolean): JsonObject {
        val profile = value.obj.toMutableMap()
        if (isKey) listOf(
            "loadBalanceStrategy" to setOf("default", "adaptive", "failover", "circuit-breaker", "round-robin"),
            "traceStickyMode" to setOf("default", "disabled", "prefer_previous_channel"),
        ).forEach { (key, allowed) ->
            val raw = profile[key].text
            val normalized = if (raw.isBlank() || raw == "system_default") "default" else raw
            require(normalized in allowed) { "Invalid $key" }
            profile[key] = JsonPrimitive(normalized)
        }
        return JsonObject(profile)
    }
    fun validateProfiles(value: JsonElement, isKey: Boolean) {
        val profiles = value["profiles"].arr
        val names = profiles.map { it["name"].text.trim().lowercase(Locale.ROOT) }
        require(profiles.isNotEmpty() && names.none(String::isBlank) && names.distinct().size == names.size && profiles.any { it["name"].text == value["activeProfile"].text }) { "Use unique Profile names and choose an active Profile" }
        if (!isKey) return
        profiles.forEach { profile ->
            profile["modelMappings"].arr.forEach { require(it["from"].text.isNotBlank() && it["to"].text.isNotBlank()) { "Complete both model mapping fields" } }
            val quota = profile["quota"]
            if (quota !is JsonNull) {
                require(listOf("requests", "totalTokens", "cost").any { quota[it] !is JsonNull }) { "Set at least one quota limit" }
                listOf("requests", "totalTokens").forEach { key ->
                    if (quota[key] !is JsonNull) require((quota[key] as? JsonPrimitive)?.let { !it.isString && it.intOrNull?.let { number -> number > 0 } == true } == true) { "$key must be a positive integer" }
                }
                if (quota["cost"] !is JsonNull) require(ManagementFormat.parse(quota["cost"].text)?.signum()?.let { it >= 0 } == true) { "Cost must be a non-negative USD amount" }
                val period = quota["period"]
                require(period["type"].text in setOf("all_time", "past_duration", "calendar_duration")) { "Choose a quota period" }
                if (period["type"].text == "past_duration") {
                    require(period["pastDuration"]["value"].intOrNull?.let { it > 0 } == true) { "Quota window must be positive" }
                    require(period["pastDuration"]["unit"].text in setOf("minute", "hour", "day")) { "Choose a quota window unit" }
                }
                if (period["type"].text == "calendar_duration") require(period["calendarDuration"]["unit"].text in setOf("day", "month")) { "Choose a calendar quota unit" }
            }
        }
    }
    fun replaceProfiles(value: JsonElement, profiles: List<JsonElement>): JsonObject = JsonObject(value.obj.toMutableMap().apply {
        put("profiles", JsonArray(profiles))
        if (profiles.none { it["name"].text == this["activeProfile"].text }) put("activeProfile", profiles.firstOrNull()?.get("name") ?: JsonPrimitive(""))
    })
    fun renameProfile(value: JsonElement, index: Int, name: String): JsonObject {
        val profiles = value["profiles"].arr.toMutableList()
        require(index in profiles.indices)
        val old = profiles[index]["name"].text
        profiles[index] = JsonObject(profiles[index].obj + ("name" to JsonPrimitive(name)))
        val updated = JsonObject(value.obj + ("profiles" to JsonArray(profiles)))
        return if (value["activeProfile"].text == old) JsonObject(updated + ("activeProfile" to JsonPrimitive(name))) else updated
    }
    fun profilesReadbackMatches(actual: JsonElement, expected: JsonElement): Boolean {
        fun clean(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.filterKeys { it !in setOf("templateID", "templateName") }.filterValues { it !is JsonNull }.mapValues { clean(it.value) })
            is JsonArray -> JsonArray(value.map(::clean))
            else -> value
        }
        return clean(actual) == clean(expected)
    }
    fun revealedKey(value: JsonElement, id: String): String {
        require(value["id"].text == id && value["key"].text.isNotBlank()) { "Unable to read the selected API key" }
        return value["key"].text
    }
}
