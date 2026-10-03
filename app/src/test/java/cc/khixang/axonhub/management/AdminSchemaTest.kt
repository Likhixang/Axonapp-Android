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
    @Test fun `project profiles stay valid after native normalization`() {
        val original = buildJsonObject { put("name", "Default"); put("channelIDs", JsonArray(listOf(JsonPrimitive(42)))) }
        val normalized = KeyEditorPolicy.normalizeProfile(original, false)
        catalog.schema.validate(normalized, catalog.schema.types.getValue("ProjectProfileInput").fields, true)
        assertEquals(original, normalized)
    }
    @Test fun `numeric schema rejects nonfinite float and accepts exact decimal strings`() {
        val floating = listOf(AdminField("value", "Float!"))
        for (value in listOf(JsonPrimitive(Double.NaN), JsonPrimitive(Double.POSITIVE_INFINITY), JsonPrimitive("1.25"))) {
            assertThrows(IllegalArgumentException::class.java) { catalog.schema.validate(buildJsonObject { put("value", value) }, floating, true) }
        }
        val decimal = buildJsonObject { put("value", "9007199254740993.123456789") }
        catalog.schema.validate(decimal, listOf(AdminField("value", "DecimalInput!")), true)
        assertEquals("9007199254740993.123456789", decimal["value"].text)
    }
    @Test fun `mutation validation rejects null and undeclared keys`() {
        val operation = catalog.operation("createAPIKey")
        assertThrows(IllegalArgumentException::class.java) { catalog.schema.validate(buildJsonObject { put("unknown", true) }, operation.variables, true) }
        assertThrows(IllegalArgumentException::class.java) { catalog.schema.validate(buildJsonObject { put("input", JsonNull) }, operation.variables, true) }
    }
}
