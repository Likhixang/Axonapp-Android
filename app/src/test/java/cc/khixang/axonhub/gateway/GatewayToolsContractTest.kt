package cc.khixang.axonhub.gateway

import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.data.AxonRepository
import cc.khixang.axonhub.management.AdminCatalog
import cc.khixang.axonhub.network.*
import cc.khixang.axonhub.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

/** Only local MockWebServer responses: never a real production mutation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY) @SQLiteMode(SQLiteMode.Mode.LEGACY) @ConscryptMode(ConscryptMode.Mode.OFF) @Config(sdk = [35])
class GatewayToolsContractTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var repository: AxonRepository
    private lateinit var service: GatewayToolsService
    private lateinit var catalog: AdminCatalog

    @Before fun setup(): Unit = runBlocking<Unit> {
        server = MockWebServer().also { it.start() }
        val context = RuntimeEnvironment.getApplication()
        val instance = AxonInstance(id = "gateway-tools-contract", name = "mock", address = server.url("/base").toString().trimEnd('/'), allowHttp = true, adminEmail = "admin@example.test")
        val store = InstanceStore(context)
        store.save(StoredInstances(listOf(instance), instance.id))
        val credentials = SecureCredentialStore(context) { SecretKeySpec(ByteArray(32), "AES") }
        credentials.put(instance.id, "mock-admin-token")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        reply("""{"dashboardOverview":{}}""")
        repository = AxonRepository(store, credentials, AxonApi(), scope)
        withTimeout(10_000) { repository.snapshot.first { it is LoadState.Ready } }
        server.takeRequest()
        catalog = AdminCatalog(context)
        service = GatewayToolsService(repository, catalog)
    }
    @After fun teardown() { scope.cancel(); server.shutdown() }
    private fun reply(data: String) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"data\":$data}")) }
    private fun channel(body: String) = reply("""{"channels":{"edges":[{"node":$body}]}}""")
    private fun secrets(disabled: String = "[]", credentials: String = """{"apiKeys":["key-a","key-b"]}""") = channel("""{"id":"channel-a","updatedAt":"v1","credentials":$credentials,"disabledAPIKeys":$disabled,"settings":{}}""")
    private fun request(): JsonObject {
        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("No request")
        assertEquals("/base/admin/graphql", request.path)
        assertEquals("Bearer mock-admin-token", request.getHeader("Authorization"))
        return Json.parseToJsonElement(request.body.readUtf8()).jsonObject
    }
    private suspend fun fails(expected: Throwable, block: suspend () -> Unit) {
        try { block(); fail("Expected failure") } catch (error: Exception) { assertEquals(expected, error) }
    }
    private suspend fun invalid(block: suspend () -> Unit) {
        try { block(); fail("Expected invalid input") } catch (_: IllegalArgumentException) { }
    }
    @Test fun `secret reads and key actions require explicit authorization before networking`(): Unit = runBlocking<Unit> {
        invalid { service.channelKeys("channel-a", false) }
        invalid { service.keyAction("channel-a", ChannelKeyAction.ENABLE_ALL, false) }
        invalid { service.channelTemplates(false) }
        assertEquals(1, server.requestCount)
    }
    @Test fun `missing credentials are forbidden not empty authorized state`(): Unit = runBlocking<Unit> {
        channel("""{"id":"channel-a","credentials":null,"disabledAPIKeys":[]}""")
        fails(AxonException.Forbidden) { service.channelKeys("channel-a", true) }
        assertEquals(GatewayService.CHANNEL_SECRETS, request()["query"].text)
    }
    @Test fun `disable key acknowledgement requires separate exact target readback`(): Unit = runBlocking<Unit> {
        secrets(); reply("""{"disableChannelAPIKey":true}""")
        secrets("""[{"key":"key-a","errorCode":401}]""")
        val saved = service.keyAction("channel-a", ChannelKeyAction.DISABLE, true, key = "key-a")
        assertEquals("key-a", saved["disabledAPIKeys"].arr.single()["key"].text)
        assertEquals(GatewayService.CHANNEL_SECRETS, request()["query"].text)
        val mutation = request()
        assertEquals(GatewayToolDocuments.ChannelDisableKey, mutation["query"].text)
        assertEquals("channel-a", mutation["variables"]["id"].text)
        assertEquals("key-a", mutation["variables"]["key"].text)
        assertEquals(GatewayService.CHANNEL_SECRETS, request()["query"].text)
    }
    @Test fun `boolean acknowledgement alone never verifies a key write`(): Unit = runBlocking<Unit> {
        secrets(); reply("""{"disableChannelAPIKey":true}"""); secrets()
        fails(AxonException.VerificationFailed) { service.keyAction("channel-a", ChannelKeyAction.DISABLE, true, key = "key-a") }
        assertEquals(4, server.requestCount)
    }
    @Test fun `deleting disabled keys verifies removal from single and multi key credentials`(): Unit = runBlocking<Unit> {
        secrets("""[{"key":"key-a"}]""")
        reply("""{"deleteDisabledChannelAPIKeys":{"success":true}}""")
        secrets(credentials = """{"apiKey":"key-a","apiKeys":["key-b"]}""")
        fails(AxonException.VerificationFailed) { service.keyAction("channel-a", ChannelKeyAction.DELETE_DISABLED, true, keys = listOf("key-a")) }
        request()
        assertEquals(GatewayToolDocuments.ChannelDeleteDisabledKeys, request()["query"].text)
        request()
    }
    @Test fun `oauth deletion masked keys and empty selections are rejected without mutation`(): Unit = runBlocking<Unit> {
        invalid { service.keyAction("channel-a", ChannelKeyAction.DELETE_DISABLED, true, keys = listOf("__oauth__")) }
        invalid { service.keyAction("channel-a", ChannelKeyAction.DISABLE, true, key = "••••") }
        invalid { service.keyAction("channel-a", ChannelKeyAction.ENABLE_SELECTED, true) }
        assertEquals(1, server.requestCount)
    }
    @Test fun `stale disabled selection is rejected before mutation`(): Unit = runBlocking<Unit> {
        secrets()
        fails(AxonException.TargetChanged) { service.keyAction("channel-a", ChannelKeyAction.DELETE_DISABLED, true, keys = listOf("key-a")) }
        assertEquals(2, server.requestCount)
    }
    @Test fun `test all keys checks counts and drops provider prose and key prefixes`(): Unit = runBlocking<Unit> {
        secrets()
        reply("""{"testChannelAPIKeys":{"channelID":"channel-a","total":2,"successCount":1,"failedCount":1,"keyPrefix":"upstream-secret","error":"upstream-secret","results":[{"success":true,"latency":0.12,"disabled":false,"keyPrefix":"key-a"},{"success":false,"latency":0.2,"disabled":true,"error":"upstream-secret"}]}}""")
        val result = service.keyAction("channel-a", ChannelKeyAction.TEST_ALL, true, model = "model-a")
        assertEquals(2, result["total"].intOrNull)
        assertFalse(result.toString().contains("upstream-secret")); assertFalse(result.toString().contains("key-a"))
        request()
        val mutation = request()
        assertEquals(GatewayToolDocuments.ChannelTestKeys, mutation["query"].text)
        assertEquals("model-a", mutation["variables"]["model"].text)
    }
    @Test fun `test all keys refuses inconsistent totals`(): Unit = runBlocking<Unit> {
        secrets()
        reply("""{"testChannelAPIKeys":{"channelID":"channel-a","total":2,"successCount":2,"failedCount":0,"results":[{"success":true,"latency":0,"disabled":false}]}}""")
        fails(AxonException.InvalidResponse) { service.keyAction("channel-a", ChannelKeyAction.TEST_ALL, true) }
    }
    @Test fun `route preview uses association input and stays a query`(): Unit = runBlocking<Unit> {
        reply("""{"queryModelChannelConnections":[{"channel":{"id":"channel-a","name":"A","status":"enabled"},"priority":1,"models":[{"requestModel":"request-a","actualModel":"actual-a","source":"manual"}]}]}""")
        val associations = JsonArray(emptyList())
        assertEquals(1, service.routePreview(associations).size)
        val query = request()
        assertEquals(GatewayToolDocuments.ModelRouteConnections, query["query"].text)
        assertEquals(associations, query["variables"]["associations"])
    }
    @Test fun `unassociated model lookup uses actual iOS API`(): Unit = runBlocking<Unit> {
        reply("""{"queryUnassociatedChannels":[{"channel":{"id":"channel-a","name":"A","status":"enabled"},"models":["model-a"]}]}""")
        assertEquals(1, service.unassociatedChannels().size)
        assertEquals(GatewayToolDocuments.ModelUnassociatedChannels, request()["query"].text)
    }
    @Test fun `clear error mutation is followed by target detail not trusted by response alone`(): Unit = runBlocking<Unit> {
        reply("""{"updateChannel":{"id":"channel-a","errorMessage":null}}""")
        channel("""{"id":"channel-a","errorMessage":"still present"}""")
        fails(AxonException.VerificationFailed) { service.clearError("channel-a") }
        assertEquals(GatewayToolDocuments.ChannelClearError, request()["query"].text)
        assertEquals(GatewayService.CHANNEL_DETAIL, request()["query"].text)
    }
    @Test fun `quota reset reads back exact diagnostics with asynchronous outcome`(): Unit = runBlocking<Unit> {
        reply("""{"resetChannelQuotaNow":true}""")
        channel("""{"id":"channel-a","liveLimiterStats":{"inFlight":2},"providerQuotaStatus":{"status":"pending","quotaData":{"apiKey":"upstream-secret"}},"allModelEntries":[]}""")
        val result = service.resetQuota("channel-a")
        assertEquals("pending", result["providerQuotaStatus"]["status"].text)
        assertFalse(result.toString().contains("upstream-secret"))
        assertEquals(GatewayToolDocuments.ChannelQuotaReset, request()["query"].text)
        val readback = request(); assertEquals(GatewayToolDocuments.ChannelDiagnostics, readback["query"].text)
        assertEquals("channel-a", readback["variables"]["id"].text)
    }
    @Test fun `history refuses repeated cursor and inconsistent counts`(): Unit = runBlocking<Unit> {
        reply("""{"requests":{"edges":[],"pageInfo":{"hasNextPage":true,"endCursor":"same"},"totalCount":3}}""")
        fails(AxonException.InvalidResponse) { service.testHistory("channel-a", "same") }
        val query = request()
        assertEquals(GatewayToolDocuments.ChannelTestHistory, query["query"].text)
        assertEquals("same", query["variables"]["after"].text)
    }
    @Test fun `price save uses modelId input and verifies nested price readback`(): Unit = runBlocking<Unit> {
        val input = Json.parseToJsonElement("""[{"modelId":"model-a","price":{"items":[]}}]""").jsonArray
        channel("""{"id":"channel-a","channelModelPrices":[]}""")
        reply("""{"saveChannelModelPrices":[{"id":"price-a","modelID":"model-a"}]}""")
        channel("""{"id":"channel-a","channelModelPrices":[{"modelID":"model-a","price":{"items":[]}}]}""")
        assertEquals(input, service.savePrices("channel-a", input, JsonArray(emptyList())))
        request(); val mutation = request()
        assertEquals(GatewayToolDocuments.ChannelSavePrices, mutation["query"].text)
        assertEquals(input, mutation["variables"]["input"])
        assertEquals(GatewayToolDocuments.ChannelPrices, request()["query"].text)
    }
    @Test fun `price save refuses mismatched separate readback`(): Unit = runBlocking<Unit> {
        val input = Json.parseToJsonElement("""[{"modelId":"model-a","price":{"items":[]}}]""").jsonArray
        channel("""{"id":"channel-a","channelModelPrices":[]}""")
        reply("""{"saveChannelModelPrices":[{"id":"price-a","modelID":"model-a"}]}""")
        channel("""{"id":"channel-a","channelModelPrices":[]}""")
        fails(AxonException.VerificationFailed) { service.savePrices("channel-a", input, JsonArray(emptyList())) }
    }
    @Test fun `catalog refresh verifies filtered true then browses different unfiltered data`(): Unit = runBlocking<Unit> {
        val filtered = """{"data":{"providers":{"kept":{}}},"source":"upstream","fetchedAt":"v1","filtered":true}"""
        val unfiltered = """{"data":{"providers":{"kept":{},"excluded":{}}},"source":"upstream","fetchedAt":"v1","filtered":false}"""
        reply("""{"refreshProvidersCatalog":$filtered}""")
        reply("""{"providersCatalog":$filtered}"""); reply("""{"providersCatalog":$unfiltered}""")
        assertEquals(Json.parseToJsonElement(unfiltered), service.providersCatalog(true))
        assertEquals(catalog.document(catalog.operation("refreshProvidersCatalog")), request()["query"].text)
        assertEquals(GatewayToolDocuments.ModelProvidersCatalogFiltered, request()["query"].text)
        assertEquals(GatewayToolDocuments.ModelProvidersCatalog, request()["query"].text)
    }
    @Test fun `archive model verifies models root and archived status`(): Unit = runBlocking<Unit> {
        reply("""{"models":{"edges":[{"node":{"id":"model-a","status":"enabled"}}]}}""")
        reply("""{"bulkArchiveModels":true}""")
        reply("""{"models":{"edges":[{"node":{"id":"model-a","status":"archived"}}]}}""")
        assertEquals(1, service.batchLifecycle(listOf("model-a"), false, GatewayLifecycleAction.ARCHIVE).succeeded)
        assertEquals(GatewayService.MODEL_DETAIL, request()["query"].text)
        assertEquals(GatewayToolDocuments.ModelBulkArchive, request()["query"].text)
        assertEquals(GatewayService.MODEL_DETAIL, request()["query"].text)
    }
    @Test fun `ordering compares requested weight not only server response`(): Unit = runBlocking<Unit> {
        val input = Json.parseToJsonElement("""{"channels":[{"id":"channel-a","orderingWeight":9}]}""").jsonObject
        channel("""{"id":"channel-a","orderingWeight":1}""")
        reply("""{"bulkUpdateChannelOrdering":{"success":true,"updated":1,"channels":[{"id":"channel-a","orderingWeight":8}]}}""")
        channel("""{"id":"channel-a","orderingWeight":8}""")
        fails(AxonException.VerificationFailed) { service.bulkChannels(ChannelImportMode.ORDERING, input, false) }
        request(); assertEquals(GatewayToolDocuments.ChannelBulkOrdering, request()["query"].text); request()
    }
    @Test fun `template pagination refuses cursor cycles`(): Unit = runBlocking<Unit> {
        reply("""{"channelOverrideTemplates":{"edges":[],"pageInfo":{"hasNextPage":true,"endCursor":"cursor-a"}}}""")
        reply("""{"channelOverrideTemplates":{"edges":[],"pageInfo":{"hasNextPage":true,"endCursor":"cursor-a"}}}""")
        fails(AxonException.InvalidResponse) { service.channelTemplates(true) }
        assertEquals(GatewayToolDocuments.ChannelTemplates, request()["query"].text)
        assertEquals("cursor-a", request()["variables"]["after"].text)
    }
    @Test fun `template apply checks exact settings after mutation`(): Unit = runBlocking<Unit> {
        val template = Json.parseToJsonElement("""{"id":"template-a","name":"A","headerOverrideOperations":[{"op":"set","path":"Authorization","value":"new-secret"}],"bodyOverrideOperations":[]}""")
        reply("""{"channelOverrideTemplates":{"edges":[{"node":$template}],"pageInfo":{"hasNextPage":false}}}""")
        secrets()
        reply("""{"applyChannelOverrideTemplate":{"success":true,"updated":1,"channels":[{"id":"channel-a"}]}}""")
        secrets() // Still has no applied header: mutation response alone is insufficient.
        fails(AxonException.VerificationFailed) { service.applyTemplate(template, listOf("channel-a"), true, true) }
        request(); request()
        val mutation = request(); assertEquals(GatewayToolDocuments.ChannelTemplateApply, mutation["query"].text)
        assertEquals("REPLACE", mutation["variables"]["input"]["mode"].text)
        request()
    }
    @Test fun `target change between key baseline and mutation prevents old session write`(): Unit = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val query = Json.parseToJsonElement(request.body.clone().readUtf8())["query"].text
                return if (query.contains("ChannelSecrets")) MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"data":{"channels":{"edges":[{"node":{"id":"channel-a","credentials":{"apiKeys":["key-a"]},"disabledAPIKeys":[]}}]}}}""").setBodyDelay(2, TimeUnit.SECONDS)
                else MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"dashboardOverview":{}}}""")
            }
        }
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { service.keyAction("channel-a", ChannelKeyAction.DISABLE, true, key = "key-a") }
        }
        request() // Baseline is in flight; its delayed response cannot authorize an old-session write.
        repository.selectProject("project-other")
        val outcome = withTimeout(5_000) { runCatching { operation.await() } }
        val failure = outcome.exceptionOrNull() ?: outcome.getOrNull()?.exceptionOrNull()
        assertTrue(failure is CancellationException || failure == AxonException.TargetChanged)
        val newSnapshot = request()
        assertFalse(newSnapshot["query"].text.contains("disableChannelAPIKey"))
        assertEquals(3, server.requestCount)
    }
    @Test fun `model bulk creation rejects duplicate ids even with matching count`(): Unit = runBlocking<Unit> {
        val draft = catalog.schema.defaultValue("CreateModelInput!").obj.toMutableMap().apply {
            put("name", JsonPrimitive("Model A")); put("modelID", JsonPrimitive("model-a"))
        }.let(::JsonObject)
        reply("""{"bulkCreateModels":[{"id":"same"},{"id":"same"}]}""")
        fails(AxonException.VerificationFailed) { service.createModels(JsonArray(listOf(draft, draft))) }
        val mutation = request()
        assertEquals(GatewayToolDocuments.ModelBulkCreate, mutation["query"].text)
        assertEquals(2, mutation["variables"]["inputs"].arr.size)
        assertEquals(2, server.requestCount)
    }
    @Test fun `template save uses optimistic baseline and separate paginated readback`(): Unit = runBlocking<Unit> {
        val baseline = Json.parseToJsonElement("""{"id":"template-a","name":"Before","headerOverrideOperations":[],"bodyOverrideOperations":[]}""")
        val input = Json.parseToJsonElement("""{"name":"After","headerOverrideOperations":[],"bodyOverrideOperations":[]}""").jsonObject
        reply("""{"channelOverrideTemplates":{"edges":[{"node":$baseline}],"pageInfo":{"hasNextPage":false}}}""")
        reply("""{"updateChannelOverrideTemplate":{"id":"template-a"}}""")
        reply("""{"channelOverrideTemplates":{"edges":[{"node":{"id":"template-a","name":"After","headerOverrideOperations":[],"bodyOverrideOperations":[]}}],"pageInfo":{"hasNextPage":false}}}""")
        assertEquals("template-a", service.saveTemplate("template-a", input, baseline, true))
        request(); assertEquals(GatewayToolDocuments.ChannelTemplateEdit, request()["query"].text)
        assertEquals(GatewayToolDocuments.ChannelTemplates, request()["query"].text)
    }
    @Test fun `template save refuses stale configuration before mutation`(): Unit = runBlocking<Unit> {
        val baseline = Json.parseToJsonElement("""{"id":"template-a","name":"Before"}""")
        reply("""{"channelOverrideTemplates":{"edges":[{"node":{"id":"template-a","name":"Changed elsewhere"}}],"pageInfo":{"hasNextPage":false}}}""")
        fails(AxonException.TargetChanged) { service.saveTemplate("template-a", buildJsonObject { put("name", "After") }, baseline, true) }
        assertEquals(2, server.requestCount)
    }
    @Test fun `sensitive override placeholders cannot be submitted as real secrets`(): Unit = runBlocking<Unit> {
        invalid {
            service.saveTemplate(null, Json.parseToJsonElement("""{"name":"A","headerOverrideOperations":[{"op":"set","path":"Authorization","value":"••••"}]}""").jsonObject, JsonNull, true)
        }
        assertEquals(1, server.requestCount)
    }
    @Test fun `channel deletion needs before authority and exact absent readback`(): Unit = runBlocking<Unit> {
        channel("""{"id":"channel-a","status":"archived"}""")
        reply("""{"bulkDeleteChannels":true}""")
        reply("""{"channels":{"edges":[]}}""")
        assertEquals(1, service.batchLifecycle(listOf("channel-a"), true, GatewayLifecycleAction.DELETE).succeeded)
        request(); assertEquals(GatewayToolDocuments.ChannelBulkDelete, request()["query"].text)
        assertEquals(GatewayService.CHANNEL_DETAIL, request()["query"].text)
    }
    @Test fun `recover channel verifies enabled status`(): Unit = runBlocking<Unit> {
        channel("""{"id":"channel-a","status":"archived"}""")
        reply("""{"bulkRecoverChannels":true}""")
        channel("""{"id":"channel-a","status":"disabled"}""")
        assertEquals(1, service.batchLifecycle(listOf("channel-a"), true, GatewayLifecycleAction.RECOVER).failed)
        request(); assertEquals(GatewayToolDocuments.ChannelBulkRecover, request()["query"].text); request()
    }
    private fun priceRows(rows: JsonArray) = channel(buildJsonObject {
        put("id", "channel-a")
        put("channelModelPrices", JsonArray(rows.map { row -> buildJsonObject {
            put("modelID", row["modelId"]); put("price", row["price"])
        } }))
    }.toString())
    private fun priceInput(model: String = "model-a") = Json.parseToJsonElement(
        """[{"modelId":"$model","price":{"items":[]}}]"""
    ).jsonArray
    private fun importInput() = Json.parseToJsonElement("""{"channels":[
        {"type":"openai","name":"A","baseURL":"https://provider.test","apiKey":"key-a","supportedModels":[],"defaultTestModel":""},
        {"type":"openai","name":"B","baseURL":"https://provider.test","apiKey":"key-b","supportedModels":[],"defaultTestModel":""}
    ]}""").jsonObject
    private fun importedChannel(id: String = "channel-a", name: String = "A", key: String = "key-a") {
        channel("""{"id":"$id","type":"openai","name":"$name","baseURL":"https://provider.test","supportedModels":[],"defaultTestModel":""}""")
        channel("""{"id":"$id","credentials":{"apiKeys":["$key"]},"disabledAPIKeys":[]}""")
    }
    @Test fun `nullable pricing branches variants schedules and unbounded tiers round trip as omissions`(): Unit = runBlocking<Unit> {
        val raw = Json.parseToJsonElement("""[{"modelId":"model-a","price":{"items":[
            {"itemCode":"prompt_tokens","pricing":{"mode":"usage_tiered","flatFee":null,"usagePerUnit":null,"usageTiered":{"tiers":[{"upTo":100,"pricePerUnit":"1"},{"upTo":null,"pricePerUnit":"2"}]}},"promptWriteCacheVariants":null},
            {"itemCode":"completion_tokens","pricing":{"mode":"usage_per_unit","flatFee":null,"usagePerUnit":"3","usageTiered":null},"promptWriteCacheVariants":null},
            {"itemCode":"prompt_write_cached_tokens","pricing":{"mode":"flat_fee","flatFee":"4","usagePerUnit":null,"usageTiered":null},"promptWriteCacheVariants":[{"variantCode":"five_min","pricing":{"mode":"usage_per_unit","flatFee":null,"usagePerUnit":"5","usageTiered":null}}]}
        ],"schedule":null}}]""").jsonArray
        priceRows(raw)
        val editable = service.channelPrices("channel-a")
        assertFalse(editable.toString().contains(":null"))
        assertFalse(editable.single()["price"]["items"].arr[0]["pricing"]["usageTiered"]["tiers"].arr.last().obj.containsKey("upTo"))
        priceRows(raw); reply("""{"saveChannelModelPrices":[{"id":"price-a","modelID":"model-a"}]}"""); priceRows(raw)
        assertEquals(editable, service.savePrices("channel-a", editable, editable))
        request(); request()
        assertEquals(editable, request()["variables"]["input"])
        request()
    }
    @Test fun `nested nullable schedule conditions are projected without losing values`(): Unit = runBlocking<Unit> {
        val raw = Json.parseToJsonElement("""[{"modelId":"model-a","price":{"items":[],"schedule":{"timezone":"UTC","overrides":[{"name":"Night","priority":1,"when":{"dailyTime":{"start":"00:00","end":"06:00"},"weekdays":null,"dateRange":null},"items":[{"itemCode":"prompt_tokens","pricing":{"mode":"flat_fee","flatFee":"1","usagePerUnit":null,"usageTiered":null},"promptWriteCacheVariants":null}]}]}}}]""").jsonArray
        priceRows(raw)
        val editable = service.channelPrices("channel-a")
        assertFalse(editable.toString().contains(":null"))
        assertEquals("06:00", editable.single()["price"]["schedule"]["overrides"].arr.single()["when"]["dailyTime"]["end"].text)
        catalog.schema.validate(buildJsonObject { put("input", editable) }, listOf(cc.khixang.axonhub.management.AdminField("input", "[SaveChannelModelPriceInput!]!")), true)
    }
    @Test fun `price replacement refuses latest baseline additions removals or changed prices before mutation`(): Unit = runBlocking<Unit> {
        val baseline = priceInput()
        val added = JsonArray(baseline + priceInput("other"))
        val changed = Json.parseToJsonElement("""[{"modelId":"model-a","price":{"items":[{"itemCode":"prompt_tokens","pricing":{"mode":"flat_fee","flatFee":"1"}}]}}]""").jsonArray
        listOf(added, JsonArray(emptyList()), changed).forEach { latest ->
            priceRows(latest)
            fails(AxonException.TargetChanged) { service.savePrices("channel-a", baseline, baseline) }
            assertEquals(GatewayToolDocuments.ChannelPrices, request()["query"].text)
        }
        assertEquals(4, server.requestCount)
    }
    @Test fun `price removals require exact confirmation even when clearing all prices`(): Unit = runBlocking<Unit> {
        val baseline = priceInput()
        invalid { service.savePrices("channel-a", JsonArray(emptyList()), baseline) }
        invalid { service.savePrices("channel-a", JsonArray(emptyList()), baseline, setOf("wrong")) }
        assertEquals(1, server.requestCount)
        priceRows(baseline); reply("""{"saveChannelModelPrices":[]}"""); priceRows(JsonArray(emptyList()))
        assertTrue(service.savePrices("channel-a", JsonArray(emptyList()), baseline, setOf("model-a")).isEmpty())
        request(); assertTrue(request()["variables"]["input"].arr.isEmpty()); request()
    }
    @Test fun `price readback refuses extra model ids left behind by replacement`(): Unit = runBlocking<Unit> {
        val input = priceInput()
        priceRows(JsonArray(emptyList())); reply("""{"saveChannelModelPrices":[{"modelID":"model-a"}]}""")
        priceRows(JsonArray(input + priceInput("left-behind")))
        fails(AxonException.VerificationFailed) { service.savePrices("channel-a", input, JsonArray(emptyList())) }
    }
    @Test fun `unbounded tier cannot verify as a finite tier merely because upTo was omitted`(): Unit = runBlocking<Unit> {
        val input = Json.parseToJsonElement("""[{"modelId":"model-a","price":{"items":[{"itemCode":"prompt_tokens","pricing":{"mode":"usage_tiered","usageTiered":{"tiers":[{"pricePerUnit":"1"}]}}}]}}]""").jsonArray
        val finite = Json.parseToJsonElement(input.toString().replace("{\"pricePerUnit\":", "{\"upTo\":100,\"pricePerUnit\":" )).jsonArray
        priceRows(JsonArray(emptyList())); reply("""{"saveChannelModelPrices":[{"modelID":"model-a"}]}"""); priceRows(finite)
        fails(AxonException.VerificationFailed) { service.savePrices("channel-a", input, JsonArray(emptyList())) }
    }
    @Test fun `price baseline accepts reordered models and numeric float normalization`(): Unit = runBlocking<Unit> {
        val one = Json.parseToJsonElement("""[{"modelId":"model-a","price":{"items":[{"itemCode":"prompt_tokens","pricing":{"mode":"flat_fee","flatFee":1}}]}}]""").jsonArray
        val two = priceInput("model-b")
        val baseline = JsonArray(one + two)
        val reordered = Json.parseToJsonElement(JsonArray(two + one).toString().replace("\"flatFee\":1", "\"flatFee\":1.0")).jsonArray
        priceRows(reordered); reply("""{"saveChannelModelPrices":[{"modelID":"model-a"},{"modelID":"model-b"}]}"""); priceRows(reordered)
        assertEquals(reordered, service.savePrices("channel-a", baseline, baseline))
    }
    @Test fun `price equality matches Go decimal and empty optional list semantics without normalizing ids`(): Unit = runBlocking<Unit> {
        val draft = Json.parseToJsonElement("""[{"modelId":"001","price":{"items":[{"itemCode":"prompt_tokens","pricing":{"mode":"flat_fee","flatFee":"1.00"},"promptWriteCacheVariants":[]}]}}]""").jsonArray
        val actual = Json.parseToJsonElement("""[{"modelId":"001","price":{"items":[{"itemCode":"prompt_tokens","pricing":{"mode":"flat_fee","flatFee":"1"}}]}}]""").jsonArray
        assertTrue(gatewayPriceSetsMatch(actual, draft))
        assertFalse(gatewayPriceSetsMatch(Json.parseToJsonElement(actual.toString().replace("001", "1")).jsonArray, draft))
        priceRows(actual); reply("""{"saveChannelModelPrices":[{"modelID":"001"}]}"""); priceRows(actual)
        assertEquals(actual, service.savePrices("channel-a", draft, draft))
    }
    @Test fun `project change during price baseline prevents replacement on old session`(): Unit = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val query = Json.parseToJsonElement(request.body.clone().readUtf8())["query"].text
                return if (query.contains("query ChannelPrices")) MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"data":{"channels":{"edges":[{"node":{"id":"channel-a","channelModelPrices":[]}}]}}}""").setBodyDelay(2, TimeUnit.SECONDS)
                else MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"dashboardOverview":{}}}""")
            }
        }
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { service.savePrices("channel-a", priceInput(), JsonArray(emptyList())) }
        }
        assertEquals(GatewayToolDocuments.ChannelPrices, request()["query"].text)
        repository.selectProject("project-other")
        val outcome = withTimeout(5_000) { runCatching { operation.await() } }
        val failure = outcome.exceptionOrNull() ?: outcome.getOrNull()?.exceptionOrNull()
        assertTrue(failure is CancellationException || failure == AxonException.TargetChanged)
        assertFalse(request()["query"].text.contains("saveChannelModelPrices"))
        assertEquals(3, server.requestCount)
    }
    @Test fun `catalog refuses stale filtered readback without browsing an unverified refresh`(): Unit = runBlocking<Unit> {
        reply("""{"refreshProvidersCatalog":{"data":{"providers":{}},"source":"upstream","fetchedAt":"v2","filtered":true}}""")
        reply("""{"providersCatalog":{"data":{"providers":{}},"source":"upstream","fetchedAt":"v1","filtered":true}}""")
        fails(AxonException.VerificationFailed) { service.providersCatalog(true) }
        request(); assertEquals(GatewayToolDocuments.ModelProvidersCatalogFiltered, request()["query"].text)
        assertEquals(3, server.requestCount)
    }
    @Test fun `partial import returns verified persisted successes rather than blanket failure`(): Unit = runBlocking<Unit> {
        reply("""{"bulkImportChannels":{"success":false,"created":1,"failed":1,"channels":[{"id":"channel-a"}],"errors":["provider-secret"]}}""")
        importedChannel()
        val result = service.bulkChannels(ChannelImportMode.IMPORT, importInput(), true)
        assertEquals(false, result["success"].boolOrNull); assertEquals(true, result["partial"].boolOrNull)
        assertEquals(1, result["verified"].intOrNull); assertEquals(1, result["created"].intOrNull); assertEquals(1, result["failed"].intOrNull)
        assertEquals(listOf("channel-a"), result["verifiedIds"].arr.map { it.text })
        assertFalse(result.toString().contains("provider-secret")); assertFalse(result.toString().contains("key-a"))
        assertEquals(GatewayToolDocuments.ChannelBulkImport, request()["query"].text)
        assertEquals(GatewayService.CHANNEL_DETAIL, request()["query"].text)
        assertEquals(GatewayService.CHANNEL_SECRETS, request()["query"].text)
    }
    @Test fun `import reads back every created channel and reports all verified ids`(): Unit = runBlocking<Unit> {
        reply("""{"bulkImportChannels":{"success":true,"created":2,"failed":0,"channels":[{"id":"channel-a"},{"id":"channel-b"}]}}""")
        importedChannel(); importedChannel("channel-b", "B", "key-b")
        val result = service.bulkChannels(ChannelImportMode.IMPORT, importInput(), true)
        assertEquals(2, result["verified"].intOrNull)
        assertEquals(listOf("channel-a", "channel-b"), result["verifiedIds"].arr.map { it.text })
        request(); request(); request()
        assertEquals("channel-b", request()["variables"]["id"].text)
        assertEquals("channel-b", request()["variables"]["id"].text)
    }
    @Test fun `partial import acknowledgement cannot bypass successful record readback`(): Unit = runBlocking<Unit> {
        reply("""{"bulkImportChannels":{"success":false,"created":1,"failed":1,"channels":[{"id":"channel-a"}]}}""")
        reply("""{"channels":{"edges":[]}}""")
        fails(AxonException.VerificationFailed) { service.bulkChannels(ChannelImportMode.IMPORT, importInput(), true) }
    }
    @Test fun `fully failed import is a structured result with no successful records to read back`(): Unit = runBlocking<Unit> {
        reply("""{"bulkImportChannels":{"success":false,"created":0,"failed":2,"channels":[]}}""")
        val result = service.bulkChannels(ChannelImportMode.IMPORT, importInput(), true)
        assertEquals(0, result["verified"].intOrNull); assertEquals(2, result["failed"].intOrNull)
        assertEquals(false, result["partial"].boolOrNull); assertEquals(false, result["success"].boolOrNull)
        assertEquals(2, server.requestCount)
    }
    @Test fun `import refuses contradictory success counts and missing channel lists`(): Unit = runBlocking<Unit> {
        listOf(
            """{"success":true,"created":1,"failed":1,"channels":[{"id":"channel-a"}]}""",
            """{"success":false,"created":1,"failed":0,"channels":[{"id":"channel-a"}]}""",
            """{"success":false,"created":1,"failed":1,"channels":[]}""",
            """{"success":false,"created":0,"failed":2}"""
        ).forEach { result ->
            reply("""{"bulkImportChannels":$result}""")
            fails(AxonException.VerificationFailed) { service.bulkChannels(ChannelImportMode.IMPORT, importInput(), true) }
            request()
        }
        assertEquals(5, server.requestCount)
    }
    @Test fun `bulk create empty or omitted tags verify upstream base name default`(): Unit = runBlocking<Unit> {
        listOf("\"tags\":[],", "").forEach { tags ->
            val input = Json.parseToJsonElement("""{"type":"openai","name":"Base",$tags"baseURL":"https://provider.test","apiKeys":["key-a"],"supportedModels":[],"defaultTestModel":""}""").jsonObject
            reply("""{"bulkCreateChannels":[{"id":"channel-a"}]}""")
            channel("""{"id":"channel-a","name":"Base - (1)","tags":["Base"],"type":"openai","baseURL":"https://provider.test","supportedModels":[],"defaultTestModel":""}""")
            secrets(credentials = """{"apiKeys":["key-a"]}""")
            assertEquals(1, service.bulkChannels(ChannelImportMode.CREATE, input, true)["verified"].intOrNull)
            assertEquals(input, request()["variables"]["input"]); request(); request()
        }
    }
    @Test fun `bulk create explicit tags are preserved rather than replaced by base name`(): Unit = runBlocking<Unit> {
        val input = Json.parseToJsonElement("""{"type":"openai","name":"Base","tags":["Custom"],"baseURL":"https://provider.test","apiKeys":["key-a"],"supportedModels":[],"defaultTestModel":""}""").jsonObject
        reply("""{"bulkCreateChannels":[{"id":"channel-a"}]}""")
        channel("""{"id":"channel-a","name":"Base - (1)","tags":["Custom"],"type":"openai","baseURL":"https://provider.test","supportedModels":[],"defaultTestModel":""}""")
        secrets(credentials = """{"apiKeys":["key-a"]}""")
        assertEquals(1, service.bulkChannels(ChannelImportMode.CREATE, input, true)["verified"].intOrNull)
    }
    @Test fun `bulk create default tags still fail verification if readback is empty`(): Unit = runBlocking<Unit> {
        val input = Json.parseToJsonElement("""{"type":"openai","name":"Base","tags":[],"baseURL":"https://provider.test","apiKeys":["key-a"],"supportedModels":[],"defaultTestModel":""}""").jsonObject
        reply("""{"bulkCreateChannels":[{"id":"channel-a"}]}""")
        channel("""{"id":"channel-a","name":"Base - (1)","tags":[],"type":"openai","baseURL":"https://provider.test","supportedModels":[],"defaultTestModel":""}""")
        fails(AxonException.VerificationFailed) { service.bulkChannels(ChannelImportMode.CREATE, input, true) }
        assertEquals(3, server.requestCount)
    }
    @Test fun `all JUnit test methods expose JVM void return types`(): Unit = runBlocking<Unit> {
        GatewayToolsContractTest::class.java.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.forEach { method ->
            assertEquals(method.name, java.lang.Void.TYPE, method.returnType)
        }
    }
    @Test fun `numeric readback accepts GraphQL float normalization without normalizing ids`(): Unit = runBlocking<Unit> {
        assertTrue(gatewayMatches(Json.parseToJsonElement("0.0"), JsonPrimitive(0)))
        assertFalse(gatewayMatches(JsonPrimitive("0.0"), JsonPrimitive("0")))
        assertFalse(gatewayMatches(JsonPrimitive("001"), JsonPrimitive("1")))
    }
    @Test fun `header merge is case insensitive and body merge preserves operation order`(): Unit = runBlocking<Unit> {
        fun array(value: String) = Json.parseToJsonElement(value).jsonArray
        val existing = array("""[{"op":"set","path":"X-Key","value":"old"},{"op":"copy","from":"a","to":"b"}]""")
        val template = array("""[{"op":"set","path":"x-key","value":"new"}]""")
        assertEquals("new", mergeGatewayOverrides(existing, template, true).first()["value"].text)
        assertEquals(2, mergeGatewayOverrides(existing, template, true).size)
        val body = mergeGatewayOverrides(array("""[{"op":"set","path":"a","value":"old"},{"op":"copy","from":"x","to":"y"}]"""), array("""[{"op":"delete","path":"a"},{"op":"set_if_absent","path":"a","value":"new"}]"""), false)
        assertEquals(listOf("delete", "set_if_absent", "copy"), body.map { it["op"].text })
    }
}
