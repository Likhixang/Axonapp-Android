package cc.khixang.axonhub.playground

import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.data.AxonRepository
import cc.khixang.axonhub.network.AxonException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.IOException
import java.util.UUID

data class PlaygroundChoice(val id: String, val name: String)
data class PlaygroundChannel(val id: String, val name: String, val models: List<PlaygroundChoice>)
data class PlaygroundCatalog(val projects: List<PlaygroundChoice> = emptyList(), val channels: List<PlaygroundChannel> = emptyList(), val models: List<PlaygroundChoice> = emptyList(), val canUseGateway: Boolean = false)

class PlaygroundService(private val repository: AxonRepository, private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }) {
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun catalog(): PlaygroundCatalog = repository.fenced { session, project ->
        if (session.instance.authType == AuthType.API_KEY) return@fenced PlaygroundCatalog(models = repository.api.v1Models(session).map { PlaygroundChoice(it.modelId, it.name) })
        val identity = repository.api.graphQl(session, IDENTITY, projectId = null)
        val scopes = identity["me"]["scopes"].arr.map { it.text }
        val canUse = identity["me"]["isOwner"].boolOrNull == true || "*" in scopes || "read_channels" in scopes
        val channelsData = repository.api.graphQl(session, CHANNELS, projectId = project)
        val channels = channelsData["allChannelSummarys"].arr.mapNotNull { node ->
            val models = node["allModelEntries"].arr.map { PlaygroundChoice(it["requestModel"].text, it["requestModel"].text) }.distinctBy { it.id }.filter { it.id.isNotBlank() }
            models.takeIf(List<*>::isNotEmpty)?.let { PlaygroundChannel(node["id"].text, node["name"].text, models) }
        }
        val models = if (canUse) allAdminModels(session, project) else emptyList()
        PlaygroundCatalog(identity["myProjects"].arr.filter { it["status"].text == "active" }.map { PlaygroundChoice(it["id"].text, it["name"].text) }, channels, models, canUse)
    }

    private suspend fun allAdminModels(session: cc.khixang.axonhub.network.AxonSession, project: String?): List<PlaygroundChoice> {
        val result = mutableListOf<PlaygroundChoice>(); var after: String? = null; val seen = mutableSetOf<String>()
        do {
            val vars = buildJsonObject { after?.let { put("after", it) } }
            val page = repository.api.graphQl(session, MODELS, vars, project)["models"]
            result += page["edges"].arr.map { it["node"] }.map { node -> PlaygroundChoice(node["modelID"].text, node["name"].text.ifBlank { node["modelID"].text }) }
            if (page["pageInfo"]["hasNextPage"].boolOrNull != true) break
            val next = page["pageInfo"]["endCursor"].text
            if (next.isBlank() || !seen.add(next)) throw AxonException.InvalidResponse
            after = next
        } while (true)
        return result.distinctBy { it.id }.filter { it.id.isNotBlank() }
    }

    suspend fun stream(settings: PlaygroundSettings, messages: List<ChatMessage>, projectId: String? = null, channelId: String? = null, onUpdate: suspend (StreamResult) -> Unit): StreamResult = repository.fenced { session, boundProject ->
        val fence = repository.currentFence()
        val transcriptKey = repository.privateScopeKey("playground", fence)
        if (!projectId.isNullOrBlank() && projectId != boundProject) throw AxonException.TargetChanged
        require(settings.model.isNotBlank() && settings.temperature in 0.0..2.0 && settings.maxTokens > 0 && messages.lastOrNull()?.role == "user") { "Invalid playground input" }
        val protocol = if (session.instance.authType == AuthType.ADMIN) PlaygroundProtocol.ADMIN else settings.protocol
        val payload = payload(protocol, settings, messages)
        val request = request(session, protocol, settings.model, payload, projectId ?: boundProject, channelId)
        withContext(Dispatchers.IO) {
            val call = repository.api.client.newCall(request)
            val cancellationWatcher = CoroutineScope(currentCoroutineContext()).launch {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    when (response.code) { 401 -> throw AxonException.Unauthorized; 403 -> throw AxonException.Forbidden }
                    if (!response.isSuccessful) throw AxonException.HttpStatus(response.code)
                    if (!response.header("Content-Type").orEmpty().lowercase().contains("text/event-stream")) throw AxonException.InvalidResponse
                    val source = response.body?.source() ?: throw AxonException.InvalidResponse
                    val decoder = SseDecoder(); val accumulator = StreamAccumulator(protocol, UUID.randomUUID().toString())
                    val buffer = Buffer(); var lastPublish = 0L
                    while (!source.exhausted() && !accumulator.complete) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer, 8192); if (count < 0) break
                        var pending: StreamResult? = null
                        decoder.feed(buffer.readByteArray()) { event ->
                            accumulator.consume(event, json)
                            val now = System.nanoTime()
                            if (accumulator.complete || now - lastPublish >= 33_000_000) { pending = accumulator.result(); lastPublish = now }
                        }
                        pending?.let { update -> repository.verify(fence); onUpdate(update) }
                    }
                    if (!accumulator.complete) decoder.finish()?.let { accumulator.consume(it, json) }
                    val result = accumulator.result(); repository.verify(fence); onUpdate(result)
                    if (!result.complete) throw IOException("Stream interrupted")
                    repository.verify(fence); saveTranscript(transcriptKey, messages + result.message, settings.copy(protocol = protocol))
                    result
                }
            } finally { cancellationWatcher.cancel(); call.cancel() }
        }
    }

    fun loadTranscript(): SavedTranscript? {
        val key = repository.privateScopeKey("playground")
        return repository.loadPrivateState(key)?.let { runCatching { json.decodeFromString(SavedTranscript.serializer(), it) }.getOrNull() }
    }
    private fun saveTranscript(key: String, messages: List<ChatMessage>, settings: PlaygroundSettings) = repository.savePrivateState(key, json.encodeToString(SavedTranscript.serializer(), SavedTranscript(messages.takeLast(40), settings.model, settings.protocol)))
    fun clearTranscript() = repository.clearPrivateState(repository.privateScopeKey("playground"))

    private fun request(session: cc.khixang.axonhub.network.AxonSession, protocol: PlaygroundProtocol, model: String, payload: JsonObject, project: String?, channel: String?): Request {
        val path = when (protocol) {
            PlaygroundProtocol.ADMIN -> "admin/playground/chat"; PlaygroundProtocol.OPENAI_CHAT -> "v1/chat/completions"
            PlaygroundProtocol.OPENAI_RESPONSES -> "v1/responses"; PlaygroundProtocol.ANTHROPIC -> "v1/messages"
            PlaygroundProtocol.GEMINI -> "v1beta/models"
        }
        var url = repository.api.endpoint(session.instance.address, path, session.instance.allowHttp)
        if (protocol == PlaygroundProtocol.GEMINI) url = url.newBuilder().addPathSegment("$model:streamGenerateContent").addQueryParameter("alt", "sse").build()
        val builder = Request.Builder().url(url).post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(mediaType))
            .header("Authorization", "Bearer ${session.token}").header("Content-Type", "application/json").header("Accept", "text/event-stream")
        if (protocol == PlaygroundProtocol.ANTHROPIC) builder.header("anthropic-version", "2023-06-01")
        if (protocol == PlaygroundProtocol.ADMIN) {
            project?.takeIf(String::isNotBlank)?.let { builder.header("X-Project-ID", it) }
            channel?.takeIf(String::isNotBlank)?.let { builder.header("X-Channel-ID", it) }
        } else if (!project.isNullOrBlank() || !channel.isNullOrBlank()) throw AxonException.Forbidden
        return builder.build()
    }

    fun payload(protocol: PlaygroundProtocol, settings: PlaygroundSettings, messages: List<ChatMessage>): JsonObject = when (protocol) {
        PlaygroundProtocol.ADMIN -> buildJsonObject {
            put("model", settings.model); put("temperature", settings.temperature); put("max_tokens", settings.maxTokens); put("stream", true); put("system", settings.system)
            put("messages", JsonArray(messages.map { message -> buildJsonObject { put("id", message.id); put("role", message.role); put("parts", JsonArray(message.parts.map { part -> buildJsonObject { put("type", part.type); if (part.text.isNotEmpty()) put("text", part.text); if (part.type == "file") { put("mediaType", part.data["mediaType"]); put("filename", part.data["filename"]); put("url", part.data["url"]) } } })) } }))
        }
        PlaygroundProtocol.OPENAI_CHAT -> buildJsonObject {
            put("model", settings.model); put("temperature", settings.temperature); put("max_tokens", settings.maxTokens); put("stream", true); put("stream_options", buildJsonObject { put("include_usage", true) })
            put("messages", JsonArray(openAiMessages(settings.system, messages)))
        }
        PlaygroundProtocol.OPENAI_RESPONSES -> buildJsonObject {
            put("model", settings.model); put("temperature", settings.temperature); put("max_output_tokens", settings.maxTokens); put("stream", true); if (settings.system.isNotBlank()) put("instructions", settings.system)
            put("input", JsonArray(messages.map { m -> buildJsonObject { put("role", m.role); put("content", JsonArray(m.parts.mapNotNull { p -> when (p.type) { "text" -> buildJsonObject { put("type", if (m.role == "assistant") "output_text" else "input_text"); put("text", p.text) }; "file" -> buildJsonObject { put("type", "input_image"); put("image_url", p.data["url"]) }; else -> null } })) } }))
        }
        PlaygroundProtocol.ANTHROPIC -> buildJsonObject {
            put("model", settings.model); put("temperature", settings.temperature); put("max_tokens", settings.maxTokens); put("stream", true); if (settings.system.isNotBlank()) put("system", settings.system)
            put("messages", JsonArray(messages.filter { it.role != "system" }.map { m -> buildJsonObject { put("role", if (m.role == "assistant") "assistant" else "user"); put("content", JsonArray(m.parts.mapNotNull { p -> when (p.type) { "text" -> buildJsonObject { put("type", "text"); put("text", p.text) }; "file" -> buildJsonObject { put("type", "image"); put("source", buildJsonObject { put("type", "base64"); put("media_type", p.data["mediaType"]); put("data", p.data["base64"]) }) }; else -> null } })) } }))
        }
        PlaygroundProtocol.GEMINI -> buildJsonObject {
            if (settings.system.isNotBlank()) put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", settings.system) }) }) })
            put("contents", JsonArray(messages.map { m -> buildJsonObject { put("role", if (m.role == "assistant") "model" else "user"); put("parts", JsonArray(m.parts.mapNotNull { p -> when (p.type) { "text" -> buildJsonObject { put("text", p.text) }; "file" -> buildJsonObject { put("inlineData", buildJsonObject { put("mimeType", p.data["mediaType"]); put("data", p.data["base64"]) }) }; else -> null } })) } }))
            put("generationConfig", buildJsonObject { put("temperature", settings.temperature); put("maxOutputTokens", settings.maxTokens) })
        }
    }

    private fun openAiMessages(system: String, messages: List<ChatMessage>): List<JsonElement> = buildList {
        if (system.isNotBlank()) add(buildJsonObject { put("role", "system"); put("content", system) })
        messages.forEach { m -> add(buildJsonObject {
            put("role", m.role)
            val supported = m.parts.filter { it.type == "text" || it.type == "file" }
            if (supported.any { it.type == "file" }) put("content", JsonArray(supported.map { part -> if (part.type == "text") buildJsonObject { put("type", "text"); put("text", part.text) } else buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", part.data["url"]) }) } }))
            else put("content", supported.joinToString("\n") { it.text })
        }) }
    }

    companion object {
        const val IDENTITY = "query PlaygroundIdentity { me { isOwner scopes } myProjects { id name status } }"
        const val CHANNELS = "query PlaygroundChannels { allChannelSummarys(includeArchived: false) { id name allModelEntries { requestModel } } }"
        const val MODELS = "query PlaygroundModels(${'$'}after: Cursor) { models(first: 200, after: ${'$'}after, orderBy: { field: NAME, direction: ASC }, where: { statusIn: [enabled], typeIn: [chat] }) { edges { node { modelID name } } pageInfo { hasNextPage endCursor } } }"
    }
}
