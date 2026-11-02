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
    /** Called right before the single commit: the editor's own reports must not contradict [context]. */
    fun canCommit(context: EditorSurrounding): Boolean = isCurrent()
    /** Blocking, bounded IPC; called once on IO, only for the explicitly captured target. */
    fun surrounding(): EditorSurrounding?
    fun commit(text: String)
    /** Non-content state of the editor, for refusal diagnostics. */
    fun describe(): String = ""
}

/**
 * [composing] means a composition exists whose position is not known, so a commit could replace it.
 * [composingStart]/[composingEnd] give a known keyboard composition (-1 when there is none).
 */
internal data class EditorSurrounding(
    val text: String,
    val start: Int,
    val end: Int,
    val offset: Int,
    val composing: Boolean,
    val composingStart: Int = -1,
    val composingEnd: Int = -1,
)

/** Editor-only target at START: no field text is kept between START and stop, only its hash. */
internal data class EditorStart(val contentHash: String, val length: Int, val start: Int, val end: Int, val offset: Int) {
    companion object {
        fun of(context: EditorSurrounding) = EditorStart(TargetPolicy.hash(context.text), context.text.length,
            context.start, context.end, context.offset)
    }
}

/** What to send with commitText, and what the field must contain afterwards. */
internal data class CommitPlan(val text: String, val expected: String, val cursor: Int)

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
    /** Non-content reason why the editor context cannot be trusted, or null when it matches the target. */
    fun mismatch(target: TargetState, identity: EditorIdentity, context: EditorSurrounding?): String? = when {
        context == null -> "contexte de l’éditeur illisible"
        context.composing -> "composition du clavier à une position inconnue"
        context.offset != 0 -> "contexte tronqué (décalage ${context.offset})"
        !sameEditor(target, identity) -> "éditeur différent du champ"
        target.length > MAX_FIELD_LENGTH -> "champ trop long"
        context.text.length != target.length -> "longueur différente (champ ${target.length}, éditeur ${context.text.length})"
        context.start != target.start || context.end != target.end ->
            "sélection différente (champ ${target.start}-${target.end}, éditeur ${context.start}-${context.end})"
        TargetPolicy.hash(context.text) != target.contentHash -> "texte différent entre le champ et l’éditeur"
        else -> null
    }
    /** Same editor field: restarted input on it is normal, another field (even in the same view) is not. */
    fun sameField(a: EditorIdentity, b: EditorIdentity): Boolean =
        a.packageName == b.packageName && a.fieldId == b.fieldId && a.fieldName == b.fieldName &&
            a.viewId == b.viewId && sameInputKind(a.inputType, b.inputType) && !TargetPolicy.password(b.inputType)
    /** Editor-only target: non-content reason why the field is not exactly as it was at START, or null. */
    fun changedSince(before: EditorStart, now: EditorSurrounding?): String? = when {
        now == null -> "contexte de l’éditeur illisible"
        now.composing -> "composition du clavier à une position inconnue"
        before.offset != 0 || now.offset != 0 -> "contexte tronqué (décalage ${before.offset}/${now.offset})"
        now.text.length > MAX_FIELD_LENGTH -> "champ trop long"
        now.text.length != before.length || TargetPolicy.hash(now.text) != before.contentHash ->
            "texte du champ modifié pendant la dictée (${before.length} → ${now.text.length} car.)"
        now.start != before.start || now.end != before.end ->
            "curseur déplacé pendant la dictée (${before.start}-${before.end} → ${now.start}-${now.end})"
        now.start != now.end -> "texte sélectionné dans le champ : rien n’est remplacé"
        else -> null
    }
    fun matches(target: TargetState, identity: EditorIdentity, context: EditorSurrounding?): Boolean =
        mismatch(target, identity, context) == null
    /**
     * Insert at a collapsed cursor without removing anything. commitText replaces the keyboard's composing
     * region, so a known composition around the cursor is re-sent with the dictation inside it: the
     * composed word stays intact and only the dictation is added. Null when that cannot be guaranteed.
     */
    fun plan(context: EditorSurrounding, transcript: String): CommitPlan? {
        val start = context.start
        val end = context.end
        if (start != end || start !in 0..context.text.length) return null
        val expected = TargetPolicy.insertedText(context.text, start, end, transcript)
        val composingStart = context.composingStart
        val composingEnd = context.composingEnd
        if (composingStart < 0 && composingEnd < 0) return CommitPlan(transcript, expected, start + transcript.length)
        if (composingStart < 0 || composingStart > start || end > composingEnd || composingEnd > context.text.length) return null
        val text = context.text.substring(composingStart, start) + transcript + context.text.substring(end, composingEnd)
        return CommitPlan(text, expected, composingStart + text.length)
    }
}
