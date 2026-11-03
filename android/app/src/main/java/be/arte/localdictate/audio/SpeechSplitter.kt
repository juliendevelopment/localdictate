package be.arte.localdictate.audio

/**
 * Silero's maxSpeechDuration is not a hard limit: on continuous speech (a speakerphone call, two voices without a
 * 0.4 s pause) sherpa-onnx 2.0.1 returned a 23 s segment with a 20 s maximum, and the meeting transcription
 * failed on it. Longer segments are cut at the quietest [window] within the last [search] samples before each
 * limit, so the recognizer never receives more than [maxLength] samples of speech. The pieces are contiguous:
 * no sample is dropped or decoded twice.
 */
object SpeechSplitter {
    fun lengths(samples: FloatArray, count: Int = samples.size, maxLength: Int = 20 * 16_000,
        search: Int = 5 * 16_000, window: Int = 1_600): List<Int> {
        require(count in 0..samples.size && window > 0 && search >= window && maxLength > search)
        val result = mutableListOf<Int>()
        var position = 0
        while (count - position > maxLength) {
            val limit = position + maxLength
            var best = limit - window
            var bestEnergy = Double.MAX_VALUE
            var start = limit - search
            while (start <= limit - window) {
                var energy = 0.0
                for (i in start until start + window) energy += samples[i] * samples[i]
                if (energy < bestEnergy) { bestEnergy = energy; best = start }
                start += window / 2
            }
            val cut = best + window / 2
            result += cut - position
            position = cut
        }
        if (count > position) result += count - position
        return result
    }
}
