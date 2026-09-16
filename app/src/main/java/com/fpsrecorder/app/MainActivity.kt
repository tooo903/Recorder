package com.fpsrecorder.app

import android.app.Activity
import android.content.Intent
import android.media.MediaFormat
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var fpsSpinner: Spinner
    private lateinit var resolutionSpinner: Spinner
    private lateinit var codecSpinner: Spinner
    private lateinit var reportView: TextView
    private lateinit var startStopButton: Button
    private lateinit var permButton: Button

    private var isRecording = false

    private val fpsOptions = listOf(60, 72, 80, 90)
    private val resolutionOptions = listOf("1280x720" to Pair(1280, 720), "1920x1080" to Pair(1920, 1080))
    private val codecOptions = listOf("H.264 (AVC)" to MediaFormat.MIMETYPE_VIDEO_AVC, "H.265 (HEVC)" to MediaFormat.MIMETYPE_VIDEO_HEVC)

    private lateinit var projectionManager: MediaProjectionManager

    private val projectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchRecordService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Разрешение на запись экрана не выдано", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "FPS Recorder — контролируемая запись экрана"
            textSize = 18f
        })

        root.addView(sectionLabel("Целевой FPS записи"))
        fpsSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, fpsOptions.map { "$it fps" })
            setSelection(0)
        }
        root.addView(fpsSpinner)

        root.addView(sectionLabel("Разрешение записи"))
        resolutionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, resolutionOptions.map { it.first })
        }
        root.addView(resolutionSpinner)

        root.addView(sectionLabel("Кодек"))
        codecSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, codecOptions.map { it.first })
        }
        root.addView(codecSpinner)

        val checkButton = Button(this).apply {
            text = "Проверить реальный лимит кодека"
            setOnClickListener { runCodecReport() }
        }
        root.addView(checkButton)

        reportView = TextView(this).apply {
            textSize = 13f
            setPadding(0, 16, 0, 16)
        }
        root.addView(reportView)

        permButton = Button(this).apply {
            text = "Выдать разрешение на изменение настроек экрана"
            setOnClickListener { requestWriteSettings() }
        }
        root.addView(permButton)

        startStopButton = Button(this).apply {
            text = "Начать запись"
            setOnClickListener { toggleRecording() }
        }
        root.addView(startStopButton)

        setContentView(root)
        runCodecReport()
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setPadding(0, 24, 0, 4)
    }

    private fun runCodecReport() {
        val fps = fpsOptions[fpsSpinner.selectedItemPosition]
        val lines = CodecCapabilities.fullReport(fps)
        reportView.text = buildString {
            appendLine("Реальные возможности твоего видеоэнкодера (для $fps fps):")
            lines.forEach { appendLine("• $it") }
            appendLine()
            appendLine("Если везде написано \"НЕ хватает\" — это аппаратное ограничение SoC, программно это не обойти.")
        }
    }

    private fun requestWriteSettings() {
        if (!Settings.System.canWrite(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
            startActivity(intent)
        } else {
            Toast.makeText(this, "Разрешение уже выдано", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecordService()
        } else {
            if (!Settings.System.canWrite(this)) {
                Toast.makeText(this, "Сначала выдай разрешение на изменение настроек экрана — иначе частота 90Hz не зафиксируется", Toast.LENGTH_LONG).show()
                return
            }
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    private fun launchRecordService(resultCode: Int, data: Intent) {
        val fps = fpsOptions[fpsSpinner.selectedItemPosition]
        val (w, h) = resolutionOptions[resolutionSpinner.selectedItemPosition].second
        val mime = codecOptions[codecSpinner.selectedItemPosition].second

        val intent = Intent(this, RecordService::class.java).apply {
            putExtra(RecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecordService.EXTRA_RESULT_DATA, data)
            putExtra(RecordService.EXTRA_WIDTH, w)
            putExtra(RecordService.EXTRA_HEIGHT, h)
            putExtra(RecordService.EXTRA_FPS, fps)
            putExtra(RecordService.EXTRA_BITRATE, if (w >= 1920) 16_000_000 else 10_000_000)
            putExtra(RecordService.EXTRA_MIME, mime)
        }
        ContextCompat.startForegroundService(this, intent)
        isRecording = true
        startStopButton.text = "Остановить запись"
    }

    private fun stopRecordService() {
        val intent = Intent(this, RecordService::class.java).apply {
            action = RecordService.ACTION_STOP
        }
        startService(intent)
        isRecording = false
        startStopButton.text = "Начать запись"
    }
}
