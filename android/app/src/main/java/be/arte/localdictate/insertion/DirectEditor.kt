package be.arte.localdictate.insertion

/** No API33 platform types cross this boundary. All acquisition/current checks/commits run on main. */
internal fun interface DirectEditor {
    fun acquire(): DirectEditorSession?
}

internal data class EditorIdentity(val packageName: String, val fieldId: Int, val fieldName: String?, val inputType: Int,
    val viewId: String? = null)

internal interface DirectEditorSession {
    val identity: EditorIdentity
    fun isCurrent(): Boolean
    fun canCommit(start: Int, end: Int): Boolean = isCurrent()
    /** Blocking, bounded IPC; called once on IO, only for the explicitly captured target. */
    fun surrounding(): EditorSurrounding?
    fun commit(text: String)
}

internal data class EditorSurrounding(
    val text: String,
    val start: Int,
    val end: Int,
    val offset: Int,
    val composing: Boolean,
)

internal object DirectContextPolicy {
    const val MAX_FIELD_LENGTH = 32_768
    /** Class and variation decide the kind of field; editors may add flag bits the view does not report. */
    private fun sameInputKind(a: Int, b: Int): Boolean {
        val mask = android.text.InputType.TYPE_MASK_CLASS or android.text.InputType.TYPE_MASK_VARIATION
        return a and mask == b and mask
    }
    fun sameEditor(target: TargetState, identity: EditorIdentity): Boolean =
        identity.packageName == target.packageName && !TargetPolicy.password(identity.inputType) &&
            (target.inputType == 0 || sameInputKind(target.inputType, identity.inputType)) &&
            (identity.viewId == null || target.viewId == null || identity.viewId == target.viewId)
    fun matches(target: TargetState, identity: EditorIdentity, context: EditorSurrounding?): Boolean =
        context != null && !context.composing && context.offset == 0 &&
            sameEditor(target, identity) &&
            target.length <= MAX_FIELD_LENGTH && context.text.length == target.length &&
            context.start == target.start && context.end == target.end &&
            TargetPolicy.hash(context.text) == target.contentHash
}
