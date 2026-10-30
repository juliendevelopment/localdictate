package be.arte.localdictate.insertion

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
    @Test fun explicitCopyReplacesClipAndMarksItSensitiveWithoutAnAccessSecurityPromise() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        PrivateClipboard(context).put("Privé")
        val system = context.getSystemService(ClipboardManager::class.java)
        assertEquals("Privé", system.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(system.primaryClip!!.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
    }
}
