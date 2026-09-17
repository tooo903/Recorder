package com.fpsrecorder.app

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Surface
import android.widget.Toast
import java.io.File

class RecordService : Service() {

    companion object {
        const val CHANNEL_ID = "fps_recorder_channel"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_MIME = "mime"
        const val ACTION_STOP = "com.fpsrecorder.app.STOP"
        private const val TAG = "RecordService"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    @Volatile
    private var isStopping = false

    private var originalMinRefreshRate: String? = null
    private var originalPeakRefreshRate: String? = null

    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var legacyOutputFile: File? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        val width = intent?.getIntExtra(EXTRA_WIDTH, 1280) ?: 1280
        val height = intent?.getIntExtra(EXTRA_HEIGHT, 720) ?: 720
        val requestedFps = intent?.getIntExtra(EXTRA_FPS, 60) ?: 60
        val bitrate = intent?.getIntExtra(EXTRA_BITRATE, 12_000_000) ?: 12_000_000
        val mime = intent?.getStringExtra(EXTRA_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC

        startForeground(1, buildNotification())

        if (resultData == null) {
            Log.e(TAG, "Нет разрешения на запись экрана")
            stopSelf()
            return START_NOT_STICKY
        }

        // КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: не просим у энкодера больше, чем он реально может.
        // Раньше requestedFps шёл напрямую в KEY_FRAME_RATE, даже если аппаратный
        // энкодер физически не тянет — это переполняло буфер и роняло плавность
        // всей системы, а не только записи.
        val encoderInfo = CodecCapabilities.probe(width, height, requestedFps, mime)
        val effectiveFps = if (encoderInfo != null && !encoderInfo.supportsRequestedFps) {
            encoderInfo.maxFpsAtResolution.toInt().coerceAtLeast(1)
        } else {
            requestedFps
        }

        if (effectiveFps < requestedFps) {
            Log.w(TAG, "Энкодер не тянет $requestedFps fps на ${width}x$height, снижаю до $effectiveFps")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    this,
                    "Энкодер не тянет $requestedFps fps на этом разрешении — записываю $effectiveFps fps",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        lockRefreshRate(effectiveFps)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)

        startEncoding(width, height, effectiveFps, bitrate, mime)

        return START_STICKY
    }

    private fun lockRefreshRate(fps: Int) {
        if (!Settings.System.canWrite(this)) {
            Log.w(TAG, "Нет разрешения WRITE_SETTINGS — не могу зафиксировать частоту экрана")
            return
        }
        try {
            originalMinRefreshRate = Settings.System.getString(contentResolver, "min_refresh_rate")
            originalPeakRefreshRate = Settings.System.getString(contentResolver, "peak_refresh_rate")

            val target = if (fps >= 90) "90" else if (fps >= 60) "60" else fps.toString()
            Settings.System.putString(contentResolver, "min_refresh_rate", target)
            Settings.System.putString(contentResolver, "peak_refresh_rate", target)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось зафиксировать refresh rate: ${e.message}")
        }
    }

    private fun restoreRefreshRate() {
        if (!Settings.System.canWrite(this)) return
        try {
            Settings.System.putString(contentResolver, "min_refresh_rate", originalMinRefreshRate ?: "0")
            Settings.System.putString(contentResolver, "peak_refresh_rate", originalPeakRefreshRate ?: "0")
        } catch (_: Exception) {
        }
    }

    private fun createMuxer(mime: String): MediaMuxer {
        val fileName = "fps_record_${System.currentTimeMillis()}.mp4"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FpsRecorder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Не удалось создать запись в MediaStore")
            outputUri = uri

            val pfd = contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Не удалось открыть файловый дескриптор")
            outputPfd = pfd

            MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            @Suppress("DEPRECATION")
            val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "FpsRecorder")
            if (!moviesDir.exists()) moviesDir.mkdirs()
            val outFile = File(moviesDir, fileName)
            legacyOutputFile = outFile

            MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
    }

    private fun finalizeOutputFile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            outputUri?.let { uri ->
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                try {
                    contentResolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Не удалось финализировать файл в MediaStore: ${e.message}")
                }
            }
            try {
                outputPfd?.close()
            } catch (_: Exception) {
            }
        } else {
            legacyOutputFile?.let { file ->
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
            }
        }
    }

    private fun startEncoding(width: Int, height: Int, fps: Int, bitrate: Int, mime: String) {
        encoderThread = HandlerThread("EncoderThread").also { it.start() }
        encoderHandler = Handler(encoderThread!!.looper)

        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000 / fps)
        }

        encoder = MediaCodec.createEncoderByType(mime).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        muxer = createMuxer(mime)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FpsRecorderDisplay",
            width, height, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, encoderHandler
        )

        drainEncoderLoop()
    }

    private fun drainEncoderLoop() {
        encoderHandler?.post(object : Runnable {
            val bufferInfo = MediaCodec.BufferInfo()
            override fun run() {
                if (isStopping) return

                val codec = encoder ?: return
                var running = true
                while (running) {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIndex >= 0 -> {
                            val encodedData = codec.getOutputBuffer(outIndex) ?: continue
                            if (bufferInfo.size > 0 && muxerStarted) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer?.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer?.addTrack(codec.outputFormat) ?: -1
                            muxer?.start()
                            muxerStarted = true
                        }
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            running = false
                        }
                    }
                }
                if (!isStopping) {
                    encoderHandler?.postDelayed(this, 5)
                }
            }
        })
    }

    private fun stopRecording() {
        if (isStopping) return
        isStopping = true

        val handler = encoderHandler
        if (handler == null) {
            releaseAll()
            return
        }

        handler.post {
            try {
                encoder?.signalEndOfInputStream()
                drainRemainingOutput()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при финальном дренаже: ${e.message}")
            } finally {
                releaseAll()
            }
        }
    }

    private fun drainRemainingOutput() {
        val codec = encoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var sawEos = false
        var attempts = 0

        while (!sawEos && attempts < 50) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawEos = true
                    }
                    val encodedData = codec.getOutputBuffer(outIndex)
                    if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer?.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer?.addTrack(codec.outputFormat) ?: -1
                    muxer?.start()
                    muxerStarted = true
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    attempts++
                }
            }
        }
    }

    private fun releaseAll() {
        try {
            virtualDisplay?.release()
            encoder?.stop()
            encoder?.release()
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            muxer?.release()
            mediaProjection?.stop()
            finalizeOutputFile()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при остановке: ${e.message}")
        } finally {
            encoderThread?.quitSafely()
            restoreRefreshRate()
            Handler(Looper.getMainLooper()).post {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        if (!isStopping) {
            stopRecording()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Запись экрана", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Запись экрана")
            .setContentText("Идёт запись с фиксированным FPS")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
