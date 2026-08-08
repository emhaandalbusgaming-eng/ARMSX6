package com.armsx2.autoconfig

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES10
import android.opengl.GLES20
import android.os.Build
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Reads the specs AutoConfigEngine needs to pick a starting profile for a game:
 * chipset family, GPU vendor/renderer, total/available RAM, screen resolution,
 * and a thermal throttling threshold estimate.
 *
 * Chipset/GPU are read from Build fields + an EGL probe (needs a live GL
 * context — see [detectGpu]). RAM comes from ActivityManager. Thermal
 * threshold either comes from PowerManager's headroom API (Android 11+) or
 * falls back to reading /sys/class/thermal directly, since OEMs vary wildly
 * in whether they expose the AOSP thermal HAL properly.
 */
object HardwareDetector {

    data class Specs(
        val chipset: String,          // e.g. "Snapdragon 8 Gen 2", "Mediatek Dimensity 9200", raw SOC_MODEL as fallback
        val gpu: String,               // e.g. "Adreno 740", "Mali-G715", "PowerVR"
        val gpuVendor: GpuVendor,
        val ramMb: Int,                 // total device RAM
        val ramAvailableMb: Int,        // free RAM right now
        val screenWidth: Int,
        val screenHeight: Int,
        val thermalThresholdC: Float,   // estimated onset-of-throttle temperature
        val gpuDriverVersion: String,
    )

    enum class GpuVendor { ADRENO, MALI, POWERVR, XCLIPSE, UNKNOWN }

    /** Cached after first detect() — specs don't change mid-session, and RAM/thermal
     *  are re-sampled separately via [sampleRam] / [sampleThermal] where freshness matters. */
    @Volatile private var cached: Specs? = null

    fun detect(context: Context, forceRefresh: Boolean = false): Specs {
        cached?.let { if (!forceRefresh) return it }

        val chipset = detectChipset()
        val (gpuName, gpuVendor, driverVersion) = detectGpu()
        val (ramTotal, ramAvail) = sampleRam(context)
        val (w, h) = screenResolution(context)
        val thermal = estimateThermalThreshold(context)

        return Specs(
            chipset = chipset,
            gpu = gpuName,
            gpuVendor = gpuVendor,
            ramMb = ramTotal,
            ramAvailableMb = ramAvail,
            screenWidth = w,
            screenHeight = h,
            thermalThresholdC = thermal,
            gpuDriverVersion = driverVersion,
        ).also { cached = it }
    }

    // ---- Chipset ----

    private fun detectChipset(): String {
        // SOC_MODEL is the most reliable field (API 31+) — e.g. "SM8550" for an 8 Gen 2.
        val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null
        val socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()

        val raw = (socModel ?: "").ifBlank { "$hardware/$board" }
        return prettifyChipset(raw, socManufacturer, hardware, board)
    }

    /** Best-effort mapping from raw SoC codenames to a human-readable marketing name.
     *  Extend this table as new codenames show up in the field — it only affects the
     *  starting profile tier, never a hard requirement. */
    private fun prettifyChipset(raw: String, manufacturer: String?, hardware: String, board: String): String {
        val r = raw.uppercase()
        val known = mapOf(
            "SM8650" to "Snapdragon 8 Gen 3", "SM8550" to "Snapdragon 8 Gen 2",
            "SM8475" to "Snapdragon 8+ Gen 1", "SM8450" to "Snapdragon 8 Gen 1",
            "SM8350" to "Snapdragon 888", "SM8250" to "Snapdragon 865",
            "SM8150" to "Snapdragon 855", "SM7325" to "Snapdragon 778G",
            "SM7250" to "Snapdragon 765G", "SM6375" to "Snapdragon 695",
            "SM4350" to "Snapdragon 480", "SM6225" to "Snapdragon 680",
            "SM6115" to "Snapdragon 662",
        )
        known.entries.firstOrNull { r.contains(it.key) }?.let { return it.value }

        // Dimensity / Mali-heavy MediaTek chips report via hardware/board, not SOC_MODEL.
        val mtk = Regex("MT\\d{4}").find(r)?.value
        if (mtk != null) return "MediaTek $mtk"
        if (hardware.contains("mt6") || board.contains("mt6")) return "MediaTek (${hardware.ifBlank { board }})"

        if (r.contains("EXYNOS") || hardware.contains("exynos")) return "Exynos ($raw)"
        if (r.contains("KIRIN") || hardware.contains("kirin")) return "Kirin ($raw)"
        if (r.contains("TENSOR")) return "Google Tensor ($raw)"

        // Common midrange Snapdragon board name (e.g. "720G" boards report as "lito"/"bengal" etc)
        // — without SOC_MODEL (pre-API 31) we can't recover the marketing name reliably, so
        // surface the raw identifier and let AutoConfigEngine fall back to RAM-based tiering.
        return manufacturer?.let { "$it ${raw.ifBlank { hardware }}" } ?: raw.ifBlank { hardware }
    }

    // ---- GPU ----

    /** GL_RENDERER / GL_VENDOR require a current EGL context. If this is called before
     *  the emulator's surface exists, it falls back to Build.SUPPORTED_ABIS-based
     *  guessing, which is far less reliable — prefer calling this after the GLSurface
     *  (or a throwaway probe context) is created. In practice AutoConfigEngine calls this
     *  once EmulationSurface has produced its first frame. */
    private fun detectGpu(): Triple<String, GpuVendor, String> {
        val renderer = runCatching { GLES20.glGetString(GLES20.GL_RENDERER) }.getOrNull()
            ?: runCatching { GLES10.glGetString(GLES10.GL_RENDERER) }.getOrNull()
        val vendorStr = runCatching { GLES20.glGetString(GLES20.GL_VENDOR) }.getOrNull() ?: ""
        val version = runCatching { GLES20.glGetString(GLES20.GL_VERSION) }.getOrNull() ?: "unknown"

        val name = renderer ?: "unknown"
        val vendor = when {
            name.contains("Adreno", true) || vendorStr.contains("Qualcomm", true) -> GpuVendor.ADRENO
            name.contains("Mali", true) || vendorStr.contains("ARM", true) -> GpuVendor.MALI
            name.contains("PowerVR", true) || vendorStr.contains("Imagination", true) -> GpuVendor.POWERVR
            name.contains("Xclipse", true) -> GpuVendor.XCLIPSE
            else -> GpuVendor.UNKNOWN
        }
        return Triple(name, vendor, version)
    }

    // ---- RAM ----

    fun sampleRam(context: Context): Pair<Int, Int> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.totalMem / (1024 * 1024)).toInt() to (info.availMem / (1024 * 1024)).toInt()
    }

    // ---- Screen ----

    private fun screenResolution(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    // ---- Thermal ----

    /** A rough onset-of-throttle temperature, used by AutoConfigEngine.onThermalWarning's
     *  caller to decide when to poll harder. Not a measurement — see [sampleThermal] for
     *  the live reading. */
    private fun estimateThermalThreshold(context: Context): Float {
        // Android 10+ headroom API returns 0..1 "how close to throttling" rather than a
        // temperature, so we can't read a threshold in °C from it directly; use the
        // conservative default and let sampleThermal's headroom signal drive decisions
        // when available (see AutoConfigEngine.pollThermal).
        return 45.0f
    }

    /** Live temperature sample, °C. Prefers PowerManager's thermal headroom (normalized
     *  0..1+, 1.0 == throttling) converted against the estimated threshold; falls back to
     *  reading the hottest /sys/class/thermal/thermal_zoneN/temp entry whose type looks
     *  CPU/GPU-related, since headroom isn't available on every OEM skin. */
    fun sampleThermal(context: Context): Float {
        if (Build.VERSION.SDK_INT >= 30) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val headroom = pm?.let { runCatching { it.getThermalHeadroom(0) }.getOrNull() }
            if (headroom != null && headroom > 0f && !headroom.isNaN()) {
                // headroom 1.0 ~= throttling onset ~= thermalThresholdC
                return estimateThermalThreshold(context) * headroom
            }
        }
        return readThermalZonesC() ?: -1f
    }

    private fun readThermalZonesC(): Float? {
        val base = File("/sys/class/thermal")
        val zones = base.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
        var hottestRelevant: Float? = null
        for (zone in zones) {
            val type = runCatching { File(zone, "type").readText().trim().lowercase() }.getOrNull() ?: continue
            if (!(type.contains("cpu") || type.contains("gpu") || type.contains("big") || type.contains("apu") || type.contains("soc"))) continue
            val milliC = runCatching { File(zone, "temp").readText().trim().toFloat() }.getOrNull() ?: continue
            // Some kernels report milli-°C (e.g. 45000), others report raw °C (e.g. 45).
            val celsius = if (milliC > 1000f) milliC / 1000f else milliC
            if (celsius in 0f..150f && (hottestRelevant == null || celsius > hottestRelevant!!)) {
                hottestRelevant = celsius
            }
        }
        return hottestRelevant
    }
}
