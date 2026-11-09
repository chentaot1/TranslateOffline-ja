package io.github.ilovephos.translate

/** Watches the original page, excluding our lettering instead of treating captured English as a new source. */
class SceneGuard {
    private var reference: ScreenSample? = null
    private var painted: List<SampleRegion> = emptyList()
    private var changedFrames = 0

    fun expect(source: ScreenSample) {
        reference = source
        painted = emptyList()
        changedFrames = 0
    }

    fun ignoreTranslations(regions: List<SampleRegion>) {
        painted = regions.toList()
        changedFrames = 0
    }

    /** An isolated compositor frame cannot remove finished lettering; actual gestures invalidate immediately. */
    fun changed(frame: ScreenSample): Boolean {
        val source = reference ?: return false
        changedFrames = if (source.matches(frame, painted)) 0 else changedFrames + 1
        return changedFrames >= 2
    }

    fun reset() {
        reference = null
        painted = emptyList()
        changedFrames = 0
    }
}
