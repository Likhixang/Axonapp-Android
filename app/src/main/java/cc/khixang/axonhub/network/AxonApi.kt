package cc.khixang.axonhub.network

import cc.khixang.axonhub.core.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class AxonException(message: String) : Exception(message) {
    data object InvalidUrl : AxonException("Enter a valid AxonHub URL without credentials, query, or fragment.")
    data object InsecureUrl : AxonException("HTTPS is required unless HTTP is explicitly allowed for a trusted network.")
    data object InvalidCredentials : AxonException("Credentials are empty or invalid.")
    data object Unauthorized : AxonException("Authentication failed or the session expired.")
    data object Forbidden : AxonException("You do not have permission for this operation.")
    data object Transport : AxonException("Could not connect to AxonHub. Check the address, network, and certificate.")
    data object TimedOut : AxonException("The request timed out.")
    data class HttpStatus(val status: Int) : AxonException("Server returned HTTP $status.")
    data object InvalidResponse : AxonException("The gateway returned an unexpected response.")
    data object Rejected : AxonException("The server rejected the request. Check permissions and input.")
    data object TargetChanged : AxonException("The instance or project changed; the operation was cancelled.")
    data object VerificationFailed : AxonException("The server response could not be verified by readback.")
}

data class AxonSession(val instance: AxonInstance, val token: String)

class AxonApi(
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    client: OkHttpClient? = null,
) {
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    val client: OkHttpClient = (client?.newBuilder() ?: OkHttpClient.Builder())
        .followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    fun validateBaseUrl(raw: String, allowHttp: Boolean): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull() ?: throw AxonException.InvalidUrl
        if (url.scheme == "http" && !allowHttp) throw AxonException.InsecureUrl
        if (url.scheme != "https" && url.scheme != "http") throw AxonException.InvalidUrl
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) throw AxonException.InvalidUrl
        return url
    }

    fun endpoint(baseUrl: String, path: String, allowHttp: Boolean): HttpUrl {
        val base = validateBaseUrl(baseUrl, allowHttp)
        return base.newBuilder().encodedPath("${base.encodedPath.trimEnd('/')}/${path.trimStart('/')}").query(null).fragment(null).build()
    }

    suspend fun signIn(instance: AxonInstance, password: String): String {
        if (instance.adminEmail.isBlank() || password.isBlank()) throw AxonException.InvalidCredentials
        val body = buildJsonObject { put("email", instance.adminEmail.trim()); put("password", password) }
        val request = Request.Builder().url(endpoint(instance.address, "admin/auth/signin", instance.allowHttp))
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(mediaType)).build()
        return executeJson(request)["token"].text.ifBlank { throw AxonException.InvalidResponse }
    }

    suspend fun authenticate(instance: AxonInstance, secret: String): String {
        if (secret.isBlank()) throw AxonException.InvalidCredentials
        validateBaseUrl(instance.address, instance.allowHttp)
        val token = if (instance.authType == AuthType.ADMIN) signIn(instance, secret) else secret.trim()
        val session = AxonSession(instance, token)
        if (instance.authType == AuthType.ADMIN) graphQl(session, Documents.SNAPSHOT) else v1Models(session)
        return token
    }

    suspend fun graphQl(
        session: AxonSession,
        document: String,
        variables: JsonObject = buildJsonObject {},
        projectId: String? = null,
        maxResponseBytes: Long = MAX_JSON_BYTES,
    ): JsonObject {
        if (session.instance.authType != AuthType.ADMIN) throw AxonException.Forbidden
        val payload = buildJsonObject { put("query", document); if (variables.isNotEmpty()) put("variables", variables) }
        val builder = authorized(session, "admin/graphql").post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(mediaType))
            .header("Content-Type", "application/json")
        if (!projectId.isNullOrBlank()) builder.header("X-Project-ID", projectId)
        val root = executeJson(builder.build(), maxResponseBytes)
        val errors = root["errors"].arr
        if (errors.isNotEmpty()) {
            val codes = errors.map { it["extensions"]["code"].text }
            if ("UNAUTHENTICATED" in codes) throw AxonException.Unauthorized
            if ("FORBIDDEN" in codes) throw AxonException.Forbidden
            throw AxonException.Rejected
        }
        return root["data"].obj.takeIf { it.isNotEmpty() } ?: throw AxonException.InvalidResponse
    }

    suspend fun graphQlMultipart(
        session: AxonSession,
        document: String,
        input: JsonObject,
        file: ByteArray,
        projectId: String? = null,
    ): JsonObject {
        if (session.instance.authType != AuthType.ADMIN) throw AxonException.Forbidden
        val operations = buildJsonObject {
            put("query", document)
            put("variables", buildJsonObject { put("file", JsonNull); put("input", input) })
        }
        val map = buildJsonObject { put("0", buildJsonArray { add("variables.file") }) }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("operations", json.encodeToString(JsonObject.serializer(), operations))
            .addFormDataPart("map", json.encodeToString(JsonObject.serializer(), map))
            .addFormDataPart("0", "backup.json", file.toRequestBody("application/json".toMediaType()))
            .build()
        val builder = authorized(session, "admin/graphql").post(body)
        if (!projectId.isNullOrBlank()) builder.header("X-Project-ID", projectId)
        val root = executeJson(builder.build())
        val errors = root["errors"].arr
        if (errors.isNotEmpty()) {
            val codes = errors.map { it["extensions"]["code"].text }
            if ("UNAUTHENTICATED" in codes) throw AxonException.Unauthorized
            if ("FORBIDDEN" in codes) throw AxonException.Forbidden
            throw AxonException.Rejected
        }
        return root["data"].obj.takeIf { it.isNotEmpty() } ?: throw AxonException.InvalidResponse
    }

    suspend fun v1Models(session: AxonSession): List<ModelItem> {
        val root = executeJson(authorized(session, "v1/models").get().build())
        return root["data"].arr.mapNotNull {
            val id = it["id"].text
            id.takeIf(String::isNotBlank)?.let { value -> ModelItem(value, value, value, it["owned_by"].text) }
        }
    }

    fun authorized(session: AxonSession, path: String): Request.Builder {
        if (session.token.isBlank()) throw AxonException.InvalidCredentials
        return Request.Builder().url(endpoint(session.instance.address, path, session.instance.allowHttp))
            .header("Authorization", "Bearer ${session.token}").header("Accept", "application/json")
    }

    suspend fun oauth(session: AxonSession, provider: String, action: String, body: JsonObject): JsonObject {
        val allowed = mapOf(
            "codex" to setOf("oauth/start", "oauth/exchange", "auth/decode"), "claudecode" to setOf("oauth/start", "oauth/exchange"),
            "antigravity" to setOf("oauth/start", "oauth/exchange"), "xai" to setOf("oauth/start", "oauth/exchange", "oauth/sso"),
            "copilot" to setOf("oauth/start", "oauth/poll"),
        )
        if (session.instance.authType != AuthType.ADMIN || action !in allowed[provider].orEmpty()) throw AxonException.Forbidden
        val request = authorized(session, "admin/$provider/$action")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(mediaType)).build()
        return executeJson(request)
    }

    suspend fun createInvitation(session: AxonSession, projectId: String, roleId: Int, expiresInHours: Int, maxUses: Int): JsonObject {
        if (session.instance.authType != AuthType.ADMIN || projectId.isBlank() || roleId <= 0 || expiresInHours < 0 || maxUses < 0) throw AxonException.Forbidden
        val body = buildJsonObject { put("roleID", roleId); put("expiresInHours", expiresInHours); put("maxUses", maxUses) }
        val request = authorized(session, "admin/invitations").header("X-Project-ID", projectId)
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(mediaType)).build()
        return executeJson(request)
    }

    suspend fun invitationDetail(session: AxonSession, token: String): JsonObject {
        if (session.instance.authType != AuthType.ADMIN || !token.matches(Regex("[A-Za-z0-9_-]{8,}"))) throw AxonException.InvalidResponse
        return executeJson(authorized(session, "auth/invitations/$token").get().build())
    }

    suspend fun executeJson(request: Request, maxResponseBytes: Long = MAX_JSON_BYTES): JsonObject = suspendCancellableCoroutine { continuation ->
        require(maxResponseBytes in 1..MAX_ALLOWED_JSON_BYTES)
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (!continuation.isActive) return
                continuation.resumeWithException(if (error is SocketTimeoutException) AxonException.TimedOut else AxonException.Transport)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) { response.close(); return }
                try { response.use {
                when (response.code) { 401 -> throw AxonException.Unauthorized; 403 -> throw AxonException.Forbidden }
                val body = readBody(response, maxResponseBytes)
                if (!response.isSuccessful) {
                    val root = runCatching { json.parseToJsonElement(body).obj }.getOrNull()
                    if (root == null) throw AxonException.HttpStatus(response.code)
                    val codes = root["errors"].arr.map { it["extensions"]["code"].text }
                    if ("UNAUTHENTICATED" in codes) throw AxonException.Unauthorized
                    if ("FORBIDDEN" in codes) throw AxonException.Forbidden
                    if (root["errors"].arr.isNotEmpty()) throw AxonException.Rejected
                    throw AxonException.HttpStatus(response.code)
                }
                continuation.resume(runCatching { json.parseToJsonElement(body).obj }.getOrElse { throw AxonException.InvalidResponse })
                } } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(if (e is AxonException) e else AxonException.Transport) }
            }
        })
    }

    private fun readBody(response: Response, maxBytes: Long): String {
        val source = response.body?.source() ?: return ""
        val output = okio.Buffer(); var total = 0L
        while (true) {
            val count = source.read(output, minOf(8192L, maxBytes + 1 - total))
            if (count < 0) break
            total += count
            if (total > maxBytes) throw AxonException.InvalidResponse
        }
        return output.readString(Charsets.UTF_8)
    }

    companion object {
        private const val MAX_JSON_BYTES = 8L * 1024 * 1024
        private const val MAX_ALLOWED_JSON_BYTES = 64L * 1024 * 1024
    }
}

object Documents {
    const val SNAPSHOT = """
        query AxonOverview {
          dashboardOverview { totalRequests failedRequests averageResponseTime requestStats { requestsToday requestsThisWeek requestsLastWeek requestsThisMonth } }
          tokenStats { totalInputTokensToday totalOutputTokensToday totalCachedTokensToday totalInputTokensThisMonth totalOutputTokensThisMonth totalCachedTokensThisMonth totalInputTokensAllTime totalOutputTokensAllTime totalCachedTokensAllTime }
          allChannelSummarys(includeArchived: true) { id name type baseURL status supportedModels orderingWeight errorMessage autoDisabledAt tags remark }
          models(first: 100, orderBy: { direction: DESC, field: CREATED_AT }) { edges { node { id modelID name developer type group icon status remark } } pageInfo { hasNextPage endCursor } }
          requests(first: 50, orderBy: { direction: DESC, field: CREATED_AT }) { edges { node { id createdAt modelID source format status stream clientIP metricsLatencyMs metricsFirstTokenLatencyMs metricsReasoningDurationMs executions(first: 1, orderBy: { direction: DESC, field: CREATED_AT }) { edges { node { errorMessage } } } } } }
          apiKeys(first: 50, orderBy: { direction: DESC, field: CREATED_AT }) { edges { node { id name type status scopes createdAt } } }
          dailyRequestStats { date count tokens cost }
          channelSuccessRates(timeWindow: "month", limit: 100) { channelId channelName channelType channelDisabled successCount failedCount totalCount successRate }
          analyticsOverview { totalCost }
        }
    """
}
