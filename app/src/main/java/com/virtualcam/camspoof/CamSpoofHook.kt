package com.virtualcam.camspoof

import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.view.Surface
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * Xposed Module entry point.
 *
 * Hook target:
 * - CameraManager.openCamera() → intercept dan inject FakeCameraDevice
 * - CameraDevice.createCaptureSession() → feed surface dengan foto/video
 *
 * Config disimpan di /sdcard/CamSpoof/config.txt
 * Format:
 *   mode=photo
 *   path=/sdcard/CamSpoof/spoof.jpg
 * atau:
 *   mode=video
 *   path=/sdcard/CamSpoof/spoof.mp4
 */
class CamSpoofHook : IXposedHookLoadPackage {

    companion object {
        const val CONFIG_DIR = "/sdcard/CamSpoof"
        const val CONFIG_FILE = "$CONFIG_DIR/config.txt"

        fun readConfig(): Pair<String, String> {
            return try {
                val lines = File(CONFIG_FILE).readLines()
                val mode = lines.find { it.startsWith("mode=") }?.removePrefix("mode=") ?: "real"
                val path = lines.find { it.startsWith("path=") }?.removePrefix("path=") ?: ""
                Pair(mode, path)
            } catch (e: Exception) {
                Pair("real", "")
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook berlaku untuk semua app yang jalan di VirtualXposed
        // Kecuali module kita sendiri
        if (lpparam.packageName == "com.virtualcam.camspoof") return

        hookCameraOpen(lpparam)
    }

    private fun hookCameraOpen(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook CameraManager.openCamera()
            XposedHelpers.findAndHookMethod(
                CameraManager::class.java,
                "openCamera",
                String::class.java,          // cameraId
                CameraDevice.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val (mode, path) = readConfig()
                        if (mode == "real" || path.isEmpty()) return

                        // Replace callback dengan versi kita
                        val originalCallback = param.args[1] as CameraDevice.StateCallback
                        param.args[1] = SpoofCameraCallback(originalCallback, mode, path)

                        XposedBridge.log("CamSpoof: intercepted openCamera, mode=$mode")
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("CamSpoof: hook failed: ${e.message}")
        }
    }
}

/**
 * Intercept onOpened() — inject SpoofCameraDevice
 */
class SpoofCameraCallback(
    private val real: CameraDevice.StateCallback,
    private val mode: String,
    private val path: String
) : CameraDevice.StateCallback() {

    override fun onOpened(camera: CameraDevice) {
        // Wrap dengan spoof device
        real.onOpened(SpoofCameraDevice(camera, mode, path))
    }

    override fun onDisconnected(camera: CameraDevice) = real.onDisconnected(camera)
    override fun onError(camera: CameraDevice, error: Int) = real.onError(camera, error)
}

/**
 * SpoofCameraDevice — delegasi semua ke real device,
 * kecuali createCaptureSession yang kita intercept.
 */
class SpoofCameraDevice(
    private val real: CameraDevice,
    private val mode: String,
    private val path: String
) : CameraDevice() {

    override fun getId(): String = real.id

    override fun createCaptureRequest(templateType: Int): CaptureRequest.Builder =
        real.createCaptureRequest(templateType)

    override fun createReprocessCaptureRequest(inputResult: TotalCaptureResult): CaptureRequest.Builder =
        real.createReprocessCaptureRequest(inputResult)

    @Suppress("DEPRECATION")
    override fun createCaptureSession(
        outputs: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler?
    ) {
        XposedBridge.log("CamSpoof: createCaptureSession intercepted, surfaces=${outputs.size}")
        startSpoofing(outputs, callback, handler)
    }

    override fun createCaptureSessionByOutputConfigurations(
        outputConfigurations: List<OutputConfiguration>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler?
    ) {
        val surfaces = outputConfigurations.mapNotNull { runCatching { it.surface }.getOrNull() }
        startSpoofing(surfaces, callback, handler)
    }

    override fun createConstrainedHighSpeedCaptureSession(
        outputs: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler?
    ) = startSpoofing(outputs, callback, handler)

    override fun close() = real.close()

    override fun isSessionConfigurationSupported(config: android.hardware.camera2.params.SessionConfiguration) = true

    override fun createCaptureSession(config: android.hardware.camera2.params.SessionConfiguration) {
        val surfaces = config.outputConfigurations.mapNotNull { runCatching { it.surface }.getOrNull() }
        startSpoofing(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) =
                config.stateCallback.onConfigured(session)
            override fun onConfigureFailed(session: CameraCaptureSession) =
                config.stateCallback.onConfigureFailed(session)
        }, null)
    }

    private fun startSpoofing(
        surfaces: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler?
    ) {
        if (surfaces.isEmpty()) {
            real.createCaptureSession(surfaces, callback, handler)
            return
        }

        val previewSurface = surfaces.first()

        // Buat SpoofSession dulu
        val spoofSession = SpoofSession(real, previewSurface, mode, path, handler)

        // Notify target app session siap
        handler?.post { callback.onConfigured(spoofSession) }
            ?: callback.onConfigured(spoofSession)

        // Mulai inject frames
        spoofSession.startFrames()
    }
}

/**
 * SpoofSession — fake CameraCaptureSession yang inject frames dari foto/video
 */
class SpoofSession(
    private val device: CameraDevice,
    private val surface: Surface,
    private val mode: String,
    private val path: String,
    private val handler: Handler?
) : CameraCaptureSession() {

    private var mediaPlayer: MediaPlayer? = null
    private var photoThread: Thread? = null
    @Volatile private var running = false

    fun startFrames() {
        running = true
        when (mode) {
            "photo" -> startPhotoFrames()
            "video" -> startVideoFrames()
        }
    }

    private fun startPhotoFrames() {
        val bmp = BitmapFactory.decodeFile(path) ?: run {
            XposedBridge.log("CamSpoof: failed to decode photo: $path")
            return
        }

        photoThread = Thread {
            XposedBridge.log("CamSpoof: photo loop started")
            while (running) {
                try {
                    val canvas = surface.lockCanvas(null) ?: break
                    canvas.drawColor(Color.BLACK)
                    val matrix = Matrix()
                    val scaleX = canvas.width.toFloat() / bmp.width
                    val scaleY = canvas.height.toFloat() / bmp.height
                    val scale = minOf(scaleX, scaleY)
                    matrix.postScale(scale, scale)
                    matrix.postTranslate(
                        (canvas.width - bmp.width * scale) / 2f,
                        (canvas.height - bmp.height * scale) / 2f
                    )
                    canvas.drawBitmap(bmp, matrix, null)
                    surface.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    break
                }
                Thread.sleep(33) // ~30fps
            }
            bmp.recycle()
        }.also { it.start() }
    }

    private fun startVideoFrames() {
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                setSurface(surface)
                isLooping = true
                setOnPreparedListener { start() }
                setOnErrorListener { _, what, extra ->
                    XposedBridge.log("CamSpoof: video error $what/$extra")
                    true
                }
                prepareAsync()
            }
            XposedBridge.log("CamSpoof: video player started")
        } catch (e: Exception) {
            XposedBridge.log("CamSpoof: video failed: ${e.message}")
        }
    }

    // ── CameraCaptureSession overrides ────────────────────────

    override fun getDevice(): CameraDevice = device
    override fun capture(r: CaptureRequest, l: CaptureCallback?, h: Handler?) = 0
    override fun captureBurst(r: List<CaptureRequest>, l: CaptureCallback?, h: Handler?) = 0
    override fun setRepeatingRequest(r: CaptureRequest, l: CaptureCallback?, h: Handler?) = 0
    override fun setRepeatingBurst(r: List<CaptureRequest>, l: CaptureCallback?, h: Handler?) = 0
    override fun stopRepeating() {}
    override fun abortCaptures() {}
    override fun prepare(target: Surface) {}
    override fun isReprocessable() = false
    override fun getInputSurface(): Surface? = null
    override fun finalizeOutputConfigurations(configs: MutableList<OutputConfiguration>?) {}

    override fun close() {
        running = false
        photoThread?.interrupt()
        mediaPlayer?.release()
        mediaPlayer = null
        XposedBridge.log("CamSpoof: session closed")
    }
}
