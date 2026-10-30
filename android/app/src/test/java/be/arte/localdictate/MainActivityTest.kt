package be.arte.localdictate

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launcherShowsDictationAndHonestDevelopmentStatus() {
        compose.onNodeWithText("LocalDictate").assertIsDisplayed()
        compose.onNodeWithText("Jusqu’à 15 minutes").assertIsDisplayed()
        compose.onNodeWithText("Langues prévues : français et anglais").assertIsDisplayed()
        compose.onNodeWithTag("recording-unavailable").assertIsNotEnabled()
        compose.runOnIdle {
            assertNull(shadowOf(compose.activity).nextStartedService)
            assertEquals(PackageManager.PERMISSION_DENIED,
                compose.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            val requested = compose.activity.packageManager.getPackageInfo(
                compose.activity.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            assertFalse(requested.contains(Manifest.permission.INTERNET))
            assertFalse(requested.contains(Manifest.permission.SYSTEM_ALERT_WINDOW))
        }
    }
    @Test fun modesAreDistinctAndMeetingDoesNotPromiseInsertion() {
        compose.onNodeWithTag("mode-meeting").performClick()
        compose.onNodeWithText("Jusqu’à 2 heures").assertIsDisplayed()
        compose.onNodeWithText("Aucun collage automatique", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("mode-dictation").performClick()
        compose.onNodeWithText("Jusqu’à 15 minutes").assertIsDisplayed()
    }
    @Test fun selectedModeSurvivesActivityRecreation() {
        compose.onNodeWithTag("mode-meeting").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Jusqu’à 2 heures").assertIsDisplayed()
        compose.onNodeWithTag("recording-unavailable").assertIsNotEnabled()
    }
}
