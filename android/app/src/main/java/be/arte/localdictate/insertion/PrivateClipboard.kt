package be.arte.localdictate.insertion

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import java.util.UUID

class PrivateClipboard(context: Context) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    fun put(text: String): String {
        val token = "LocalDictate:" + UUID.randomUUID().toString()
        clipboard.setPrimaryClip(ClipData.newPlainText(token, text).apply {
            description.extras = PersistableBundle().apply {
                // String constant also works on API 29–32 (EXTRA_IS_SENSITIVE added in 33).
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        })
        return token
    }
    fun owns(token: String, text: String): Boolean {
        val clip = clipboard.primaryClip ?: return false
        return ClipboardOwnership.owns(token, text, clip.description.label?.toString(),
            if (clip.itemCount == 1) clip.getItemAt(0).text?.toString() else null, clip.itemCount)
    }
    fun clearIfOwned(token: String, text: String) {
        if (owns(token, text)) clipboard.clearPrimaryClip()
    }
}
