package be.arte.localdictate.insertion

import android.text.InputType
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
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val inputType: Int = 0,
)
object TargetPolicy {
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun password(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }
    fun eligible(target: TargetState, ownPackage: String): Boolean =
        target.packageName.isNotBlank() && target.packageName != ownPackage &&
            target.editable && target.visible && target.enabled && !target.password && target.focused &&
            target.start in 0..target.length && target.end in target.start..target.length
    fun unchanged(original: TargetState, current: TargetState, ownPackage: String): Boolean =
        eligible(original, ownPackage) && eligible(current, ownPackage) && original == current
    fun insertedText(original: String, start: Int, end: Int, transcript: String): String {
        require(start in 0..original.length && end in start..original.length)
        return original.substring(0, start) + transcript + original.substring(end)
    }
}
