package com.virtualcam.camspoof

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.virtualcam.camspoof.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val configDir = File("/sdcard/CamSpoof")
    private val configFile = File("/sdcard/CamSpoof/config.txt")

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val dest = copyToSpoofDir(uri, "spoof_photo")
        if (dest != null) {
            writeConfig("photo", dest)
            toast("✓ Foto aktif: ${dest.name}")
            updateStatus()
        }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val dest = copyToSpoofDir(uri, "spoof_video")
        if (dest != null) {
            writeConfig("video", dest)
            toast("✓ Video aktif: ${dest.name}")
            updateStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configDir.mkdirs()
        checkPermissions()
        updateStatus()

        binding.btnPhoto.setOnClickListener { pickPhoto.launch("image/*") }
        binding.btnVideo.setOnClickListener { pickVideo.launch("video/*") }
        binding.btnReal.setOnClickListener {
            writeConfig("real", null)
            toast("✓ Kamera asli aktif")
            updateStatus()
        }
    }

    private fun updateStatus() {
        val (mode, path) = CamSpoofHook.readConfig()
        val (icon, label) = when (mode) {
            "photo" -> "🖼️" to "Photo: ${File(path).name}"
            "video" -> "🎬" to "Video: ${File(path).name}"
            else    -> "📷" to "Real Camera"
        }
        binding.tvStatus.text = "$icon $label"

        listOf(binding.btnReal, binding.btnPhoto, binding.btnVideo).forEach { it.alpha = 0.5f }
        when (mode) {
            "real"  -> binding.btnReal.alpha  = 1f
            "photo" -> binding.btnPhoto.alpha = 1f
            "video" -> binding.btnVideo.alpha = 1f
        }
    }

    private fun writeConfig(mode: String, file: File?) {
        configFile.writeText("mode=$mode\npath=${file?.absolutePath ?: ""}")
    }

    private fun copyToSpoofDir(uri: Uri, prefix: String): File? {
        return try {
            val ext = getExtension(uri)
            val dest = File(configDir, "$prefix.$ext")
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest
        } catch (e: Exception) {
            toast("Gagal copy file: ${e.message}")
            null
        }
    }

    private fun getExtension(uri: Uri): String {
        var name = "bin"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) {
                name = cursor.getString(idx).substringAfterLast('.', "bin")
            }
        }
        return name
    }

    private fun checkPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
