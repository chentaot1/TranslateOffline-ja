package io.github.ilovephos.translate

import android.app.*
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import com.yomu.pipeline.typesetting.TypesetBubble
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors

/** Foreground reading session; all UI changes and capture access stay on the main thread. */
class ReadingService : Service() {
    companion object {
        @Volatile var running = false
            private set
        private val engineThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private const val CHANNEL = "offline-reading"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generations = RenderGeneration()
    private val gate = ReadingGate()
    private val scene = SceneGuard()
    private val sampler by lazy { ScreenSampler(this) }
    private var transitioning = false
    private var displayRevision = 0L
    private val snapshotLock = Mutex()
    private var screenOffRegistered = false
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stopSelf() }
    }
    private var capture: ProjectionCapture? = null
    private var overlay: ReadingOverlay? = null
    private var session: ReadingSession? = null
    private var translationJob: Job? = null
    private var automatic = true
    private var ready = false
    private val recall = object : LinkedHashMap<Long, ReadingPage>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ReadingPage>?): Boolean = size > 6
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        if (intent == null || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("capture", Intent::class.java)
            else intent.getParcelableExtra("capture")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        running = true
        foreground()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
        screenOffRegistered = true
        automatic = intent.getBooleanExtra("automatic", true)
        val source = BundledAssets.language
        val strip = intent.getBooleanExtra("strip", false)
        try {
            overlay = ReadingOverlay(
                this,
                source == SourceLanguage.JAPANESE,
                ::manual,
                ::toggleOcr,
                ::movement,
                { displayRevision++ }
            ) { stopSelf() }.also { it.showControls() }
            capture = ProjectionCapture(this, data, ::movement) { stopSelf() }
            session = ReadingSession(applicationContext, source, strip)
        } catch (error: Exception) {
            overlay?.message("Could not start reading: ${error.message}")
            scope.launch { delay(2500); stopSelf() }
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                overlay?.message("Loading ${source.label}…")
                withContext(engineThread) { session?.load() }
                ready = true
                overlay?.message(if (automatic) "Open manga; scroll and pause to translate" else "Open manga, then tap Translate")
                watch()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                overlay?.message("Could not load models: ${error.message}")
            }
        }
        return START_NOT_STICKY
    }

    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Offline manga reading", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, ReadingService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("${getString(R.string.app_name)} is reading")
            .setContentText("On-device screen translation • tap Stop to end")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        ServiceCompat.startForeground(this, 101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private suspend fun watch() {
        while (currentCoroutineContext().isActive) {
            delay(250)
            if (!ready || transitioning) continue
            val revision = displayRevision
            val frame = snapshot() ?: continue
            if (transitioning || revision != displayRevision) { frame.recycle(); continue }
            val sample = sampler.sample(frame, overlay?.controlBounds() ?: RectF())
            if (scene.changed(sample)) {
                frame.recycle()
                invalidatePage("confirmed_visible_page_change")
                continue
            }
            if (translationJob?.isActive == true || overlay?.hasLetters == true || !automatic) {
                frame.recycle()
                continue
            }
            if (gate.observe(sample, SystemClock.elapsedRealtime())) submit(frame, sample) else frame.recycle()
        }
    }

    private suspend fun snapshot(): Bitmap? = snapshotLock.withLock {
        val activeCapture = capture ?: return@withLock null
        val activeOverlay = overlay ?: return@withLock null
        activeOverlay.awaitDraw()
        activeCapture.discardPending()
        activeOverlay.pulseAndWait()
        activeCapture.frame(500)
    }

    private fun movement() = invalidatePage("gesture_or_viewport_change")

    private fun invalidatePage(reason: String) {
        if (!ready) return
        Log.d("ReadingMotion", "Clearing page: $reason")
        generations.advance()
        displayRevision++
        session?.abort()
        translationJob?.cancel()
        overlay?.clear()
        gate.reset()
        scene.reset()
        overlay?.message(if (automatic) "Waiting for the page to settle…" else "Tap Translate when the page is ready")
    }

    private fun toggleOcr(): String {
        val s = session ?: return "NAR"
        val newMode = s.toggleOcrMode()
        recall.clear()
        invalidatePage("ocr_mode_changed")
        overlay?.message("OCR: ${newMode.label}")
        return newMode.shortLabel
    }

    private fun manual() {
        if (!ready) { overlay?.message("Models are still loading…"); return }
        movement()
        translationJob = scope.launch {
            val frame = snapshot()
            if (frame == null) overlay?.message("No screen frame. Stop and start reading again.")
            else submit(frame, sampler.sample(frame, overlay?.controlBounds() ?: RectF()))
        }
    }

    private fun submit(frame: Bitmap, source: ScreenSample) {
        val currentOverlay = overlay ?: run { frame.recycle(); return }
        val active = session ?: run { frame.recycle(); return }
        val ticket = generations.advance()
        val ignored = currentOverlay.controlBounds()
        val width = frame.width
        val height = frame.height
        val key = source.cacheKey
        scene.expect(source)
        translationJob = scope.launch {
            try {
                val cached = recall[key]
                val page = cached ?: withContext(engineThread) {
                    currentCoroutineContext().ensureActive()
                    active.read(frame, ignored,
                        status = { value -> scope.launch { if (generations.accepts(ticket)) overlay?.message(value) } }
                    )
                }
                if (generations.accepts(ticket)) {
                    while (currentOverlay.originalHeld && generations.accepts(ticket)) delay(50)
                    currentCoroutineContext().ensureActive()
                    transitioning = true
                    displayRevision++
                    val validation = snapshot()
                    val samePage = validation?.let { source.matches(sampler.sample(it, currentOverlay.controlBounds())) } == true
                    validation?.recycle()
                    if (!samePage || !generations.accepts(ticket)) {
                        invalidatePage("source_changed_before_display")
                        return@launch
                    }
                    currentOverlay.show(page.bubbles, width, height)
                    if (page.bubbles.isNotEmpty()) recall[key] = page
                    val ocrTag = if (session != null && BundledAssets.language == SourceLanguage.JAPANESE) " [${session?.ocrMode?.shortLabel}]" else ""
                    currentOverlay.message(if (page.bubbles.isEmpty()) "No readable bubbles found. Try zooming or moving the page."
                        else "${page.bubbles.size}/${page.total} bubbles$ocrTag • ${"%.1f".format(page.elapsedMs / 1000.0)} s")
                    currentOverlay.awaitDraw()
                    val painted = currentOverlay.translationBounds()
                    scene.ignoreTranslations(painted)
                    val displayed = snapshot()
                    // Retain the original baseline; captured English must never become a new source page.
                    val stayed = displayed?.let { source.matches(sampler.sample(it, currentOverlay.controlBounds()), painted) } == true
                    displayed?.recycle()
                    if (!stayed) {
                        invalidatePage("source_changed_outside_lettering")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generations.accepts(ticket)) {
                    currentOverlay.clear()
                    currentOverlay.message(error.message ?: "Translation failed. Tap Translate to retry.")
                }
            } finally { displayRevision++; transitioning = false; frame.recycle() }
        }
    }

    override fun onDestroy() {
        running = false
        ready = false
        generations.stop()
        session?.abort()
        scope.cancel()
        capture?.close()
        overlay?.close()
        val previous = session
        session = null
        CoroutineScope(engineThread).launch { previous?.close() }
        recall.clear()
        if (screenOffRegistered) { unregisterReceiver(screenOff); screenOffRegistered = false }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
