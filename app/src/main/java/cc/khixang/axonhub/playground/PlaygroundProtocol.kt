package cc.khixang.axonhub.playground

import cc.khixang.axonhub.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

enum class PlaygroundProtocol { ADMIN, OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC, GEMINI }

@Serializable data class ChatPart(val type: String, val text: String = "", val name: String = "", val data: JsonElement = JsonNull)
@Serializable data class ChatMessage(val id: String, val role: String, val parts: List<ChatPart>, val incomplete: Boolean = false)
@Serializable data class SavedTranscript(val messages: List<ChatMessage> = emptyList(), val model: String = "", val protocol: PlaygroundProtocol = PlaygroundProtocol.OPENAI_CHAT)

data class PlaygroundSettings(val model: String, val protocol: PlaygroundProtocol, val system: String, val temperature: Double, val maxTokens: Int)
data class StreamResult(val message: ChatMessage, val complete: Boolean, val finishReason: String = "", val usage: JsonElement = JsonNull)

class SseDecoder(private val maxLine: Int = 1024 * 1024, private val maxEvent: Int = 4 * 1024 * 1024) {
    private val line = ByteArrayOutputStream()
    private val data = mutableListOf<String>()
    private var size = 0
    private var skipLf = false

    fun feed(bytes: ByteArray, emit: (String) -> Unit) {
        bytes.forEach { raw ->
            val byte = raw.toInt() and 0xff
            if (skipLf) { skipLf = false; if (byte == 10) return@forEach }
            when (byte) {
                13 -> { skipLf = true; endLine()?.let(emit) }
                10 -> endLine()?.let(emit)
                else -> { if (line.size() >= maxLine) error("SSE line too large"); line.write(byte) }
            }
        }
    }

    fun finish(): String? {
        if (line.size() > 0) endLine()
        return if (data.isEmpty()) null else data.joinToString("\n").also { reset() }
    }

    private fun endLine(): String? {
        val value = line.toByteArray().toString(Charsets.UTF_8); line.reset()
        if (value.isEmpty()) return if (data.isEmpty()) null else data.joinToString("\n").also { reset() }
        if (value == "data" || value.startsWith("data:")) {
            val part = if (value == "data") "" else value.substring(5).removePrefix(" ")
            size += part.toByteArray().size; if (size > maxEvent) error("SSE event too large")
            data += part
        }
        return null
    }
    private fun reset() { data.clear(); size = 0 }
}

class StreamAccumulator(private val protocol: PlaygroundProtocol, id: String) {
    private var messageId = id
    private val parts = mutableListOf<ChatPart>()
    private val indexes = mutableMapOf<String, Int>()
    var complete = false; private set
    var finishReason = ""; private set
    var usage: JsonElement = JsonNull; private set

    private fun append(kind: String, id: String, delta: String, name: String = "") {
        if (delta.isEmpty() && kind !in setOf("tool", "file")) return
        val key = "$kind:$id"
        val index = indexes.getOrPut(key) { parts.add(ChatPart(kind, name = name)); parts.lastIndex }
        parts[index] = parts[index].copy(text = parts[index].text + delta, name = name.ifBlank { parts[index].name })
    }

    fun consume(data: String, json: Json = Json { ignoreUnknownKeys = true }) {
        if (data == "[DONE]") { complete = true; return }
        val event = runCatching { json.parseToJsonElement(data) }.getOrElse { throw IllegalArgumentException("Malformed stream event") }
        if (event["error"] !is JsonNull || event["type"].text == "error") throw IllegalStateException("Stream failed")
        when (protocol) {
            PlaygroundProtocol.ADMIN -> consumeAdmin(event)
            PlaygroundProtocol.OPENAI_CHAT -> consumeOpenAi(event)
            PlaygroundProtocol.OPENAI_RESPONSES -> consumeResponses(event)
            PlaygroundProtocol.ANTHROPIC -> consumeAnthropic(event)
            PlaygroundProtocol.GEMINI -> consumeGemini(event)
        }
    }

    private fun consumeAdmin(event: JsonElement) {
        when (event["type"].text) {
            "start" -> event["messageId"].text.takeIf(String::isNotBlank)?.let { messageId = it }
            "text-delta" -> append("text", event["id"].text, event["delta"].text)
            "reasoning-delta" -> append("reasoning", event["id"].text, event["delta"].text)
            "tool-input-start" -> append("tool", event["toolCallId"].text, "", event["toolName"].text)
            "tool-input-delta" -> append("tool", event["toolCallId"].text, event["inputTextDelta"].text)
            "tool-output-available" -> append("tool-result", event["toolCallId"].text, event["output"].toString())
            "file", "source-url", "source-document" -> parts += ChatPart(event["type"].text, data = event)
            "finish" -> { complete = true; finishReason = event["finishReason"].text }
            "abort" -> throw IllegalStateException("Stream aborted")
        }
    }

    private fun consumeOpenAi(event: JsonElement) {
        event["id"].text.takeIf(String::isNotBlank)?.let { messageId = it }
        if (event["usage"] !is JsonNull) usage = event["usage"]
        event["choices"].arr.filter { it["index"].intOrNull == 0 }.forEach { choice ->
            val delta = choice["delta"]
            append("text", "0", delta["content"].text)
            append("reasoning", "0", delta["reasoning_content"].text)
            delta["tool_calls"].arr.forEach { call -> append("tool", call["id"].text.ifBlank { call["index"].text }, call["function"]["arguments"].text, call["function"]["name"].text) }
            choice["finish_reason"].text.takeIf(String::isNotBlank)?.let { finishReason = it }
        }
    }

    private fun consumeResponses(event: JsonElement) {
        when (event["type"].text) {
            "response.created" -> event["response"]["id"].text.takeIf(String::isNotBlank)?.let { messageId = it }
            "response.output_text.delta" -> append("text", event["item_id"].text, event["delta"].text)
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> append("reasoning", event["item_id"].text, event["delta"].text)
            "response.function_call_arguments.delta" -> append("tool", event["item_id"].text, event["delta"].text, event["name"].text)
            "response.completed" -> { complete = true; finishReason = event["response"]["status"].text; usage = event["response"]["usage"] }
            "response.incomplete", "response.failed" -> throw IllegalStateException("Response failed")
        }
    }

    private fun consumeAnthropic(event: JsonElement) {
        when (event["type"].text) {
            "message_start" -> { event["message"]["id"].text.takeIf(String::isNotBlank)?.let { messageId = it }; usage = event["message"]["usage"] }
            "content_block_start" -> {
                val block = event["content_block"]; val id = event["index"].text
                when (block["type"].text) { "text" -> append("text", id, block["text"].text); "thinking" -> append("reasoning", id, block["thinking"].text); "tool_use" -> append("tool", id, "", block["name"].text) }
            }
            "content_block_delta" -> {
                val delta = event["delta"]; val id = event["index"].text
                when (delta["type"].text) { "text_delta" -> append("text", id, delta["text"].text); "thinking_delta" -> append("reasoning", id, delta["thinking"].text); "input_json_delta" -> append("tool", id, delta["partial_json"].text) }
            }
            "message_delta" -> { finishReason = event["delta"]["stop_reason"].text; usage = event["usage"] }
            "message_stop" -> complete = true
        }
    }

    private fun consumeGemini(event: JsonElement) {
        if (event["usageMetadata"] !is JsonNull) usage = event["usageMetadata"]
        event["candidates"].arr.filter { (it["index"].intOrNull ?: 0) == 0 }.forEach { candidate ->
            candidate["content"]["parts"].arr.forEachIndexed { index, part ->
                if (part["thought"].boolOrNull == true) append("reasoning", index.toString(), part["text"].text)
                else if (part["text"].text.isNotEmpty()) append("text", index.toString(), part["text"].text)
                else if (part["functionCall"] !is JsonNull) append("tool", index.toString(), part["functionCall"]["args"].toString(), part["functionCall"]["name"].text)
            }
            candidate["finishReason"].text.takeIf(String::isNotBlank)?.let { finishReason = it; complete = true }
        }
    }

    fun result(incomplete: Boolean = !complete) = StreamResult(ChatMessage(messageId, "assistant", parts.toList(), incomplete), complete, finishReason, usage)
}
