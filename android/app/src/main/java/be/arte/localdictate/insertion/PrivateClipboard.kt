package be.arte.localdictate.insertion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/** Used only by the explicit foreground Copier action. No ownership reads, clearing or restoration. */
class PrivateClipboard(context: Context) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    fun put(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("LocalDictate", text).apply {
            description.extras = PersistableBundle().apply {
                // The sensitive hint masks previews; it is not access control.
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        })
    }
}
