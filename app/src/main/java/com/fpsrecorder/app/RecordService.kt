package com.fpsrecorder.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.Surface
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

    // Чтобы вернуть пользователю его исходную частоту обновления после записи
    private var originalMinRefreshRate: String? = null
    private var originalPeakRefreshRate: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        val width = intent?.getIntExtra(EXTRA_WIDTH, 1280) ?: 1280
        val height = intent?.getIntExtra(EXTRA_HEIGHT, 720) ?: 720
        val fps = intent?.getIntExtra(EXTRA_FPS, 60) ?: 60
        val bitrate = intent?.getIntExtra(EXTRA_BITRATE, 12_000_000) ?: 12_000_000
        val mime = intent?.getStringExtra(EXTRA_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC

        startForeground(1, buildNotification())

        if (resultData == null) {
            Log.e(TAG, "Нет разрешения на запись экрана")
            stopSelf()
            return START_NOT_STICKY
        }

        lockRefreshRate(fps)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)

        startEncoding(width, height, fps, bitrate, mime)

        return START_STICKY
    }

    /**
     * Ключевой момент: EMUI/Android может тихо занижать частоту обновления
     * до 60Hz на время активного MediaProjection ради энергосбережения.
     * Явно просим систему держать min и peak refresh rate на нужном значении.
     * Требует разрешения WRITE_SETTINGS (пользователь выдаёт его вручную из UI).
     */
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

    private fun startEncoding(width: Int, height: Int, fps: Int, bitrate: Int, mime: String) {
        encoderThread = HandlerThread("EncoderThread").also { it.start() }
        encoderHandler = Handler(encoderThread!!.looper)

        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            // Жёстко задаём FPS энкодера — не отдаём системе на откуп "естественный" темп кадров,
            // иначе при просадках она сама начнёт дропать кадры неравномерно, что и ощущается как рывки.
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Ключевой параметр против джиттера: постоянный поток кадров на входе,
            // энкодер не должен "ждать" — это сглаживает микрозадержки композитора.
            setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000 / fps)
        }

        encoder = MediaCodec.createEncoderByType(mime).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        val outFile = File(getExternalFilesDir(null), "fps_record_${System.currentTimeMillis()}.mp4")
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

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
                encoderHandler?.postDelayed(this, 5)
            }
        })
    }

    private fun stopRecording() {
        try {
            virtualDisplay?.release()
            encoder?.signalEndOfInputStream()
            encoder?.stop()
            encoder?.release()
            if (muxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при остановке: ${e.message}")
        } finally {
            encoderThread?.quitSafely()
            restoreRefreshRate()
        }
    }

    override fun onDestroy() {
        stopRecording()
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
