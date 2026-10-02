package cc.khixang.axonhub.management

import android.content.Context
import cc.khixang.axonhub.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY) @SQLiteMode(SQLiteMode.Mode.LEGACY) @ConscryptMode(ConscryptMode.Mode.OFF) @Config(sdk = [35])
class AdminSchemaTest {
    private val catalog by lazy { AdminCatalog(RuntimeEnvironment.getApplication()) }
    @Test fun `all imported operations resolve exact documents`() {
        assertEquals("939b2bc07cc05bdf7750d7ec872290d67784d13d", catalog.schema.revision)
        assertEquals(126, catalog.schema.operations.size)
        catalog.schema.operations.forEach { assertTrue(it.id, catalog.document(it).contains(it.name)) }
    }
    @Test fun `recursive form defaults arrays enums and required nested fields`() {
        val value = catalog.schema.defaultValue("UpdateAPIKeyProfilesInput!")
        assertTrue(value is JsonObject); assertEquals("", value["activeProfile"].text)
        assertTrue(catalog.schema.defaultValue("[APIKeyProfileInput!]") is JsonArray)
        assertTrue(catalog.schema.defaultValue("APIKeyStatus!").text.isNotBlank())
    }
    @Test fun `mutation validation rejects null and undeclared keys`() {
        val operation = catalog.operation("createAPIKey")
        assertThrows(IllegalArgumentException::class.java) { catalog.schema.validate(buildJsonObject { put("unknown", true) }, operation.variables, true) }
        assertThrows(IllegalArgumentException::class.java) { catalog.schema.validate(buildJsonObject { put("input", JsonNull) }, operation.variables, true) }
    }
}
