package cc.khixang.axonhub.storage

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY) @SQLiteMode(SQLiteMode.Mode.LEGACY) @ConscryptMode(ConscryptMode.Mode.OFF) @Config(sdk = [35])
class SecureCredentialStoreTest {
    @Test fun `aes gcm round trip stores no plaintext and deletion revokes access`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = SecureCredentialStore(context) { SecretKeySpec(ByteArray(32) { 3 }, "AES") }
        store.put("instance", "secret-token")
        assertEquals("secret-token", store.get("instance"))
        val raw = context.getSharedPreferences("axonhub_credentials", Context.MODE_PRIVATE).getString("instance", "")!!
        assertFalse(raw.contains("secret-token")); assertTrue(raw.length > 20)
        store.remove("instance"); assertThrows(Exception::class.java) { store.get("instance") }
    }
}
