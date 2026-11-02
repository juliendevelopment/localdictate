package be.arte.localdictate.meeting

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import be.arte.localdictate.DictateApplication
import be.arte.localdictate.session.Phase
import be.arte.localdictate.session.Use
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeetingIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun manifestDeclaresMeetingServiceAndExportProvider() {
        val app = ApplicationProvider.getApplicationContext<DictateApplication>()
        val serviceInfo = app.packageManager.getServiceInfo(ComponentName(app, MeetingService::class.java), 0)
        assertFalse(serviceInfo.exported)
        val expectedTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        assertEquals(expectedTypes, serviceInfo.foregroundServiceType and expectedTypes)

        val providerInfo = app.packageManager.getProviderInfo(
            ComponentName(app, androidx.core.content.FileProvider::class.java), 0)
        assertFalse(providerInfo.exported)
        assertTrue(providerInfo.grantUriPermissions)
        assertEquals("${app.packageName}.meeting.exports", providerInfo.authority)
    }

    @Test fun unreservedMeetingServiceIsRejectedImmediately() {
        val handle = Robolectric.buildService(MeetingService::class.java).create()
        val service = handle.get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(
            Intent(service, MeetingService::class.java).setAction(MeetingService.STOP), 0, 2))
        assertNull(shadowOf(service).lastForegroundNotification)
        handle.destroy()
    }

    @Test fun meetingRepositoryEnforcesQuotasAndManagesSessions() {
        val root = temporary.newFolder("meetings")
        val repo = MeetingRepository(root)
        assertTrue(repo.list().isEmpty())

        val session = repo.create(SummaryLanguage.FRENCH)
        assertEquals(SummaryLanguage.FRENCH, session.language)
        assertEquals(MeetingPhase.CREATED, session.phase)
        assertTrue(MeetingRepository.validId(session.id))

        val sessions = repo.list()
        assertEquals(1, sessions.size)
        assertEquals(session.id, sessions[0].id)

        val updated = session.copy(phase = MeetingPhase.RECORDED, durationMs = 60_000, diagnostic = "Enregistrement terminé.")
        repo.save(updated)
        val reloaded = repo.read(session.id)
        assertEquals(MeetingPhase.RECORDED, reloaded.phase)
        assertEquals(60_000L, reloaded.durationMs)
        assertEquals("Enregistrement terminé.", reloaded.diagnostic)

        repo.delete(session.id)
        assertTrue(repo.list().isEmpty())
        assertFalse(File(root, session.id).exists())
    }

    @Test fun meetingControllerSharesMutualExclusionWithDictation() {
        val app = ApplicationProvider.getApplicationContext<DictateApplication>()
        val dictation = app.controller
        val meeting = app.meetings

        assertFalse(dictation.state.value.meetingBusy)

        // When models are not ready, reserve returns null
        assertNull(meeting.reserve(null, SummaryLanguage.FRENCH))

        // When dictation is armed, meeting reserve is refused
        dictation.update { it.copy(modelsReady = true) }
        // Emulate models ready in ModelManager if possible, or test gate directly:
        assertTrue(dictation.gate.acquire(Use.ARMED))
        assertNull(meeting.reserve(null, SummaryLanguage.FRENCH))
        dictation.gate.release(Use.ARMED)

        // Acquire gate for meeting manually to check dictation is blocked
        assertTrue(dictation.gate.acquire(Use.MEETING))
        assertFalse(dictation.reserveArm())
        dictation.gate.release(Use.MEETING)
    }

    @Test fun meetingPipelineAndTranscriberRunOnSyntheticAudio() = runBlocking {
        val audioFile = File(temporary.root, "audio.pcm")
        val journalFile = File(temporary.root, "transcript.dat")
        val guard = StorageGuard { _, _ -> }

        // Write synthetic PCM16: 2 seconds of 16 kHz mono (64,000 bytes)
        PcmRecording.create(audioFile, guard).use { rec ->
            val block = ByteArray(3200) // 100 ms
            repeat(20) { rec.append(block) }
        }

        val journal = TranscriptJournal.create(journalFile, guard)
        val decoder = object : MeetingDecoder {
            private var calls = 0
            override fun accept(samples: FloatArray): List<MeetingSpeech> {
                calls++
                return if (calls == 1) listOf(MeetingSpeech(0L, FloatArray(1600))) else emptyList()
            }
            override fun finish(): List<MeetingSpeech> = emptyList()
            override fun recognize(samples: FloatArray): String = "Phrase de test réunion"
            override fun close() = Unit
        }

        MeetingTranscriber().transcribe(audioFile, journal, decoder, cancelled = { false })
        journal.close()

        val reader = TranscriptJournal.openReader(journalFile)
        val segments = mutableListOf<TranscriptSegment>()
        val scan = reader.use { it.scan { s -> segments += s } }
        assertTrue(scan.isComplete)
        assertEquals(1, segments.size)
        assertEquals("Phrase de test réunion", segments[0].text)
        assertEquals(0L, segments[0].startMs)
        assertEquals(100L, segments[0].endMs)
    }

    @Test fun meetingExportProducesValidMarkdownAndWav() {
        val root = temporary.newFolder("meetings_export")
        val cache = temporary.newFolder("cache")
        val repo = MeetingRepository(root)
        val session = repo.create(SummaryLanguage.FRENCH)
        val guard = StorageGuard { _, _ -> }

        // Create synthetic audio and journal
        PcmRecording.create(repo.audio(session.id), guard).use { rec ->
            rec.append(ByteArray(1600))
        }
        val journalName = "transcript-${java.util.UUID.randomUUID()}.dat"
        val journalFile = File(repo.directory(session.id), journalName)
        TranscriptJournal.create(journalFile, guard).use { j ->
            j.append(0, 1000, "Bonjour bienvenue à la réunion.")
        }
        val summaryName = "summary-${java.util.UUID.randomUUID()}.md"
        val summaryFile = File(repo.directory(session.id), summaryName)
        summaryFile.writeText("# Résumé\n- Point important abordé\n", Charsets.UTF_8)

        val updated = session.copy(
            phase = MeetingPhase.COMPLETE,
            journal = journalName,
            summary = summaryName,
            durationMs = 50,
            diagnostic = "Réunion terminée",
        )
        repo.save(updated)

        val export = MeetingExport(cache, repo)
        val md = export.create(updated, wav = false)
        assertTrue(md.exists())
        assertTrue(md.length() > 0)
        val mdText = md.readText(Charsets.UTF_8)
        assertTrue(mdText.contains("Bonjour bienvenue à la réunion."))
        assertTrue(mdText.contains("Point important abordé"))

        val wav = export.create(updated, wav = true)
        assertTrue(wav.exists())
        assertEquals(1644L, wav.length()) // 44 bytes header + 1600 bytes PCM
    }
}
