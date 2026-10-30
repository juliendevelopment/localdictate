package be.arte.localdictate.insertion

import java.security.MessageDigest

data class TargetState(
    val packageName: String,
    val windowId: Int,
    val viewId: String?,
    val className: String,
    val contentHash: String,
    val length: Int,
    val start: Int,
    val end: Int,
    val editable: Boolean,
    val password: Boolean,
    val focused: Boolean,
    val pasteSupported: Boolean,
    val visible: Boolean = true,
    val enabled: Boolean = true,
)
object TargetPolicy {
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun eligible(target: TargetState, ownPackage: String): Boolean =
        target.packageName.isNotBlank() && target.packageName != ownPackage &&
            target.editable && target.visible && target.enabled && !target.password && target.focused && target.pasteSupported &&
            target.start in 0..target.length && target.end in target.start..target.length
    fun unchanged(original: TargetState, current: TargetState, ownPackage: String): Boolean =
        eligible(original, ownPackage) && eligible(current, ownPackage) && original == current
    fun insertedText(original: String, start: Int, end: Int, transcript: String): String {
        require(start in 0..original.length && end in start..original.length)
        return original.substring(0, start) + transcript + original.substring(end)
    }
}

/** A clipboard write is owned only while its unguessable label AND payload still match. */
object ClipboardOwnership {
    fun owns(token: String, text: String, currentLabel: String?, currentText: String?, itemCount: Int): Boolean =
        itemCount == 1 && currentLabel == token && currentText == text
}
