package io.github.ilovephos.translate

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Verified, resumable preparation from APK assets, with no downloader or network dependencies. */
object BundledAssets {
    val language = SourceLanguage.valueOf(BuildConfig.SOURCE_LANGUAGE)

    fun directory(context: Context): File = File(context.noBackupFilesDir, "models")

    suspend fun prepare(context: Context, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val manifest = context.assets.open("model-manifest.json").use { it.readBytes() }
        val identity = MessageDigest.getInstance("SHA-256").digest(manifest)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val description = JSONObject(String(manifest, Charsets.UTF_8))
        require(description.getString("sourceLanguage") == language.name) { "Installer language does not match its models" }
        val array = description.getJSONArray("assets")
        val entries = (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            AssetEntry(entry.getString("name"), entry.getLong("bytes"), entry.getString("sha256"))
        }
        require(language.accepts(entries)) {
            "Installer is missing required models"
        }
        val root = directory(context)
        AssetInstaller(
            root, entries, identity,
            openAsset = { context.assets.open("models/$it") },
            availableBytes = { StatFs(root.path).availableBytes },
            retiredNames = language.retiredAssets
        ).prepare(progress)
    }
}
