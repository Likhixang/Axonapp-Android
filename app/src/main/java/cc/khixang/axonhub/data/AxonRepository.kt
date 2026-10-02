package cc.khixang.axonhub.data

import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.network.*
import cc.khixang.axonhub.storage.InstanceStore
import cc.khixang.axonhub.storage.SecureCredentialStore
import cc.khixang.axonhub.storage.StoredInstances
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

class AxonRepository(
    private val store: InstanceStore,
    private val credentials: SecureCredentialStore,
    val api: AxonApi,
    private val scope: CoroutineScope,
) {
    private val mutationMutex = Mutex()
    private val generation = AtomicLong(1)
    private var loaded = false
    private var refreshJob: Job? = null
    private val activeJobs = mutableSetOf<Job>()
    private val cache = mutableMapOf<String, Snapshot>()

    private val _instances = MutableStateFlow<List<AxonInstance>>(emptyList())
    val instances = _instances.asStateFlow()
    private val _selectedId = MutableStateFlow("")
    val selectedId = _selectedId.asStateFlow()
    val selected = combine(_instances, _selectedId) { list, id -> list.firstOrNull { it.id == id } }.stateIn(scope, SharingStarted.Eagerly, null)
    private val _projectId = MutableStateFlow<String?>(null)
    val projectId = _projectId.asStateFlow()
    private val _snapshot = MutableStateFlow<LoadState<Snapshot>>(LoadState.Idle)
    val snapshot = _snapshot.asStateFlow()

    init {
        scope.launch {
            store.state.first().also { saved ->
                _instances.value = saved.instances
                _selectedId.value = saved.selectedId.takeIf { id -> saved.instances.any { it.id == id } } ?: saved.instances.firstOrNull()?.id.orEmpty()
                loaded = true
                cache[snapshotCacheKey(_selectedId.value, _projectId.value)]?.let { _snapshot.value = LoadState.Ready(it) }
                if (_selectedId.value.isNotEmpty()) refresh()
            }
        }
    }

    private suspend fun persist() {
        if (loaded) store.save(StoredInstances(_instances.value, _selectedId.value))
    }

    fun currentFence(): TargetFence {
        val instance = currentInstance()
        return TargetFence(instance.id, _projectId.value, generation.get())
    }

    private fun currentInstance(): AxonInstance = _instances.value.firstOrNull { it.id == _selectedId.value } ?: throw AxonException.InvalidUrl

    fun verify(fence: TargetFence) {
        if (fence.instanceId != _selectedId.value || fence.projectId != _projectId.value || fence.generation != generation.get()) throw AxonException.TargetChanged
    }

    fun session(fence: TargetFence = currentFence()): AxonSession {
        verify(fence)
        val instance = currentInstance()
        return AxonSession(instance, credentials.get(instance.id))
    }

    fun privateScopeKey(prefix: String, fence: TargetFence = currentFence()): String {
        val current = session(fence)
        val digest = MessageDigest.getInstance("SHA-256").digest(current.token.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        return "$prefix:${current.instance.id}:${fence.projectId.orEmpty()}:$digest"
    }

    fun savePrivateState(key: String, value: String) = credentials.put(key, value)
    fun loadPrivateState(key: String): String? = runCatching { credentials.get(key) }.getOrNull()
    fun clearPrivateState(key: String) = credentials.remove(key)

    suspend fun add(instance: AxonInstance, secret: String) {
        require(instance.name.isNotBlank()) { "Instance name is required." }
        val normalized = instance.copy(name = instance.name.trim(), address = instance.address.trim(), adminEmail = instance.adminEmail.trim())
        val token = api.authenticate(normalized, secret)
        credentials.put(normalized.id, token)
        _instances.value = _instances.value + normalized
        switch(normalized.id)
    }

    suspend fun update(instance: AxonInstance, secret: String) {
        val original = _instances.value.firstOrNull { it.id == instance.id } ?: throw AxonException.InvalidResponse
        val normalized = instance.copy(name = instance.name.trim(), address = instance.address.trim(), adminEmail = instance.adminEmail.trim())
        require(normalized.name.isNotBlank()) { "Instance name is required." }
        api.validateBaseUrl(normalized.address, normalized.allowHttp)
        val needsSecret = original.address != normalized.address || original.authType != normalized.authType || original.adminEmail != normalized.adminEmail
        if (needsSecret && secret.isBlank()) throw AxonException.InvalidCredentials
        val token = if (secret.isNotBlank()) api.authenticate(normalized, secret) else null
        mutationMutex.withLock {
            val index = _instances.value.indexOfFirst { it == original }
            if (index < 0) throw AxonException.TargetChanged
            token?.let { credentials.put(instance.id, it) }
            _instances.value = _instances.value.toMutableList().also { it[index] = normalized }
            persist()
            if (_selectedId.value == instance.id) invalidate(cacheEntry = true)
        }
        if (_selectedId.value == instance.id) refresh()
    }

    suspend fun delete(id: String) {
        credentials.remove(id); credentials.removePrefix("playground:$id:"); cache.keys.filter { it.startsWith("$id:") }.forEach(cache::remove)
        _instances.value = _instances.value.filterNot { it.id == id }
        if (_selectedId.value == id) {
            invalidate(cacheEntry = false)
            _selectedId.value = _instances.value.firstOrNull()?.id.orEmpty()
            _projectId.value = null
        }
        persist()
        if (_selectedId.value.isNotEmpty()) refresh()
    }

    suspend fun switch(id: String) {
        if (_instances.value.none { it.id == id }) throw AxonException.InvalidResponse
        if (_selectedId.value != id) {
            invalidate(cacheEntry = false)
            _selectedId.value = id
            _projectId.value = null
            _snapshot.value = cache[snapshotCacheKey(id, null)]?.let { LoadState.Ready(it) } ?: LoadState.Idle
            persist()
        }
        refresh()
    }

    fun selectProject(id: String?) {
        val next = id?.takeIf(String::isNotBlank)
        if (_projectId.value != next) {
            _projectId.value = next
            invalidate(cacheEntry = false)
            if (_selectedId.value.isNotEmpty()) scope.launch { refresh() }
        }
    }

    private fun invalidate(cacheEntry: Boolean) {
        generation.incrementAndGet(); refreshJob?.cancel(); refreshJob = null
        synchronized(activeJobs) { activeJobs.toList().forEach(Job::cancel); activeJobs.clear() }
        if (cacheEntry) cache.keys.filter { it.startsWith("${_selectedId.value}:") }.forEach(cache::remove)
        _snapshot.value = cache[snapshotCacheKey(_selectedId.value, _projectId.value)]?.let { LoadState.Ready(it) } ?: LoadState.Idle
    }

    suspend fun relogin(secret: String) {
        val instance = currentInstance()
        val token = api.authenticate(instance, secret)
        credentials.put(instance.id, token)
        invalidate(cacheEntry = true); refresh()
    }

    suspend fun refresh() {
        val instance = _instances.value.firstOrNull { it.id == _selectedId.value } ?: return
        refreshJob?.cancel()
        val fence = TargetFence(instance.id, _projectId.value, generation.get())
        _snapshot.value = LoadState.Loading
        val job = scope.launch {
            try {
                val session = session(fence)
                val value = if (instance.authType == AuthType.API_KEY) Snapshot(models = api.v1Models(session)) else parseSnapshot(api.graphQl(session, Documents.SNAPSHOT))
                verify(fence); cache[snapshotCacheKey(instance.id, fence.projectId)] = value; _snapshot.value = LoadState.Ready(value)
            } catch (_: CancellationException) { }
            catch (e: Exception) { if (runCatching { verify(fence) }.isSuccess) _snapshot.value = LoadState.Failed(e.message ?: "Request failed.") }
        }
        refreshJob = job
        job.join()
    }

    suspend fun <T> fenced(block: suspend (AxonSession, String?) -> T): T {
        val fence = currentFence(); val job = currentCoroutineContext().job
        synchronized(activeJobs) { activeJobs += job }
        return try { val value = block(session(fence), fence.projectId); verify(fence); value }
        finally { synchronized(activeJobs) { activeJobs -= job } }
    }

    suspend fun <T> fencedMutation(block: suspend (AxonSession, String?) -> T): T = mutationMutex.withLock {
        val fence = currentFence(); val job = currentCoroutineContext().job
        synchronized(activeJobs) { activeJobs += job }
        try { verify(fence); val value = block(session(fence), fence.projectId); verify(fence); value }
        finally { synchronized(activeJobs) { activeJobs -= job } }
    }

    private fun parseSnapshot(data: JsonObject): Snapshot {
        val dash = data["dashboardOverview"]; val req = dash["requestStats"]; val token = data["tokenStats"]
        return Snapshot(
            dashboard = DashboardStats(
                dash["totalRequests"].longOrNull, dash["failedRequests"].longOrNull, dash["averageResponseTime"].doubleOrNull,
                req["requestsToday"].longOrNull, req["requestsThisWeek"].longOrNull, req["requestsThisMonth"].longOrNull,
                token["totalInputTokensToday"].longOrNull, token["totalOutputTokensToday"].longOrNull, token["totalCachedTokensToday"].longOrNull,
                token["totalInputTokensThisMonth"].longOrNull, token["totalOutputTokensThisMonth"].longOrNull, token["totalCachedTokensThisMonth"].longOrNull,
                token["totalInputTokensAllTime"].longOrNull, token["totalOutputTokensAllTime"].longOrNull, token["totalCachedTokensAllTime"].longOrNull,
                data["analyticsOverview"]["totalCost"].doubleOrNull,
            ),
            channels = data["allChannelSummarys"].arr.map { c -> ChannelItem(c["id"].text, c["name"].text, c["type"].text, c["baseURL"].text.ifBlank { null }, c["status"].text, c["supportedModels"].arr.map { it.text }, c["orderingWeight"].intOrNull ?: 0, c["errorMessage"].text.ifBlank { null }, c["autoDisabledAt"].text.ifBlank { null }, c["tags"].arr.map { it.text }, c["remark"].text.ifBlank { null }) },
            models = data["models"]["edges"].arr.map { it["node"] }.map { m -> ModelItem(m["id"].text, m["modelID"].text, m["name"].text, m["developer"].text, m["type"].text, m["group"].text, m["icon"].text.ifBlank { null }, m["status"].text, m["remark"].text.ifBlank { null }) },
            requests = data["requests"]["edges"].arr.map { it["node"] }.map { r -> RequestItem(r["id"].text, r["createdAt"].text, r["modelID"].text, r["source"].text, r["format"].text, r["status"].text, r["stream"].boolOrNull ?: false, r["clientIP"].text, r["metricsLatencyMs"].intOrNull, r["metricsFirstTokenLatencyMs"].intOrNull, r["metricsReasoningDurationMs"].intOrNull, r["executions"]["edges"].arr.firstOrNull()?.get("node")?.get("errorMessage")?.text?.ifBlank { null }) },
            apiKeys = data["apiKeys"]["edges"].arr.map { it["node"] }.map { k -> ApiKeyItem(k["id"].text, k["name"].text, k["type"].text, k["status"].text, k["scopes"].arr.map { it.text }, k["createdAt"].text) },
            daily = data["dailyRequestStats"].arr.map { DailyStat(it["date"].text, it["count"].longOrNull, it["tokens"].longOrNull, it["cost"].doubleOrNull) },
            channelPerformance = data["channelSuccessRates"].arr.map { ChannelPerformance(it["channelId"].text, it["channelName"].text, it["channelType"].text, it["channelDisabled"].boolOrNull, it["successCount"].longOrNull, it["failedCount"].longOrNull, it["totalCount"].longOrNull, it["successRate"].doubleOrNull) },
        )
    }

    private fun snapshotCacheKey(instanceId: String, projectId: String?) = "$instanceId:${projectId.orEmpty()}"
}
