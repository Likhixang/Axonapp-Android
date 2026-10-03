package cc.khixang.axonhub.ui

import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import cc.khixang.axonhub.AppSettings
import cc.khixang.axonhub.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35])
class BrandResourcesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `launcher stays white independently from the iOS accent`() {
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertEquals(Color.WHITE, (icon.background as ColorDrawable).color)
        assertNotEquals(AppSettings().accent.toInt(), (icon.background as ColorDrawable).color)
        assertNotNull(icon.foreground)
    }

    @Test fun `round icon has the same neutral adaptive background`() {
        val icon = context.getDrawable(R.mipmap.ic_launcher_round) as AdaptiveIconDrawable
        assertEquals(Color.WHITE, (icon.background as ColorDrawable).color)
    }

    @Test fun `display name is Axonapp and the upgrade package is preserved`() {
        assertEquals("Axonapp", context.getString(R.string.app_name))
        assertEquals("cc.khixang.axonhub", context.packageName)
    }

    @Test fun `all copied provider logos can be resolved locally`() {
        val files = context.assets.list("brand-icons").orEmpty()
        assertTrue(files.count { it.endsWith(".png") } >= 340)
        for (name in files.filter { it.endsWith(".png") }) {
            context.assets.open("brand-icons/$name").use { stream -> assertTrue(name, stream.read() >= 0) }
        }
    }
}
