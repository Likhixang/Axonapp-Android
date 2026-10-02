package cc.khixang.axonhub.playground

import android.content.Context
import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.data.AxonRepository
import cc.khixang.axonhub.network.AxonApi
import cc.khixang.axonhub.storage.InstanceStore
import cc.khixang.axonhub.storage.SecureCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY) @SQLiteMode(SQLiteMode.Mode.LEGACY) @ConscryptMode(ConscryptMode.Mode.OFF) @Config(sdk = [35])
class PlaygroundPayloadTest {
    private fun service(): PlaygroundService {
        val context: Context = RuntimeEnvironment.getApplication()
        val secret = SecureCredentialStore(context) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        return PlaygroundService(AxonRepository(InstanceStore(context), secret, AxonApi(), CoroutineScope(SupervisorJob() + Dispatchers.Main)))
    }
    private val messages = listOf(ChatMessage(UUID.randomUUID().toString(), "user", listOf(ChatPart("text", "hello"))))
    private val settings = PlaygroundSettings("model", PlaygroundProtocol.OPENAI_CHAT, "system", 0.5, 100)

    @Test fun `protocol payloads use native fields`() {
        val service = service()
        val chat = service.payload(PlaygroundProtocol.OPENAI_CHAT, settings, messages)
        assertEquals("system", chat["messages"].arr.first()["content"].text); assertTrue(chat["stream"].boolOrNull == true)
        val responses = service.payload(PlaygroundProtocol.OPENAI_RESPONSES, settings, messages)
        assertEquals("system", responses["instructions"].text); assertEquals("input_text", responses["input"].arr.first()["content"].arr.first()["type"].text)
        val anthropic = service.payload(PlaygroundProtocol.ANTHROPIC, settings, messages)
        assertEquals(100, anthropic["max_tokens"].intOrNull); assertEquals("text", anthropic["messages"].arr.first()["content"].arr.first()["type"].text)
        val gemini = service.payload(PlaygroundProtocol.GEMINI, settings, messages)
        assertEquals("user", gemini["contents"].arr.first()["role"].text); assertEquals(100, gemini["generationConfig"]["maxOutputTokens"].intOrNull)
        val admin = service.payload(PlaygroundProtocol.ADMIN, settings, messages)
        assertEquals("text", admin["messages"].arr.first()["parts"].arr.first()["type"].text)
    }

    @Test fun `image parts map to each native protocol without remote fetches`() {
        val image = ChatPart("file", name = "pixel.png", data = buildJsonObject { put("filename", "pixel.png"); put("mediaType", "image/png"); put("base64", "AA=="); put("url", "data:image/png;base64,AA==") })
        val withImage = listOf(ChatMessage(UUID.randomUUID().toString(), "user", listOf(ChatPart("text", "look"), image)))
        val service = service()
        assertEquals("image_url", service.payload(PlaygroundProtocol.OPENAI_CHAT, settings, withImage)["messages"].arr[1]["content"].arr[1]["type"].text)
        assertEquals("input_image", service.payload(PlaygroundProtocol.OPENAI_RESPONSES, settings, withImage)["input"].arr.first()["content"].arr[1]["type"].text)
        assertEquals("base64", service.payload(PlaygroundProtocol.ANTHROPIC, settings, withImage)["messages"].arr.first()["content"].arr[1]["source"]["type"].text)
        assertEquals("image/png", service.payload(PlaygroundProtocol.GEMINI, settings, withImage)["contents"].arr.first()["parts"].arr[1]["inlineData"]["mimeType"].text)
        assertEquals("data:image/png;base64,AA==", service.payload(PlaygroundProtocol.ADMIN, settings, withImage)["messages"].arr.first()["parts"].arr[1]["url"].text)
    }
}
