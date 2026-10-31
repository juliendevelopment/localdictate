package be.arte.localdictate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import be.arte.localdictate.session.Phase
import be.arte.localdictate.session.WeekStats
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Before
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
    private val controller get() = (compose.activity.application as DictateApplication).controller
    @Before fun cleanState() {
        compose.runOnIdle {
            controller.journal.clear()
            controller.update { it.copy(transcript = "", error = null, phase = Phase.OFF, preparing = false) }
        }
    }
    @Test fun launchHasNoAutomaticDownloadMicrophoneOrService() {
        compose.onNodeWithText("LocalDictate").assertIsDisplayed()
        compose.onNodeWithText("Jusqu’à 15 minutes").assertIsDisplayed()
        compose.onNodeWithTag("arm").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertNull(shadowOf(compose.activity).nextStartedService)
            assertFalse(controller.state.value.preparing)
            assertEquals(Phase.OFF, controller.state.value.phase)
            assertEquals(PackageManager.PERMISSION_DENIED, compose.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            assertFalse(java.io.File(compose.activity.filesDir, "models/downloads").exists())
        }
    }
    @Test fun meetingIsHonestAndNeverStartsAService() {
        compose.onNodeWithTag("mode-meeting").performClick()
        compose.onNodeWithText("Jusqu’à 2 heures").assertIsDisplayed()
        compose.onNodeWithTag("meeting-unavailable").assertIsDisplayed()
        compose.onNodeWithText("Aucun collage automatique", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("arm").assertDoesNotExist()
        compose.runOnIdle { assertNull(shadowOf(compose.activity).nextStartedService) }
    }
    @Test fun selectedModeSurvivesRecreationWithoutArming() {
        compose.onNodeWithTag("mode-meeting").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Jusqu’à 2 heures").assertIsDisplayed()
        compose.runOnIdle { assertNull(shadowOf(compose.activity).nextStartedService) }
    }
    @Test fun accessibilityExplainsBroadPermissionBeforeOpeningSettings() {
        compose.onNodeWithTag("setup-accessibility").performScrollTo().performClick()
        compose.onNodeWithText("Android autorise un accès large", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("accessibility-settings").performClick()
        compose.runOnIdle {
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(compose.activity).nextStartedActivity.action)
            assertNull(shadowOf(compose.activity).nextStartedService)
        }
    }
    @Test fun overlayLinkTargetsThisAppAndDoesNotArmOnReturn() {
        compose.onNodeWithTag("setup-overlay").performScrollTo().performClick()
        compose.runOnIdle {
            val intent = shadowOf(compose.activity).nextStartedActivity
            assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, intent.action)
            assertEquals("package:${compose.activity.packageName}", intent.data.toString())
        }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { assertNull(shadowOf(compose.activity).nextStartedService) }
        compose.onNodeWithTag("arm").performScrollTo().assertIsNotEnabled()
    }
    @Test fun microphoneRequestIsUserDrivenAndPermissionReturnCannotStartCapture() {
        compose.onNodeWithTag("setup-microphone").performScrollTo().performClick()
        compose.runOnIdle {
            assertArrayEquals(arrayOf(Manifest.permission.RECORD_AUDIO), shadowOf(compose.activity).lastRequestedPermission.requestedPermissions)
            shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle {
            assertEquals(Phase.OFF, controller.state.value.phase)
            assertNull(shadowOf(compose.activity).nextStartedService)
        }
        compose.onNodeWithTag("arm").performScrollTo().assertIsNotEnabled()
    }
    @Test fun recoveryIsVisibleAndRecreationDoesNotDuplicateInsertion() {
        compose.runOnIdle {
            val id = controller.journal.begin().id
            val recovered = controller.journal.append(id, "Texte récupéré FR/EN")
            controller.update { it.copy(transcript = recovered.text,
                message = "Dictée interrompue : copie manuelle disponible.") }
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("transcript").performScrollTo().assertTextEquals("Texte récupéré FR/EN")
        compose.onNodeWithTag("copy").assertIsEnabled()
        compose.runOnIdle {
            assertNull(shadowOf(compose.activity).nextStartedService)
            assertFalse(controller.journal.read().insertionAttempted)
            val vm = ViewModelProvider(compose.activity,
                DictationViewModel.Factory(compose.activity.application))[DictationViewModel::class.java]
            assertSame(controller, vm.controller)
        }
    }
    @Test fun missingPermissionsAndProcessingGateRecordingAndPreparation() {
        compose.runOnIdle { controller.update { it.copy(phase = Phase.PROCESSING) } }
        compose.onNodeWithTag("prepare").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("arm").assertDoesNotExist()
        compose.onNodeWithTag("stop").assertDoesNotExist()
        compose.onNodeWithTag("disarm").performScrollTo().assertIsEnabled()
    }
    @Test fun weeklyStatsAreShownAndCanBeCleared() {
        compose.runOnIdle {
            controller.statsStore.clear()
            controller.update { it.copy(stats = emptyList()) }
            controller.count("Bonjour tout le monde", 90_000)
        }
        compose.onNodeWithText("Statistiques par semaine").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("stats-0").assertTextContains("4 mots • 1 min • 1 dictée", substring = true)
        compose.onNodeWithTag("stats-clear").performScrollTo().performClick()
        compose.onNodeWithTag("stats-0").assertDoesNotExist()
        compose.runOnIdle { assertTrue(controller.statsStore.read().isEmpty()) }
    }
    @Test fun weekLineFormatsLongTotals() {
        assertEquals("Semaine du 26 oct. : 12\u202f345 mots • 2 h 05 • 31 dictées",
            weekLine(WeekStats(LocalDate.of(2026, 10, 26), 12_345, 125 * 60_000L, 31)))
    }
}
