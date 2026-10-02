package cc.khixang.axonhub.ui

import android.view.ViewGroup
import cc.khixang.axonhub.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY) @SQLiteMode(SQLiteMode.Mode.LEGACY) @ConscryptMode(ConscryptMode.Mode.OFF) @Config(sdk = [35])
class MainActivitySmokeTest {
    @Test fun `empty installation composes onboarding root without crashing`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertTrue(content.childCount > 0)
    }
}
