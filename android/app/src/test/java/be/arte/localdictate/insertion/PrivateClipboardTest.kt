package be.arte.localdictate.insertion

import android.content.ClipData
import android.content.ClipboardManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivateClipboardTest {
    @Test fun sensitiveEntryIsRemovedOnlyWhileStillOwned() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val system = context.getSystemService(ClipboardManager::class.java)
        val clipboard = PrivateClipboard(context)
        val token = clipboard.put("Privé")
        assertTrue(system.primaryClip!!.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        assertTrue(clipboard.owns(token, "Privé"))
        system.setPrimaryClip(ClipData.newPlainText("Copie utilisateur", "Autre"))
        clipboard.clearIfOwned(token, "Privé")
        assertEquals("Autre", system.primaryClip!!.getItemAt(0).text.toString())
        val next = clipboard.put("Privé")
        clipboard.clearIfOwned(next, "Privé")
        assertFalse(system.hasPrimaryClip())
    }
}
