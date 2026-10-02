package cc.khixang.axonhub.observability

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.put

class ObservabilityRedactionTest {
    @Test fun `sanitizer removes url secrets and recursively sanitizes json strings`() {
        val value = kotlinx.serialization.json.buildJsonObject {
            put("requestURL", "https://username:p4ss@example.org/v1?sig=opaque&token=opaque#fragment")
            put("encoded", "{\"headers\":{\"x-custom-auth\":\"opaque-value\"},\"password\":\"opaque-value\"}")
        }
        val safe = sanitizeObservability(value).toString()
        assertTrue(safe.contains("https://example.org/v1"))
        listOf("username", "p4ss", "sig=", "token=", "fragment", "opaque-value").forEach { assertFalse(it, safe.contains(it)) }
    }
}
