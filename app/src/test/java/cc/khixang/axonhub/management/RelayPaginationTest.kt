package cc.khixang.axonhub.management

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RelayPaginationTest {
    @Test fun `relay page preserves node order cursor and total`() {
        val root = buildJsonObject {
            put("edges", buildJsonArray {
                add(buildJsonObject { put("cursor", "a"); put("node", buildJsonObject { put("id", "1") }) })
                add(buildJsonObject { put("cursor", "b"); put("node", buildJsonObject { put("id", "2") }) })
            })
            put("pageInfo", buildJsonObject { put("hasNextPage", true); put("endCursor", "b") })
            put("totalCount", 7)
        }
        val page = parseRelayPage(root)
        assertEquals(listOf("1", "2"), page.items.map { it["id"]?.jsonPrimitive?.content })
        assertEquals("b", page.endCursor); assertEquals(7, page.total)
    }

    @Test fun `relay page hides cursor when pagination is complete`() {
        val page = parseRelayPage(buildJsonObject { put("edges", buildJsonArray {}); put("pageInfo", buildJsonObject { put("hasNextPage", false); put("endCursor", "stale") }) })
        assertNull(page.endCursor); assertTrue(page.items.isEmpty())
    }
}
