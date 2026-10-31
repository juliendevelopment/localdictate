package be.arte.localdictate.audio

/**
 * Keeps the recent captured audio by absolute sample index and widens each VAD speech segment with a margin
 * before and after it. The VAD ends a segment exactly where silence starts, which clips soft word endings
 * and the first syllable after a pause. Margins never overlap the previous segment, so no word is decoded twice.
 */
class SegmentPadder(
    rate: Int = 16_000,
    private val before: Int = rate * 3 / 10,
    private val after: Int = rate * 3 / 10,
    capacity: Int = rate * 40,
) {
    init { require(before >= 0 && after >= 0 && capacity > 0) }
    private val ring = FloatArray(capacity)
    var written: Long = 0
        private set
    private var emittedEnd = 0L

    fun write(samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val position = (written % ring.size).toInt()
            val count = minOf(ring.size - position, samples.size - offset)
            samples.copyInto(ring, position, offset, offset + count)
            offset += count
            written += count
        }
    }

    /** Audio for the speech segment [start, start + length), widened by the margins that are still available. */
    fun widen(start: Long, length: Int): FloatArray {
        val from = maxOf(start - before, emittedEnd, written - ring.size, 0L)
        val to = minOf(start + length + after, written)
        if (to <= from) return FloatArray(0)
        emittedEnd = to
        val result = FloatArray((to - from).toInt())
        var index = from
        var offset = 0
        while (index < to) {
            val position = (index % ring.size).toInt()
            val count = minOf(ring.size - position, (to - index).toInt())
            ring.copyInto(result, offset, position, position + count)
            offset += count
            index += count
        }
        return result
    }
}
