package cc.khixang.axonhub.management

import cc.khixang.axonhub.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ManagementEditorRegressionTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test fun `decimal displays never round trip through double or modify payloads`() {
        val raw = "9007199254740993.125"
        assertEquals("9,007,199,254,740,993.12", ManagementFormat.number(raw, Locale.US))
        assertEquals("9,007,199,254,740,993.1 USD", ManagementFormat.money(raw, Locale.US))
        assertEquals("1.0 USD", ManagementFormat.money("1", Locale.US))
        assertEquals("1,2 USD", ManagementFormat.money("1.25", Locale.GERMANY))
        assertEquals("—", ManagementFormat.money("NaN", Locale.US))
        assertEquals("—", ManagementFormat.number("Infinity", Locale.US))
        assertEquals("0.0 USD", ManagementFormat.money("1e-12", Locale.US))
        assertEquals(raw, JsonPrimitive(raw).text)
    }

    @Test fun `user keys hide and omit immutable scope controls`() {
        val input = json("""{"name":"Personal","scopes":["read_channels"],"allowedIps":[]}""").obj
        assertFalse(KeyEditorPolicy.canEditScopes("user"))
        assertTrue(KeyEditorPolicy.canEditScopes("service_account"))
        assertFalse("scopes" in KeyEditorPolicy.editableKeyInput(input, "user"))
        assertEquals(input, KeyEditorPolicy.editableKeyInput(input, "service_account"))
    }

    @Test fun `clearing allowlist and service scopes uses explicit clear flags`() {
        val patch = KeyEditorPolicy.prepareKeyPatch(json("""{"allowedIps":[],"scopes":[]}""").obj, "service_account")
        assertEquals(json("""{"clearAllowedIps":true,"clearScopes":true}"""), patch)
        assertThrows(IllegalArgumentException::class.java) { KeyEditorPolicy.prepareKeyPatch(json("""{"appendScopes":[]}""").obj, "user") }
    }

    @Test fun `project profiles never acquire key only routing permissions`() {
        val profile = json("""{"name":"project","channelIDs":[1]}""")
        assertEquals(profile, KeyEditorPolicy.normalizeProfile(profile, false))
        val key = KeyEditorPolicy.normalizeProfile(profile, true)
        assertEquals("default", key["loadBalanceStrategy"].text)
        assertEquals("default", key["traceStickyMode"].text)
    }

    @Test fun `profile validation requires unique names and active membership`() {
        assertThrows(IllegalArgumentException::class.java) { KeyEditorPolicy.validateProfiles(json("""{"activeProfile":"missing","profiles":[{"name":"A"}]}"""), false) }
        assertThrows(IllegalArgumentException::class.java) { KeyEditorPolicy.validateProfiles(json("""{"activeProfile":"A","profiles":[{"name":"A"},{"name":" a "}]}"""), false) }
        KeyEditorPolicy.validateProfiles(json("""{"activeProfile":"A","profiles":[{"name":"A"}]}"""), false)
    }

    @Test fun `quota validation retains decimal precision and rejects invalid limits`() {
        val valid = json("""{"activeProfile":"A","profiles":[{"name":"A","quota":{"cost":"0.000000123456789","period":{"type":"all_time"}}}]}""")
        KeyEditorPolicy.validateProfiles(valid, true)
        assertEquals("0.000000123456789", valid["profiles"].arr[0]["quota"]["cost"].text)
        for (quota in listOf("""{"cost":"NaN"}""", """{"requests":0}""", """{"totalTokens":1.5}""", """{"cost":"-0.1"}""", """{}""")) {
            val profile = json(quota).obj + ("period" to json("""{"type":"all_time"}"""))
            val value = buildJsonObject { put("activeProfile", "A"); put("profiles", JsonArray(listOf(buildJsonObject { put("name", "A"); put("quota", JsonObject(profile)) }))) }
            assertThrows(IllegalArgumentException::class.java) { KeyEditorPolicy.validateProfiles(value, true) }
        }
    }

    @Test fun `removal and rename preserve a valid active profile`() {
        val state = json("""{"activeProfile":"A","profiles":[{"name":"A"},{"name":"B"}]}""")
        val replaced = KeyEditorPolicy.replaceProfiles(state, listOf(json("""{"name":"B"}""")))
        assertEquals("B", replaced["activeProfile"].text)
        val renamed = KeyEditorPolicy.renameProfile(state, 0, "New")
        assertEquals("New", renamed["activeProfile"].text)
    }

    @Test fun `readback detects removed quota and permission fields not just a matching subset`() {
        val expected = json("""{"activeProfile":"A","profiles":[{"name":"A"}]}""")
        val staleQuota = json("""{"activeProfile":"A","profiles":[{"name":"A","quota":{"cost":"10"}}]}""")
        assertFalse(KeyEditorPolicy.profilesReadbackMatches(staleQuota, expected))
        val metadata = json("""{"activeProfile":"A","profiles":[{"name":"A","templateID":null,"templateName":"Template","quota":null}]}""")
        assertTrue(KeyEditorPolicy.profilesReadbackMatches(metadata, expected))
    }

    @Test fun `channel selectors support actual GUIDs and validated legacy numeric ids`() {
        assertEquals(42, KeyEditorPolicy.channelNumericId("gid://axonhub/Channel/42"))
        assertEquals(42, KeyEditorPolicy.channelNumericId("42"))
        listOf("gid://axonhub/Model/42", "gid://axonhub/Channel/0", "gid://axonhub/Channel/-1", "gid://axonhub/Channel/42/extra", "gid://axonhub/Channel/2147483648", "gid://axonhub/Channel/+42", "0", "-42", "TW9kZWw6NDI=").forEach {
            assertNull(it, KeyEditorPolicy.channelNumericId(it))
        }
        assertEquals(42, KeyEditorPolicy.channelNumericId("Q2hhbm5lbDo0Mg=="))
        assertNull(KeyEditorPolicy.channelNumericId("Channel/42"))
        assertNull(KeyEditorPolicy.channelNumericId("invalid="))
    }

    @Test fun `reveal rejects a different target and empty secrets`() {
        assertThrows(IllegalArgumentException::class.java) { KeyEditorPolicy.revealedKey(json("""{"id":"other","key":"secret"}"""), "target") }
        assertThrows(IllegalArgumentException::class.java) { KeyEditorPolicy.revealedKey(json("""{"id":"target","key":""}"""), "target") }
        assertEquals("secret", KeyEditorPolicy.revealedKey(json("""{"id":"target","key":"secret"}"""), "target"))
    }
}
