package cc.khixang.axonhub.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.khixang.axonhub.AxonHubApplication
import cc.khixang.axonhub.MainActivity
import cc.khixang.axonhub.ThemeMode
import cc.khixang.axonhub.storage.InstanceStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Real MainActivity / real application state, with no backend, credentials, or fake dashboard data.
 * Run only on a fresh GitHub Actions emulator installation. This test deliberately refuses to
 * remove existing instances or clear application data.
 *
 * PNG artifacts are written by the target app to:
 * /sdcard/Android/data/cc.khixang.axonhub/files/screenshots/ios-ux-*.png
 * The emulator job must adb pull that directory before destroying the device, even on failure.
 *
 * Preferred stable production tags (text/accessible-label fallbacks remain for older UI):
 * axon_onboarding, axon_connect_instance, axon_instance_editor, axon_instance_cancel.
 * Additional useful field tags: axon_instance_name, axon_instance_url, axon_instance_secret.
 *
 * LIGHT/DARK are applied through the real SettingsManager because onboarding has no settings
 * navigation yet. These verify app appearance overrides, not a fake replacement composition or
 * Android's system night-mode setting. Each appearance runs in portrait and landscape.
 */
@RunWith(AndroidJUnit4::class)
class IosUxSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = compose.activity.application as AxonHubApplication

    @Test
    fun freshInstall_onboardingConnectionDismissal_lightDark_portraitLandscape() {
        // Read actual persisted state: never make a signed-in installation look like onboarding.
        val saved = runBlocking {
            withTimeout(10_000) { InstanceStore(instrumentation.targetContext).state.first() }
        }
        assertTrue("Use a fresh emulator installation; existing instances must not be erased", saved.instances.isEmpty())
        val originalSettings = app.settings.state.value
        val originalOrientation = compose.activity.requestedOrientation
        val luminance = mutableMapOf<String, Double>()

        try {
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                compose.runOnIdle { app.settings.update(app.settings.state.value.copy(theme = theme)) }
                compose.waitForIdle()
                assertEquals(theme, app.settings.state.value.theme)

                for ((name, requested, expected) in listOf(
                    Triple("portrait", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT),
                    Triple("landscape", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE),
                )) {
                    compose.runOnIdle { compose.activity.requestedOrientation = requested }
                    compose.waitUntil(timeoutMillis = 15_000) {
                        compose.activity.resources.configuration.orientation == expected
                    }
                    val prefix = "ios-ux-${theme.name.lowercase()}-$name"
                    assertOnboarding()
                    luminance["${theme.name}-$name"] = screenshot("$prefix-onboarding")

                    openConnectionEditor()
                    screenshot("$prefix-connect")
                    clickable(
                        tags = listOf("axon_instance_cancel", "instance_cancel", "connection_cancel"),
                        labels = listOf("Cancel", "Back", "取消", "返回"),
                    ).performClick()
                    assertOnboarding()
                    screenshot("$prefix-cancelled")

                    openConnectionEditor()
                    // No field is focused, so BACK dismisses the modal rather than only the IME.
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    assertOnboarding()
                    screenshot("$prefix-back")
                }
            }
            for (orientation in listOf("portrait", "landscape")) {
                assertTrue(
                    "Actual onboarding pixels must become darker in DARK mode ($orientation)",
                    luminance.getValue("LIGHT-$orientation") > luminance.getValue("DARK-$orientation") + 0.10,
                )
            }
            compose.runOnIdle {
                assertTrue("Opening or dismissing the form must not create an instance", app.repository.instances.value.isEmpty())
                assertTrue("No instance must be selected", app.repository.selectedId.value.isEmpty())
            }
        } catch (failure: Throwable) {
            runCatching { screenshot("ios-ux-failure") }
                .onFailure { Log.e("IosUxSmokeTest", "Unable to capture failure screenshot", it) }
            throw failure
        } finally {
            // Preserve caller settings; never clear preferences, credentials, or application data.
            instrumentation.runOnMainSync {
                app.settings.update(originalSettings)
                compose.activity.requestedOrientation = originalOrientation
            }
        }
    }

    private fun assertOnboarding() {
        compose.waitForIdle()
        assertRootHasContent()
        assertTrue("Welcome state must not show a mandatory login form", nodes(hasSetTextAction()).isEmpty())
        val welcome = hasTestTag("axon_onboarding") or hasTestTag("onboarding") or
            hasText("AxonHub", substring = true, ignoreCase = true) or
            hasText("Welcome", substring = true, ignoreCase = true) or
            hasText("欢迎", substring = true) or hasText("歡迎", substring = true)
        compose.onAllNodes(welcome, useUnmergedTree = true).onFirst().assertIsDisplayed()
        clickable(
            tags = listOf("axon_connect_instance", "onboarding_connect", "connect_instance"),
            labels = listOf("Connect instance", "Connect Instance", "Connect to AxonHub", "Connect", "Add instance", "连接实例", "連接實例", "连接 AxonHub", "连接", "連接"),
        ).assertIsDisplayed()
        assertFalse("Activity must remain alive after dismissing the connection form", compose.activity.isFinishing)
        assertFalse("Activity must not be destroyed", compose.activity.isDestroyed)
    }

    private fun openConnectionEditor() {
        clickable(
            tags = listOf("axon_connect_instance", "onboarding_connect", "connect_instance"),
            labels = listOf("Connect instance", "Connect Instance", "Connect to AxonHub", "Connect", "Add instance", "连接实例", "連接實例", "连接 AxonHub", "连接", "連接"),
        ).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { nodes(hasSetTextAction()).size >= 3 }
        assertRootHasContent()
        // Name, URL, and password/API key must be real editable Compose controls, not just text.
        assertTrue("Connection form must expose at least name, URL and secret inputs", nodes(hasSetTextAction()).size >= 3)
        compose.onAllNodes(hasSetTextAction()).onFirst().assertIsDisplayed()
        clickable(
            tags = listOf("axon_instance_cancel", "instance_cancel", "connection_cancel"),
            labels = listOf("Cancel", "Back", "取消", "返回"),
        ).assertIsDisplayed()
    }

    private fun clickable(tags: List<String>, labels: List<String>): androidx.compose.ui.test.SemanticsNodeInteraction {
        // Prefer stable semantics identifiers; translations are only a compatibility fallback.
        val tagged = tags.map(::hasTestTag).reduce { left, right -> left or right }
        if (nodes(tagged).isNotEmpty()) return compose.onAllNodes(tagged).onFirst()
        val descriptions = labels.map { hasContentDescription(it, ignoreCase = true) }
            .reduce { left, right -> left or right }
        val accessible = (descriptions or hasAnyDescendant(descriptions)) and hasClickAction()
        if (nodes(accessible).isNotEmpty()) return compose.onAllNodes(accessible).onFirst()
        val text = labels.map { hasText(it, ignoreCase = true) }.reduce { left, right -> left or right }
        return compose.onAllNodes((text or hasAnyDescendant(text)) and hasClickAction()).onFirst()
    }

    private fun nodes(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes()

    private fun assertRootHasContent() {
        val roots = nodes(isRoot())
        assertTrue("MainActivity must contain an actual rendered Compose root", roots.isNotEmpty())
        assertTrue("At least one Compose root must have visible bounds", roots.any {
            it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f
        })
    }

    /** Capture the actual screen including dialog/system bars; fail instead of fabricating images. */
    private fun screenshot(name: String): Double {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            instrumentation.uiAutomation.syncInputTransactions()
        }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("UiAutomation must return a real device screenshot", bitmap)
        val image = checkNotNull(bitmap)
        try {
            assertTrue("Device screenshot must have nonzero dimensions", image.width > 0 && image.height > 0)
            assertEquals("Screenshots must belong to the target app, not the test APK", "cc.khixang.axonhub", instrumentation.targetContext.packageName)
            val external = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)) {
                "Target app external files directory is unavailable"
            }
            val directory = File(external, "screenshots")
            assertTrue("Unable to create screenshots artifact directory", directory.isDirectory || directory.mkdirs())
            val destination = File(directory, "$name.png")
            FileOutputStream(destination).use { output ->
                assertTrue("PNG encoding failed", image.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.flush()
            }
            assertTrue("Screenshot artifact must exist and be nonempty", destination.isFile && destination.length() > 0)
            Log.i("IosUxSmokeTest", "SCREENSHOT ${destination.absolutePath} ${image.width}x${image.height}")
            // Sample the content area, excluding system bars; compare real rendered light/dark UI.
            var total = 0.0
            var samples = 0
            for (row in 2..17) for (column in 2..17) {
                val pixel = image.getPixel(image.width * column / 20, image.height * row / 20)
                total += (0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)) / 255.0
                samples++
            }
            return total / samples
        } finally {
            image.recycle()
        }
    }
}
