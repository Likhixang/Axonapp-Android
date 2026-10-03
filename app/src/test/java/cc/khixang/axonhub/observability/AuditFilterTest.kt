package cc.khixang.axonhub.observability

import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.network.AxonException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AuditFilterTest {
    @Test fun `request filters use exact iOS where field names`() {
        val filter = AuditFilter(search = "gpt", statuses = setOf("failed"), sources = setOf("api"), projects = "p", channels = "c", apiKeys = "k", format = "openai", clientIP = "127.0", externalID = "upstream", stream = "yes", start = "2026-10-01T00:00:00+08:00", end = "2026-10-03T00:00:00+08:00").json(ObserveKind.REQUESTS)
        assertEquals(setOf("createdAtGTE", "createdAtLTE", "statusIn", "projectIDIn", "channelIDIn", "apiKeyIDIn", "sourceIn", "formatContainsFold", "clientIPContains", "externalIDContains", "stream", "modelIDContainsFold"), filter.keys)
        assertEquals(JsonPrimitive(true), filter["stream"])
        assertEquals("k", filter["apiKeyIDIn"].arr.single().text)
        assertTrue(filter["apiKeyIDIn"].arr.single().jsonPrimitive.isString)
    }

    @Test fun `usage requires integer api key identifiers and does not emit request only fields`() {
        val filter = AuditFilter(apiKeys = "42, 43", search = "gpt", stream = "yes", clientIP = "127.0", externalID = "upstream").json(ObserveKind.USAGE)
        assertEquals(setOf("apiKeyIDIn", "modelIDContainsFold"), filter.keys)
        assertFalse(filter["apiKeyIDIn"].arr.singleOrNull()?.jsonPrimitive?.isString ?: false)
        assertEquals(listOf(42, 43), filter["apiKeyIDIn"].arr.map { it.intOrNull })
        assertThrows(IllegalArgumentException::class.java) { AuditFilter(apiKeys = "relay-node").json(ObserveKind.USAGE) }
        assertThrows(IllegalArgumentException::class.java) { AuditFilter(apiKeys = "9999999999999999999999").json(ObserveKind.USAGE) }
    }

    @Test fun `trace and thread status choices cannot send request status enums`() {
        assertEquals(listOf("active", "archived", "retained"), auditStatuses(ObserveKind.TRACES))
        assertTrue(auditStatuses(ObserveKind.USAGE).isEmpty())
        assertEquals(setOf("traceIDContainsFold", "projectIDIn", "statusIn"), AuditFilter(search = "trace", projects = "p", statuses = setOf("retained"), channels = "c", apiKeys = "k").json(ObserveKind.TRACES).keys)
        assertEquals(setOf("threadIDContainsFold"), AuditFilter(search = "thread").json(ObserveKind.THREADS).keys)
        assertThrows(IllegalArgumentException::class.java) { AuditFilter(statuses = setOf("completed")).json(ObserveKind.TRACES) }
    }

    @Test fun `dates compare actual instants and reject invalid values`() {
        assertThrows(IllegalArgumentException::class.java) { AuditFilter(start = "2026-10-03T00:00:00Z", end = "2026-10-02T23:59:59Z").json(ObserveKind.REQUESTS) }
        assertThrows(Exception::class.java) { AuditFilter(start = "2026-10-03").json(ObserveKind.REQUESTS) }
        assertTrue(AuditFilter().json(ObserveKind.REQUESTS).isEmpty())
    }

    @Test fun `malformed audit page is an error not a fabricated empty result`() {
        assertThrows(AxonException.InvalidResponse::class.java) { parseAuditPage(JsonNull) }
        assertThrows(AxonException.InvalidResponse::class.java) { parseAuditPage(Json.parseToJsonElement("""{"edges":[],"pageInfo":{"hasNextPage":true}}""")) }
        val empty = parseAuditPage(Json.parseToJsonElement("""{"edges":[],"pageInfo":{"hasNextPage":false,"endCursor":null},"totalCount":0}"""))
        assertTrue(empty.items.isEmpty()); assertNull(empty.endCursor); assertEquals(0, empty.total)
        val real = parseAuditPage(Json.parseToJsonElement("""{"edges":[{"node":{"id":"row","totalCost":"9007199254740993.1","token":"secret"}}],"pageInfo":{"hasNextPage":false},"totalCount":1}"""))
        assertEquals("9007199254740993.1", real.items.single()["totalCost"].text)
        assertEquals("[REDACTED]", real.items.single()["token"].text)
    }
}
