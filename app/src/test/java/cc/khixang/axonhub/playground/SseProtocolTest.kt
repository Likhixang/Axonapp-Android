package cc.khixang.axonhub.playground

import org.junit.Assert.*
import org.junit.Test

class SseProtocolTest {
    @Test fun `decoder handles split unicode multiline comments and multiple events`() {
        val decoder = SseDecoder(); val events = mutableListOf<String>()
        val bytes = ":keepalive\r\ndata: first\r\ndata: 你\r\n\r\ndata: second\n\n".toByteArray()
        bytes.asList().chunked(2).forEach { decoder.feed(it.toByteArray(), events::add) }
        assertEquals(listOf("first\n你", "second"), events)
    }

    @Test fun `openai accumulates content reasoning tools usage and done`() {
        val acc = StreamAccumulator(PlaygroundProtocol.OPENAI_CHAT, "local")
        acc.consume("""{"id":"chat-1","choices":[{"index":0,"delta":{"content":"Hi","reasoning_content":"think","tool_calls":[{"id":"t1","function":{"name":"lookup","arguments":"{\"q\":"}}]}}]}""")
        acc.consume("""{"choices":[{"index":0,"delta":{"tool_calls":[{"id":"t1","function":{"arguments":"1}"}}]},"finish_reason":"stop"}],"usage":{"total_tokens":3}}""")
        acc.consume("[DONE]")
        val result = acc.result(); assertTrue(result.complete); assertEquals("Hi", result.message.parts.first { it.type == "text" }.text); assertEquals("think", result.message.parts.first { it.type == "reasoning" }.text); assertEquals("{\"q\":1}", result.message.parts.first { it.type == "tool" }.text)
    }

    @Test fun `responses anthropic gemini and admin terminal events are understood`() {
        StreamAccumulator(PlaygroundProtocol.OPENAI_RESPONSES, "x").apply { consume("""{"type":"response.output_text.delta","item_id":"i","delta":"ok"}"""); consume("""{"type":"response.completed","response":{"status":"completed","usage":{}}}"""); assertTrue(result().complete) }
        StreamAccumulator(PlaygroundProtocol.ANTHROPIC, "x").apply { consume("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"why"}}"""); consume("""{"type":"message_stop"}"""); assertEquals("why", result().message.parts.single().text) }
        StreamAccumulator(PlaygroundProtocol.GEMINI, "x").apply { consume("""{"candidates":[{"index":0,"content":{"parts":[{"text":"hello"}]},"finishReason":"STOP"}]}"""); assertTrue(result().complete) }
        StreamAccumulator(PlaygroundProtocol.ADMIN, "x").apply { consume("""{"type":"tool-input-start","toolCallId":"1","toolName":"search"}"""); consume("""{"type":"tool-input-delta","toolCallId":"1","inputTextDelta":"{}"}"""); consume("""{"type":"finish","finishReason":"stop"}"""); assertEquals("{}", result().message.parts.single().text) }
    }
}
