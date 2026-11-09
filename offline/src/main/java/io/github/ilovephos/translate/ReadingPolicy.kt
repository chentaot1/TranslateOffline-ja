package io.github.ilovephos.translate

import java.util.concurrent.atomic.AtomicLong

/** Submits a visually settled source page once; minor capture noise never restarts the waiting interval. */
class ReadingGate(private val settleMs: Long = 900) {
    private var reference: ScreenSample? = null
    private var since = 0L
    private var submitted = false

    fun observe(frame: ScreenSample, now: Long): Boolean {
        if (reference?.matches(frame) != true) {
            reference = frame
            since = now
            submitted = false
            return false
        }
        if (submitted || now - since < settleMs) return false
        submitted = true
        return true
    }

    fun reset() { reference = null; submitted = false }
}

/** Invalidates pending UI callbacks when a gesture, capture, or stop supersedes them. */
class RenderGeneration {
    private val sequence = AtomicLong()
    @Volatile private var stopped = false

    fun advance(): Long { stopped = false; return sequence.incrementAndGet() }
    fun accepts(value: Long): Boolean = !stopped && sequence.get() == value
    fun stop() { stopped = true; sequence.incrementAndGet() }
}

