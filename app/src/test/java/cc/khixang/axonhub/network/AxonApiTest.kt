package cc.khixang.axonhub.network

import cc.khixang.axonhub.core.AuthType
import cc.khixang.axonhub.core.AxonInstance
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AxonApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AxonApi
    @Before fun setup() { server = MockWebServer(); server.start(); api = AxonApi() }
    @After fun teardown() { server.shutdown() }
    private fun instance(auth: AuthType = AuthType.ADMIN) = AxonInstance(name = "test", address = server.url("/base/path").toString().trimEnd('/'), allowHttp = true, authType = auth, adminEmail = "admin@example.test")

    @Test fun `validation preserves base subpath and rejects unsafe URLs`() {
        assertEquals("/base/path/admin/graphql", api.endpoint(instance().address, "/admin/graphql", true).encodedPath)
        assertThrows(AxonException.InsecureUrl::class.java) { api.validateBaseUrl("http://example.test/base", false) }
        listOf("https://user:pass@example.test", "https://example.test/base?q=secret", "https://example.test/base#fragment", "ftp://example.test").forEach {
            assertThrows(AxonException.InvalidUrl::class.java) { api.validateBaseUrl(it, true) }
        }
    }

    @Test fun `signin posts exact contract and does not persist password`() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"jwt-value"}""").setHeader("Content-Type", "application/json"))
        assertEquals("jwt-value", api.signIn(instance(), "password-value"))
        val request = server.takeRequest()
        assertEquals("/base/path/admin/auth/signin", request.path)
        assertEquals("POST", request.method)
        assertEquals("admin@example.test", request.body.readUtf8().substringAfter("email\":\"").substringBefore('"'))
    }

    @Test fun `graphql scopes project and classifies errors without echoing server prose`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"ok":true}}""").setHeader("Content-Type", "application/json"))
        val session = AxonSession(instance(), "admin-jwt")
        api.graphQl(session, "query Test { ok }", buildJsonObject { put("x", 1) }, "project-1")
        val request = server.takeRequest()
        assertEquals("Bearer admin-jwt", request.getHeader("Authorization")); assertEquals("project-1", request.getHeader("X-Project-ID")); assertEquals("/base/path/admin/graphql", request.path)
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"errors":[{"message":"password=server-secret","extensions":{"code":"BAD_USER_INPUT"}}]}"""))
        val failure = try {
            api.graphQl(session, "mutation Bad { bad }")
            fail("Expected the GraphQL request to be rejected")
            null
        } catch (error: AxonException.Rejected) { error }
        assertNotNull(failure)
        assertFalse(failure?.message.orEmpty().contains("server-secret"))
    }

    @Test fun `redirect is not followed and authorization is not leaked`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.invalid/steal"))
        try {
            api.graphQl(AxonSession(instance(), "top-secret"), "query Test { ok }")
            fail("Expected the redirect response to be rejected")
        } catch (_: AxonException.HttpStatus) { }
        assertEquals(1, server.requestCount)
    }

    @Test fun `api key model discovery uses exact v1 path`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"model-a","owned_by":"provider-a"}]}"""))
        val models = api.v1Models(AxonSession(instance(AuthType.API_KEY), "key-value"))
        assertEquals("model-a", models.single().modelId); assertEquals("provider-a", models.single().developer); assertEquals("", models.single().type); assertEquals("/base/path/v1/models", server.takeRequest().path)
    }

    @Test fun `injected clients cannot reenable redirects or cookies`() {
        val unsafe = okhttp3.OkHttpClient.Builder().followRedirects(true).followSslRedirects(true)
            .cookieJar(object : okhttp3.CookieJar { override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) = Unit; override fun loadForRequest(url: okhttp3.HttpUrl) = emptyList<okhttp3.Cookie>() }).build()
        val hardened = AxonApi(client = unsafe).client
        assertFalse(hardened.followRedirects); assertFalse(hardened.followSslRedirects); assertSame(okhttp3.CookieJar.NO_COOKIES, hardened.cookieJar)
    }

    @Test fun `restore uses graphql multipart map without redirect or cookies`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"restore":{"success":true}}}"""))
        val result = api.graphQlMultipart(
            AxonSession(instance(), "admin-jwt"),
            "mutation Restore(${ '$' }file: Upload!, ${ '$' }input: RestoreOptionsInput!) { restore(file: ${ '$' }file, input: ${ '$' }input) { success } }",
            buildJsonObject { put("mode", "merge") },
            "{\"version\":\"1\",\"channels\":[],\"models\":[]}".toByteArray(),
            "project-1",
        )
        assertTrue(result["restore"].toString().contains("true"))
        val request = server.takeRequest(); val body = request.body.readUtf8()
        assertEquals("/base/path/admin/graphql", request.path)
        assertEquals("Bearer admin-jwt", request.getHeader("Authorization")); assertEquals("project-1", request.getHeader("X-Project-ID")); assertNull(request.getHeader("Cookie"))
        assertTrue(body.contains("variables.file")); assertTrue(body.contains("name=\"0\"; filename=\"backup.json\"")); assertTrue(body.contains("application/json"))
    }

    @Test fun `project invitations use fixed rest routes and scoped header`() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"invite_token","projectName":"P","expiresAt":"later","maxUses":1,"usedCount":0,"remainingUses":1}"""))
        server.enqueue(MockResponse().setBody("""{"projectName":"P","expiresAt":"later","maxUses":1,"usedCount":0,"remainingUses":1}"""))
        val session = AxonSession(instance(), "admin-jwt")
        assertEquals("invite_token", api.createInvitation(session, "project-1", 42, 168, 1)["token"]?.jsonPrimitive?.content)
        val create = server.takeRequest(); assertEquals("/base/path/admin/invitations", create.path); assertEquals("project-1", create.getHeader("X-Project-ID")); assertTrue(create.body.readUtf8().contains("\"roleID\":42"))
        assertEquals("P", api.invitationDetail(session, "invite_token")["projectName"]?.jsonPrimitive?.content)
        assertEquals("/base/path/auth/invitations/invite_token", server.takeRequest().path)
    }
}
