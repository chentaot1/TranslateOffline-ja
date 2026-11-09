package io.github.ilovephos.translate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.*
import kotlinx.coroutines.*

/** Local setup and reading preferences; the installer already contains every model. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var progress: ProgressBar
    private var prepared = false
    private val prefs by lazy { getSharedPreferences("reading", MODE_PRIVATE) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(15, 23, 36))
            addView(content)
        }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        setContentView(scroll)
        content.addView(text(getString(R.string.app_name), 30f))
        content.addView(text(BundledAssets.language.label, 18f))
        content.addView(text("All models are included. Recognition and translation stay on this phone. This app has no Internet permission.", 15f))
        content.addView(toggle("Continuous-scroll / webtoon layout", "strip", false))
        content.addView(toggle("Translate automatically after scrolling settles", "automatic", true))
        content.addView(text("When reading starts, a floating bar appears. Tap Translate to retry a page; hold Original to see the art. Drag the grip to move the controls. Use × to stop.", 15f))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(16)))
        status = text("Preparing bundled models…", 15f)
        content.addView(status)
        start = button("Start reading") { startReading() }.apply { isEnabled = false }
        content.addView(start)
        content.addView(button("Stop reading") {
            stopService(Intent(this, ReadingService::class.java))
            scope.launch {
                while (ReadingService.running) delay(50)
                if (prepared) {
                    start.isEnabled = true
                    status.text = "Reading stopped. Start when ready."
                }
            }
        })
        content.addView(button("Open-source licences") {
            val notice = assets.open("NOTICE.txt").bufferedReader().use { it.readText() }
            val licences = assets.list("licenses").orEmpty().joinToString("\n\n") { name ->
                assets.open("licenses/$name").bufferedReader().use { "$name\n${it.readText()}" }
            }
            val body = text("$notice\n\n$licences", 13f)
            AlertDialog.Builder(this).setTitle("Licences").setView(ScrollView(this).apply { addView(body) })
                .setPositiveButton("Close", null).show()
        })
        prepareModels()
    }

    private fun prepareModels() {
        scope.launch {
            try {
                BundledAssets.prepare(applicationContext) { percent ->
                    runOnUiThread {
                        progress.progress = percent
                        status.text = "Preparing bundled models locally: $percent%"
                    }
                }
                prepared = true
                progress.visibility = View.GONE
                status.text = "Ready. ${BundledAssets.language.label} is available offline."
                start.text = "Start reading"
                start.setOnClickListener { startReading() }
                start.isEnabled = !ReadingService.running
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = "Preparation failed: ${error.message}. Free storage if needed, then tap Retry."
                progress.visibility = View.GONE
                start.apply {
                    text = "Retry preparation"
                    isEnabled = true
                    setOnClickListener { isEnabled = false; progress.visibility = View.VISIBLE; prepareModels() }
                }
            }
        }
    }

    private fun startReading() {
        if (!prepared || ReadingService.running) return
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            status.text = "Allow display over other apps, then return and tap Start reading."
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notification_asked", false) && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("notification_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val capture = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else manager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(capture, 10)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9) startReading()
    }

    override fun onResume() {
        super.onResume()
        if (::start.isInitialized && prepared) {
            start.isEnabled = !ReadingService.running
            if (ReadingService.running) status.text = "Reading is active. Use the floating bar or Stop reading to end it."
        }
    }

    @Deprecated("Activity result is retained for this small platform Activity")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 10 && result == RESULT_OK && data != null) {
            val intent = Intent(this, ReadingService::class.java).apply {
                putExtra("capture", data)
                putExtra("strip", prefs.getBoolean("strip", false))
                putExtra("automatic", prefs.getBoolean("automatic", true))
            }
            startForegroundService(intent)
            status.text = "Reading started. Open your browser or manga reader."
        }
    }

    private fun toggle(label: String, key: String, default: Boolean) = Switch(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        minHeight = dp(64)
        isChecked = prefs.getBoolean(key, default)
        setOnCheckedChangeListener { _, value -> prefs.edit().putBoolean(key, value).apply() }
    }

    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(228, 237, 247))
        setPadding(0, dp(12), 0, dp(12))
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(56)
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(Color.rgb(35, 89, 145))
        setOnClickListener { click() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
