package cc.khixang.axonhub.management

import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.data.AxonRepository
import cc.khixang.axonhub.network.AxonException
import cc.khixang.axonhub.network.AxonSession
import kotlinx.serialization.json.*

class AdminService(private val repository: AxonRepository, val catalog: AdminCatalog) {
    data class Invitation(val token: String, val metadata: JsonObject)
    suspend fun read(id: String, variables: JsonObject = buildJsonObject {}): JsonElement = repository.fenced { session, project ->
        val op = catalog.operation(id)
        require(!op.mutation) { "Use the verified mutation path" }
        catalog.schema.validate(variables, op.variables, mutation = false)
        repository.api.graphQl(session, catalog.document(op), variables, project)[op.root] ?: JsonNull
    }

    suspend fun revealKey(id: String): String = KeyEditorPolicy.revealedKey(
        read("revealAPIKey", buildJsonObject { put("id", id) }), id,
    )

    suspend fun page(id: String, variables: JsonObject): Page<JsonObject> {
        val root = read(id, variables)
        return parseRelayPage(root)
    }

    suspend fun detail(entity: String, id: String): JsonElement {
        val operation = when (entity) {
            "APIKey" -> "detailAPIKey"; "APIKeyProfileTemplate" -> "detailAPIKeyProfileTemplate"
            "User" -> "detailUser"; "Role" -> "detailRole"; "Project" -> "detailProject"
            "DataStorage" -> "detailDataStorage"; "Prompt" -> "detailPrompt"
            "PromptProtectionRule" -> "detailPromptProtectionRule"; else -> throw IllegalArgumentException("Unsupported entity")
        }
        return read(operation, buildJsonObject { put("id", id) })
    }

    suspend fun createInvitation(roleId: String, expiresInHours: Int, maxUses: Int): Invitation = repository.fencedMutation { session, project ->
        val projectId = project ?: throw IllegalArgumentException("Select a project first.")
        val numericRole = roleId.substringAfterLast('/').toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("Choose a valid project role.")
        val role = detailWith(session, project, "Role", roleId)
        if (role["id"].text != roleId || role["projectID"].text != projectId || role["level"].text != "project") throw AxonException.InvalidResponse
        val response = repository.api.createInvitation(session, projectId, numericRole, expiresInHours, maxUses)
        val token = response["token"].text
        if (!token.matches(Regex("[A-Za-z0-9_-]{8,}"))) throw AxonException.VerificationFailed
        val exact = repository.api.invitationDetail(session, token)
        listOf("projectName", "expiresAt", "maxUses", "usedCount", "remainingUses").forEach { key ->
            if (response[key] is JsonNull || exact[key] is JsonNull || response[key] != exact[key]) throw AxonException.VerificationFailed
        }
        Invitation(token, exact)
    }

    suspend fun restore(input: JsonObject, file: ByteArray): JsonElement = repository.fencedMutation { session, project ->
        require(file.isNotEmpty() && file.size <= MAX_BACKUP_BYTES) { "Choose a JSON backup smaller than 50 MiB." }
        val backup = runCatching { Json.parseToJsonElement(file.toString(Charsets.UTF_8)).obj }
            .getOrElse { throw IllegalArgumentException("The selected file is not valid JSON.") }
        require(backup["version"].text.isNotBlank() && "channels" in backup && "models" in backup) {
            "The selected file is not an AxonHub backup."
        }
        val operation = catalog.operation("restore")
        val inputField = operation.variables.first { it.name == "input" }
        catalog.schema.validate(buildJsonObject { put("input", input) }, listOf(inputField), mutation = true)
        val response = repository.api.graphQlMultipart(session, catalog.document(operation), input, file, project)[operation.root]
            ?: throw AxonException.InvalidResponse
        if (response["success"].boolOrNull != true) throw AxonException.VerificationFailed
        val version = readWith(session, project, "systemVersion")
        if (version["version"].text.isBlank()) throw AxonException.VerificationFailed
        response
    }

    suspend fun execute(id: String, originalVariables: JsonObject, baseline: JsonElement = JsonNull, expectedFence: TargetFence? = null): JsonElement = repository.fencedMutation { session, project ->
        expectedFence?.let(repository::verify)
        val operation = catalog.operation(id)
        require(operation.mutation) { "Not a mutation" }
        var effectiveBaseline = baseline
        if (effectiveBaseline is JsonNull && operation.entity.isNotBlank() && operation.entity != "ProjectUser" && !operation.root.startsWith("create")) {
            val ids = targetIds(operation, originalVariables)
            if (ids.size == 1) effectiveBaseline = detailWith(session, project, operation.entity, ids.single())
        }
        val variables = prepare(operation, originalVariables, effectiveBaseline)
        catalog.schema.validate(variables, operation.variables, mutation = true)

        // Establish read authority before writing so a missing target cannot look like a successful delete.
        val targetIds = targetIds(operation, variables)
        if (operation.entity.isNotBlank() && operation.entity != "ProjectUser" && !operation.root.startsWith("create")) {
            targetIds.forEach { if (detailWith(session, project, operation.entity, it) is JsonNull) throw AxonException.InvalidResponse }
        }
        expectedFence?.let(repository::verify)
        val data = repository.api.graphQl(
            session,
            catalog.document(operation),
            variables,
            project,
            if (operation.root == "backup") MAX_BACKUP_RESPONSE_BYTES else 8L * 1024 * 1024,
        )
        val response = data[operation.root] ?: throw AxonException.InvalidResponse
        if (response is JsonNull || response.boolOrNull == false || (response["success"] !is JsonNull && response["success"].boolOrNull == false)) throw AxonException.VerificationFailed
        if (operation.root != "previewPromptProtectionRule") verify(session, project, operation, variables, response, effectiveBaseline)
        response
    }

    private fun prepare(op: AdminOperation, variables: JsonObject, baseline: JsonElement): JsonObject {
        if (containsTruncatedObject(baseline)) throw IllegalArgumentException("Reload the complete server object before editing it")
        val output = variables.toMutableMap()
        var input = output["input"]?.obj?.toMutableMap()
        if (input != null) {
            if (op.allowedInputFields.isNotEmpty() && !input.keys.all { it in op.allowedInputFields }) throw IllegalArgumentException("Unsupported input field")
            val inputField = op.variables.firstOrNull { it.name == "input" }
            if (op.replacement && inputField != null) {
                if (baseline is JsonNull && op.root != "saveProxyPreset") throw AxonException.InvalidResponse
                val source = if (op.root == "saveProxyPreset" && baseline is JsonArray) {
                    baseline.firstOrNull { it["url"].text == input["url"].text } ?: buildJsonObject {}
                } else baseline
                input = catalog.schema.project(source, inputField.type).obj.toMutableMap().apply { putAll(input!!) }
            } else if (baseline !is JsonNull && inputField != null && !op.root.startsWith("create") && op.root !in setOf("updateAPIKeyProfiles", "updateProjectProfiles")) {
                input = dirtyObject(input, catalog.schema.project(baseline, inputField.type).obj).toMutableMap()
            }
            if (op.root in setOf("updateAPIKeyProfiles", "updateProjectProfiles")) {
                if (baseline is JsonNull || inputField == null) throw AxonException.InvalidResponse
                input = catalog.schema.project(baseline["profiles"], inputField.type).obj.toMutableMap().apply { putAll(input!!) }
            }
            if (op.root == "updateDataStorage") {
                val patch = input["settings"]
                if (patch is JsonObject) {
                    val merged = baseline["settings"].obj.toMutableMap()
                    patch.forEach { (key, value) ->
                        merged[key] = if (key in setOf("s3", "gcs", "webdav") && value is JsonObject) JsonObject(merged[key].obj + value) else value
                    }
                    val settingsType = inputField?.let { catalog.schema.types[catalog.schema.base(it.type)] }?.fields?.firstOrNull { it.name == "settings" }?.type
                        ?: throw IllegalArgumentException("Missing storage settings schema")
                    input["settings"] = catalog.schema.project(JsonObject(merged), settingsType)
                }
            }
            if (op.root == "updateAPIKey") {
                input = KeyEditorPolicy.prepareKeyPatch(JsonObject(input), baseline["type"].text).toMutableMap()
            }
            if (op.root == "createAPIKey" && input["type"].text != "service_account" && "scopes" in input) throw IllegalArgumentException("Scopes are only valid for service-account keys")
            if (op.root == "updateSecuritySettings") input["blockedIPs"]?.let { value ->
                input["blockedIPs"] = JsonArray(value.arr.map { it.text.trim() }.filter(String::isNotBlank).distinct().map(::JsonPrimitive))
            }
            if (op.root == "updateCatalogSettings") {
                input["upstreamURL"]?.let { input["upstreamURL"] = JsonPrimitive(it.text.trim()) }
                input["refreshSeconds"]?.intOrNull?.let { input["refreshSeconds"] = JsonPrimitive(if (it <= 0) 3600 else it.coerceIn(60, 604800)) }
            }
            if (op.root == "updateVideoStorageSettings") listOf("scanIntervalMinutes", "scanLimit").forEach { key ->
                input[key]?.intOrNull?.let { require(it > 0) { "$key must be positive" } }
            }
            if (op.root == "updateRetryPolicy") {
                input["loadBalancerStrategy"]?.text?.let { strategy ->
                    require(strategy in setOf("adaptive", "failover", "circuit-breaker", "round-robin", "weighted")) { "Invalid load-balancer strategy" }
                    if (strategy == "weighted") input["loadBalancerStrategy"] = JsonPrimitive("failover")
                }
                listOf("streamFirstEventTimeoutSeconds", "nonStreamResponseTimeoutSeconds").forEach { key ->
                    input[key]?.intOrNull?.let { input[key] = JsonPrimitive(it.coerceIn(0, 600)) }
                }
                input["upstreamErrorPolicy"]?.obj?.takeIf { it["mode"].text == "custom" && it["customMessage"].text.isBlank() }?.let { policy ->
                    input["upstreamErrorPolicy"] = JsonObject(policy + ("mode" to JsonPrimitive("hidden")))
                }
            }
            if (op.root in setOf("updateAPIKeyProfiles", "updateProjectProfiles")) {
                val isKey = op.root == "updateAPIKeyProfiles"
                KeyEditorPolicy.validateProfiles(JsonObject(input), isKey)
                input["profiles"] = JsonArray(input["profiles"].arr.map { KeyEditorPolicy.normalizeProfile(it, isKey) })
            }
            output["input"] = JsonObject(input)
            rejectMaskedSecrets(output["input"] ?: JsonNull)
        }
        output["profile"]?.takeUnless { it is JsonNull }?.let { output["profile"] = normalizeProfile(it) }
        output["profile"]?.let(::rejectMaskedSecrets)
        return JsonObject(output.filterValues { it !is JsonNull })
    }

    private fun normalizeProfile(value: JsonElement): JsonObject = KeyEditorPolicy.normalizeProfile(value, true)

    private fun containsTruncatedObject(value: JsonElement): Boolean = when (value) {
        is JsonObject -> (value.size == 1 && "__typename" in value) || value.values.any(::containsTruncatedObject)
        is JsonArray -> value.any(::containsTruncatedObject)
        else -> false
    }

    private fun rejectMaskedSecrets(value: JsonElement, sensitive: Boolean = false) {
        when (value) {
            is JsonObject -> value.forEach { (key, item) -> rejectMaskedSecrets(item, sensitive || SensitiveFields.matches(key)) }
            is JsonArray -> value.forEach { rejectMaskedSecrets(it, sensitive) }
            is JsonPrimitive -> if (sensitive && value.text.let { it.contains("••") || it.matches(Regex("[*•]{3,}")) }) throw IllegalArgumentException("Masked credentials cannot be submitted")
            else -> Unit
        }
    }

    private fun dirtyObject(candidate: Map<String, JsonElement>, baseline: JsonObject): JsonObject = JsonObject(candidate.filter { (key, value) -> value != baseline[key] })

    private fun targetIds(op: AdminOperation, variables: JsonObject): List<String> {
        variables["id"]?.text?.takeIf(String::isNotBlank)?.let { return listOf(it) }
        variables["ids"]?.arr?.map { it.text }?.filter(String::isNotBlank)?.let { if (it.isNotEmpty()) return it }
        if (op.root == "loadApiKeyProfileTemplate") return listOf(variables["input"]["apiKeyID"].text)
        return emptyList()
    }

    private suspend fun verify(session: AxonSession, project: String?, op: AdminOperation, variables: JsonObject, response: JsonElement, baseline: JsonElement) {
        if (op.entity == "ProjectUser") {
            val input = variables["input"]
            val projectId = input["projectId"].text
            val userId = input["userId"].text
            if (projectId.isBlank() || userId.isBlank()) throw AxonException.VerificationFailed
            val actual = detailWith(session, project, "Project", projectId)
            val membership = actual["projectUsers"].arr.firstOrNull { it["userID"].text == userId }
            if (op.root == "removeUserFromProject") {
                if (membership != null) throw AxonException.VerificationFailed
            } else {
                membership ?: throw AxonException.VerificationFailed
                input["isOwner"].takeUnless { it is JsonNull }?.let { if (membership["isOwner"] != it) throw AxonException.VerificationFailed }
                input["scopes"].takeUnless { it is JsonNull }?.let { if (membership["scopes"] != it) throw AxonException.VerificationFailed }
                val roleIds = membership["user"]["roles"]["edges"].arr.map { it["node"]["id"].text }.toSet()
                input["roleIDs"]?.arr?.map { it.text }?.let { if (it.any { id -> id !in roleIds }) throw AxonException.VerificationFailed }
                input["addRoleIDs"]?.arr?.map { it.text }?.let { if (it.any { id -> id !in roleIds }) throw AxonException.VerificationFailed }
                input["removeRoleIDs"]?.arr?.map { it.text }?.let { if (it.any { id -> id in roleIds }) throw AxonException.VerificationFailed }
            }
            return
        }
        if (op.root == "backup") {
            if (response["success"].boolOrNull != true) throw AxonException.VerificationFailed
            val backup = runCatching { Json.parseToJsonElement(response["data"].text) }.getOrNull() ?: throw AxonException.VerificationFailed
            if (backup["version"].text.isBlank()) throw AxonException.VerificationFailed
            if (readWith(session, project, "systemVersion")["version"].text.isBlank()) throw AxonException.VerificationFailed
            return
        }
        if (op.entity.isNotBlank() && op.entity != "ProjectUser") {
            val ids = if (op.root.startsWith("create")) listOf(response["id"].text) else targetIds(op, variables)
            if (ids.isEmpty() || ids.any(String::isBlank)) throw AxonException.VerificationFailed
            ids.forEach { id ->
                val actual = detailWith(session, project, op.entity, id)
                if (op.root.lowercase().contains("delete")) {
                    if (actual !is JsonNull) throw AxonException.VerificationFailed
                } else {
                    if (actual["id"].text != id) throw AxonException.VerificationFailed
                    val expected = variables["input"].obj.toMutableMap()
                    variables["profile"]?.takeUnless { it is JsonNull }?.let { profileValue ->
                        if (op.root.endsWith("ApiKeyProfileTemplate")) {
                            val profile = profileValue.obj.filterKeys { it !in setOf("templateID", "templateName") }.toMutableMap()
                            profile["name"] = expected["name"] ?: baseline["name"]
                            expected["profile"] = JsonObject(profile)
                        }
                    }
                    variables["status"]?.takeUnless { it is JsonNull }?.let { expected["status"] = it }
                    if (op.root.startsWith("bulk")) {
                        val status = when { op.root.contains("Enable") -> "enabled"; op.root.contains("Disable") -> "disabled"; op.root.contains("Archive") -> "archived"; else -> "" }
                        if (status.isNotBlank()) expected["status"] = JsonPrimitive(status)
                    }
                    if (op.root == "loadApiKeyProfileTemplate") {
                        if (actual["profiles"] != response["profiles"]) throw AxonException.VerificationFailed
                    } else if (op.root == "updateAPIKeyProfiles" || op.root == "updateProjectProfiles") {
                        val normalized = expected.toMutableMap()
                        normalized["profiles"] = JsonArray(expected["profiles"].arr.map { JsonObject(it.obj.filterKeys { key -> key !in setOf("templateID", "templateName") }) })
                        val inputType = op.variables.firstOrNull { it.name == "input" }?.type ?: throw AxonException.VerificationFailed
                        val observed = catalog.schema.project(actual["profiles"], inputType)
                        if (!KeyEditorPolicy.profilesReadbackMatches(observed, JsonObject(normalized))) throw AxonException.VerificationFailed
                    } else verifySubset(actual, expected, baseline)
                }
            }
            if (op.root == "rotateAPIKey") {
                val mutationKey = response["key"].text
                val reveal = readWith(session, project, "revealAPIKey", buildJsonObject { put("id", ids.single()) })
                if (mutationKey.isBlank() || reveal["key"].text != mutationKey) throw AxonException.VerificationFailed
            }
            return
        }
        if (op.verification.isBlank()) {
            if (!op.asyncEffect) throw AxonException.VerificationFailed
            return
        }
        val verifyOp = catalog.operation(op.verification)
        val verifyVars = when (op.root) {
            "triggerGcCleanup" -> variables
            "clearCache" -> buildJsonObject { variables["input"]?.let { put("input", it) } }
            else -> buildJsonObject {}
        }
        val actual = repository.api.graphQl(session, catalog.document(verifyOp), verifyVars, project)[verifyOp.root] ?: JsonNull
        if (!op.asyncEffect) {
            when (op.root) {
                "updateDefaultDataStorage" -> if (actual.text != variables["input"]["dataStorageID"].text) throw AxonException.VerificationFailed
                "deleteProxyPreset" -> if (actual.arr.any { it["url"].text == variables["url"].text }) throw AxonException.VerificationFailed
                "unlinkOIDCIdentity" -> if (actual["identities"].arr.any { it["id"].text == variables["id"].text }) throw AxonException.VerificationFailed
                "completeOnboarding" -> if (actual["onboarded"].boolOrNull != true) throw AxonException.VerificationFailed
                "completeSystemModelSettingOnboarding" -> if (actual["systemModelSetting"]["onboarded"].boolOrNull != true) throw AxonException.VerificationFailed
                "completeAutoDisableChannelOnboarding" -> if (actual["autoDisableChannel"]["onboarded"].boolOrNull != true) throw AxonException.VerificationFailed
                "refreshProvidersCatalog" -> if (actual["fetchedAt"] != response["fetchedAt"] || actual["data"] != response["data"]) throw AxonException.VerificationFailed
                "saveProxyPreset" -> actual.arr.firstOrNull { it["url"].text == variables["input"]["url"].text }?.let { verifySubset(it, variables["input"].obj, baseline) } ?: throw AxonException.VerificationFailed
                else -> {
                    val expected = variables["input"].obj
                    if (expected.isEmpty() && variables.isNotEmpty()) throw AxonException.VerificationFailed
                    verifySubset(actual, expected, baseline)
                }
            }
        }
    }

    private suspend fun detailWith(session: AxonSession, project: String?, entity: String, id: String): JsonElement {
        val name = when (entity) {
            "APIKey" -> "detailAPIKey"; "APIKeyProfileTemplate" -> "detailAPIKeyProfileTemplate"; "User" -> "detailUser"
            "Role" -> "detailRole"; "Project" -> "detailProject"; "DataStorage" -> "detailDataStorage"
            "Prompt" -> "detailPrompt"; "PromptProtectionRule" -> "detailPromptProtectionRule"; else -> return JsonNull
        }
        val op = catalog.operation(name)
        return repository.api.graphQl(session, catalog.document(op), buildJsonObject { put("id", id) }, project)[op.root] ?: JsonNull
    }

    private suspend fun readWith(session: AxonSession, project: String?, id: String, variables: JsonObject = buildJsonObject {}): JsonElement {
        val operation = catalog.operation(id)
        return repository.api.graphQl(session, catalog.document(operation), variables, project)[operation.root] ?: JsonNull
    }

    private fun verifySubset(actual: JsonElement, expected: Map<String, JsonElement>, baseline: JsonElement) {
        expected.forEach { (key, value) ->
            if (SensitiveFields.matches(key)) return@forEach
            if (key in setOf("projectID", "userID", "type") && key !in actual.obj) return@forEach
            if (key in setOf("settings", "profile", "s3", "gcs", "webdav", "proxy")) {
                verifySubset(actual[key], value.obj, baseline[key])
                return@forEach
            }
            if (key.startsWith("clear") && value.boolOrNull == true) {
                val target = key.removePrefix("clear").replaceFirstChar(Char::lowercase)
                val observed = actual[target]
                val cleared = observed is JsonNull || observed is JsonPrimitive && observed.text.isEmpty() || observed is JsonArray && observed.isEmpty() || observed is JsonObject && "edges" in observed && observed["edges"].arr.isEmpty()
                if (!cleared) throw AxonException.VerificationFailed
            } else if (key.startsWith("append")) {
                val target = key.removePrefix("append").replaceFirstChar(Char::lowercase)
                if (actual[target].arr != baseline[target].arr + value.arr) throw AxonException.VerificationFailed
            } else if (key.startsWith("add") || key.startsWith("remove") || key in setOf("roleIDs", "userIDs")) {
                val relation = if (key.contains("Role")) "roles" else if (key.contains("User")) "users" else null
                if (relation != null) {
                    val ids = actual[relation]["edges"].arr.map { it["node"]["id"].text }.toSet()
                    val values = value.arr.map { it.text }
                    if ((!key.startsWith("remove") && values.any { it !in ids }) || (key.startsWith("remove") && values.any { it in ids })) throw AxonException.VerificationFailed
                }
            } else if (!matches(actual[key], value)) throw AxonException.VerificationFailed
        }
    }

    private fun matches(actual: JsonElement, expected: JsonElement): Boolean = when (expected) {
        is JsonObject -> expected.all { (key, value) -> matches(actual[key], value) }
        is JsonArray -> actual.arr.size == expected.size && actual.arr.zip(expected).all { matches(it.first, it.second) }
        else -> actual == expected
    }


    companion object {
        const val MAX_BACKUP_BYTES = 50 * 1024 * 1024
        private const val MAX_BACKUP_RESPONSE_BYTES = 64L * 1024 * 1024
    }
}

internal fun parseRelayPage(root: JsonElement): Page<JsonObject> = Page(
    root["edges"].arr.map { it["node"].obj },
    root["pageInfo"]["endCursor"].text.takeIf { root["pageInfo"]["hasNextPage"].boolOrNull == true },
    root["totalCount"].intOrNull,
)
