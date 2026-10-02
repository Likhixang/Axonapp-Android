package cc.khixang.axonhub.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RedactionTest {
    @Test fun `recursive redaction removes header and credential values`() {
        val value = buildJsonObject {
            put("Authorization", "Bearer secret")
            put("nested", buildJsonObject { put("refresh_token", "token-value"); put("name", "visible") })
            put("rows", buildJsonArray { add(buildJsonObject { put("password", "password-value") }) })
        }.redacted()
        assertEquals("••••••", value["Authorization"].text)
        assertEquals("••••••", value["nested"]["refresh_token"].text)
        assertEquals("visible", value["nested"]["name"].text)
        assertEquals("••••••", value["rows"].arr.single()["password"].text)
        assertFalse(value.toString().contains("secret")); assertFalse(value.toString().contains("password-value"))
    }
}
