package cc.khixang.axonhub.gateway

import android.content.Context
import cc.khixang.axonhub.core.obj
import cc.khixang.axonhub.data.AxonRepository
import cc.khixang.axonhub.network.AxonApi
import cc.khixang.axonhub.storage.InstanceStore
import cc.khixang.axonhub.storage.SecureCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY) @SQLiteMode(SQLiteMode.Mode.LEGACY) @ConscryptMode(ConscryptMode.Mode.OFF) @Config(sdk = [35])
class DirtyPatchTest {
    @Test fun `changed replacement objects are emitted whole`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val repo = AxonRepository(InstanceStore(context), SecureCredentialStore(context) { SecretKeySpec(ByteArray(32), "AES") }, AxonApi(), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val service = GatewayService(repo)
        val baseline = buildJsonObject { put("name", "old"); put("settings", buildJsonObject { put("a", 1); put("b", 2) }) }
        val candidate = buildJsonObject { put("name", "new"); put("settings", buildJsonObject { put("a", 1); put("b", 3) }) }
        val patch = service.dirtyPatch(candidate, baseline)
        assertEquals(setOf("name", "settings"), patch.keys); assertEquals(setOf("a", "b"), patch["settings"].obj.keys)
    }
}
