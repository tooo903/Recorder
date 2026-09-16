package com.fpsrecorder.app

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Главный вопрос "почему запись 60, а не 90" почти всегда решается здесь.
 * Экран может отдавать 90 кадров/сек, но АППАРАТНЫЙ энкодер (Venus/Adreno)
 * может физически не поддерживать кодирование выше 60fps на выбранном разрешении.
 * Это железное ограничение конкретного SoC, а не баг рекордера.
 *
 * Эта функция честно показывает, что реально может кодек на устройстве,
 * вместо того чтобы приложение "пыталось" писать 90 и незаметно откатывалось.
 */
object CodecCapabilities {

    data class EncoderInfo(
        val name: String,
        val mime: String,
        val maxFpsAtResolution: Double,
        val supportsRequestedFps: Boolean
    )

    /**
     * width/height — целевое разрешение записи.
     * requestedFps — то, что хочет пользователь (например 90).
     * mime — "video/avc" (H.264) или "video/hevc" (H.265).
     */
    fun probe(width: Int, height: Int, requestedFps: Int, mime: String): EncoderInfo? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue

            val caps = try {
                info.getCapabilitiesForType(mime)
            } catch (e: Exception) {
                continue
            }

            // Хотим только аппаратные энкодеры — софтварные почти всегда
            // не тянут высокий FPS на 1080p+ и дают доп. нагрузку на CPU,
            // что и приводит к просадке FPS игры при записи.
            val isHardware = info.isHardwareAccelerated
            if (!isHardware) continue

            val videoCaps = caps.videoCapabilities ?: continue
            if (!videoCaps.isSizeSupported(width, height)) continue

            val fpsRange = videoCaps.getSupportedFrameRatesFor(width, height)
            val maxFps = fpsRange.upper.toDouble()

            return EncoderInfo(
                name = info.name,
                mime = mime,
                maxFpsAtResolution = maxFps,
                supportsRequestedFps = maxFps >= requestedFps
            )
        }
        return null
    }

    /**
     * Пробегает по стандартным разрешениям и обоим кодекам,
     * чтобы сразу показать пользователю таблицу: что реально доступно на его железе.
     */
    fun fullReport(requestedFps: Int): List<String> {
        val resolutions = listOf(
            Triple(1920, 1080, "1080p"),
            Triple(1280, 720, "720p")
        )
        val mimes = listOf(MediaFormat.MIMETYPE_VIDEO_AVC to "H.264", MediaFormat.MIMETYPE_VIDEO_HEVC to "H.265")

        val lines = mutableListOf<String>()
        for ((w, h, label) in resolutions) {
            for ((mime, mimeLabel) in mimes) {
                val res = probe(w, h, requestedFps, mime)
                if (res == null) {
                    lines.add("$label / $mimeLabel: аппаратный энкодер недоступен")
                } else {
                    val verdict = if (res.supportsRequestedFps) "ОК, хватает" else "НЕ хватает"
                    lines.add("$label / $mimeLabel: максимум ${res.maxFpsAtResolution.toInt()} fps ($verdict для ${requestedFps}fps)")
                }
            }
        }
        return lines
    }
}
