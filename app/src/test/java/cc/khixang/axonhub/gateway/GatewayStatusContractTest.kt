package cc.khixang.axonhub.gateway

import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.data.AxonRepository
import cc.khixang.axonhub.network.AxonApi
import cc.khixang.axonhub.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY) @SQLiteMode(SQLiteMode.Mode.LEGACY) @ConscryptMode(ConscryptMode.Mode.OFF) @Config(sdk = [35])
class GatewayStatusContractTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var service: GatewayService

    @Before fun setup() = runBlocking {
        server = MockWebServer().also { it.start() }
        val context = RuntimeEnvironment.getApplication()
        val instance = AxonInstance(id = "gateway-contract", name = "test", address = server.url("/base").toString().trimEnd('/'), allowHttp = true, adminEmail = "admin@example.test")
        val store = InstanceStore(context)
        store.save(StoredInstances(listOf(instance), instance.id))
        val credentials = SecureCredentialStore(context) { SecretKeySpec(ByteArray(32), "AES") }
        credentials.put(instance.id, "test-admin-token")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        reply("""{"dashboardOverview":{}}""")
        val repository = AxonRepository(store, credentials, AxonApi(), scope)
        withTimeout(10_000) { repository.snapshot.first { it is LoadState.Ready } }
        server.takeRequest() // Repository's one initial snapshot request, not a status operation.
        repository.selectProject(null)
        service = GatewayService(repository)
    }

    @After fun teardown() { scope.cancel(); server.shutdown() }
    private fun reply(data: String) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"data\":$data}")) }
    private fun channelMutation(id: String, status: String) = reply("""{"updateChannelStatus":{"id":"$id","status":"$status"}}""")
    private fun detail(root: String, id: String, status: String) = reply("""{"$root":{"edges":[{"node":{"id":"$id","status":"$status","settings":{}}}]}}""")
    private fun request(): JsonObject {
        val request = server.takeRequest()
        assertEquals("/base/admin/graphql", request.path)
        assertEquals("Bearer test-admin-token", request.getHeader("Authorization"))
        return Json.parseToJsonElement(request.body.readUtf8()).jsonObject
    }

    @Test fun `channel batch uses real single target mutation plus readback and preserves partial failure`() = runBlocking {
        channelMutation("a", "enabled"); detail("channels", "a", "enabled")
        server.enqueue(MockResponse().setBody("""{"errors":[{"message":"apiKey=upstream-secret","extensions":{"code":"BAD_USER_INPUT"}}]}"""))
        channelMutation("c", "enabled"); detail("channels", "c", "enabled")
        val result = service.setChannelsEnabled(listOf("a", "b", "c"), true)
        assertEquals(2, result.succeeded); assertEquals(1, result.failed)
        assertFalse(result.items[1].error.orEmpty().contains("upstream-secret"))
        assertEquals(6, server.requestCount) // Initial snapshot + 3 writes + 2 successful readbacks.
        listOf("a", "b", "c").forEach { id ->
            val mutation = request()
            assertEquals(GatewayService.CHANNEL_STATUS, mutation["query"].text)
            assertEquals(id, mutation["variables"]["id"].text)
            assertEquals("enabled", mutation["variables"]["status"].text)
            if (id != "b") {
                val readback = request()
                assertEquals(GatewayService.CHANNEL_DETAIL, readback["query"].text)
                assertEquals(id, readback["variables"]["id"].text)
            }
        }
    }

    @Test fun `model boolean acknowledgement does not count as success when readback status differs`() = runBlocking {
        reply("""{"updateModelStatus":true}"""); detail("models", "a", "enabled")
        reply("""{"updateModelStatus":true}"""); detail("models", "b", "disabled")
        val result = service.setModelsEnabled(listOf("a", "b"), false)
        assertEquals(1, result.succeeded); assertEquals(1, result.failed)
        assertFalse(result.items[0].verified); assertTrue(result.items[1].verified)
        assertEquals(5, server.requestCount) // Initial snapshot + 2 writes + 2 readbacks.
        listOf("a", "b").forEach { id ->
            val mutation = request()
            assertEquals(GatewayService.MODEL_STATUS, mutation["query"].text)
            assertEquals(id, mutation["variables"]["id"].text)
            assertEquals("disabled", mutation["variables"]["status"].text)
            val readback = request()
            assertEquals(GatewayService.MODEL_DETAIL, readback["query"].text)
            assertEquals(id, readback["variables"]["id"].text)
        }
    }
}
