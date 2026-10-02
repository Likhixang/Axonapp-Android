package cc.khixang.axonhub.data

import android.content.Context
import cc.khixang.axonhub.core.TargetFence
import cc.khixang.axonhub.network.AxonApi
import cc.khixang.axonhub.network.AxonException
import cc.khixang.axonhub.storage.InstanceStore
import cc.khixang.axonhub.storage.SecureCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY) @SQLiteMode(SQLiteMode.Mode.LEGACY) @ConscryptMode(ConscryptMode.Mode.OFF) @Config(sdk = [35])
class InstanceFenceTest {
    @Test fun `project changes invalidate in flight tickets`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val repository = AxonRepository(InstanceStore(context), SecureCredentialStore(context) { SecretKeySpec(ByteArray(32), "AES") }, AxonApi(), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val old = TargetFence("", null, 1)
        repository.verify(old)
        repository.selectProject("project-2")
        assertThrows(AxonException.TargetChanged::class.java) { repository.verify(old) }
    }
}
