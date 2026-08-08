package com.armsx2.autoconfig

import android.util.Log
import java.io.File

/**
 * A resolved graphics profile for one game. Field names intentionally mirror
 * the subset of [com.armsx2.config.Settings] that AutoConfigEngine pushes:
 *
 *   resolutionScale     -> Settings.upscaleFloat            (EmuCore/GS/upscale_multiplier)
 *   blendingAccuracy     -> Settings.accurateBlendingUnit     (EmuCore/GS/accurate_blending_unit) 0 none/1 basic/2 full
 *   anisotropicFiltering -> Settings.maxAnisotropy            (EmuCore/GS/MaxAnisotropy) 0/2/4/8/16
 *   antiAliasing         -> Settings.fxaa                     (EmuCore/GS/fxaa)
 *   skipDraw             -> Settings.skipDrawStart/skipDrawEnd (EmuCore/GS/UserHacks_SkipDraw_*)
 */
data class GameProfile(
    val gameId: String,
    val resolutionScale: Float = 1.0f,
    val blendingAccuracy: Int = 1,       // 0 none, 1 basic, 2 full
    val anisotropicFiltering: Int = 0,    // 0 (off/bilinear only), 2, 4, 8, 16
    val antiAliasing: Boolean = false,
    val skipDraw: Boolean = false,
    val generatedBy: String = "auto",     // "auto" | "user" | "downgrade"
    val lastAppliedAt: Long = 0L,
) {
    companion object {
        val RESOLUTION_LADDER = floatArrayOf(3.0f, 2.5f, 2.0f, 1.5f, 1.0f)
        val ANISO_LADDER = intArrayOf(16, 8, 4, 0)
    }
}

/**
 * Loads/saves [GameProfile] as INI at /sdcard/AetherSX2/game_profiles/{GameID}.ini,
 * and generates a starting profile from detected hardware.
 *
 * Storage note: writing directly to /sdcard on Android 11+ requires either
 * MANAGE_EXTERNAL_STORAGE (declared in AndroidManifest — see integration notes)
 * or running as a legacy-storage app. If that permission is missing, [save] and
 * [load] transparently fall back to the app's own external files dir
 * (Android/data/<pkg>/files/AetherSX2/game_profiles) so the feature still works;
 * only the *path* changes, not the format.
 */
object GameProfileManager {
    private const val TAG = "AutoConfig/ProfileMgr"
    private const val PRIMARY_DIR = "/sdcard/AetherSX2/game_profiles"

    private var fallbackDir: File? = null

    /** Call once from Application.onCreate with an app Context, so the fallback
     *  directory is available if /sdcard isn't writable. */
    fun init(fallbackExternalFilesDir: File?) {
        fallbackDir = fallbackExternalFilesDir?.resolve("AetherSX2/game_profiles")
    }

    private fun resolveDir(): File {
        val primary = File(PRIMARY_DIR)
        val usable = runCatching {
            primary.mkdirs()
            primary.canWrite() || (!primary.exists() && primary.parentFile?.canWrite() == true)
        }.getOrDefault(false)
        if (usable) return primary

        val fb = fallbackDir ?: File(PRIMARY_DIR) // last resort, will just fail loudly
        runCatching { fb.mkdirs() }
        return fb
    }

    private fun fileFor(gameId: String): File {
        val safe = gameId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(resolveDir(), "$safe.ini")
    }

    fun exists(gameId: String): Boolean = fileFor(gameId).exists()

    fun load(gameId: String): GameProfile? {
        val f = fileFor(gameId)
        if (!f.exists()) return null
        return runCatching {
            val kv = mutableMapOf<String, String>()
            f.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("#") || trimmed.startsWith("[")) return@forEachLine
                val idx = trimmed.indexOf('=')
                if (idx <= 0) return@forEachLine
                kv[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
            }
            GameProfile(
                gameId = gameId,
                resolutionScale = kv["ResolutionScale"]?.toFloatOrNull() ?: 1.0f,
                blendingAccuracy = kv["BlendingAccuracy"]?.toIntOrNull() ?: 1,
                anisotropicFiltering = kv["AnisotropicFiltering"]?.toIntOrNull() ?: 0,
                antiAliasing = kv["AntiAliasing"]?.toBooleanStrictOrNull() ?: false,
                skipDraw = kv["SkipDraw"]?.toBooleanStrictOrNull() ?: false,
                generatedBy = kv["GeneratedBy"] ?: "auto",
                lastAppliedAt = kv["LastAppliedAt"]?.toLongOrNull() ?: 0L,
            )
        }.onFailure { Log.w(TAG, "Failed to read profile for $gameId: ${it.message}") }.getOrNull()
    }

    fun save(profile: GameProfile) {
        val f = fileFor(profile.gameId)
        val text = buildString {
            appendLine("; AetherSX2 auto-generated per-game graphics profile")
            appendLine("; GameID: ${profile.gameId}")
            appendLine("[Graphics]")
            appendLine("ResolutionScale=${profile.resolutionScale}")
            appendLine("BlendingAccuracy=${profile.blendingAccuracy}")
            appendLine("AnisotropicFiltering=${profile.anisotropicFiltering}")
            appendLine("AntiAliasing=${profile.antiAliasing}")
            appendLine("SkipDraw=${profile.skipDraw}")
            appendLine("GeneratedBy=${profile.generatedBy}")
            appendLine("LastAppliedAt=${System.currentTimeMillis()}")
        }
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(text)
        }.onFailure { Log.e(TAG, "Failed to save profile for ${profile.gameId}: ${it.message}") }
    }

    /**
     * Generates a starting profile from detected hardware. Tiering is driven
     * primarily by GPU class (Adreno tends to outperform same-tier Mali on the
     * PS2 HW renderer) and RAM, matching the reference mapping:
     *
     *   Snapdragon 8 Gen 2 + 8GB  -> 2.5x, AA on,  blending full
     *   Snapdragon 865   + 6GB    -> 1.5x, AA off, blending basic
     *   Snapdragon 720G  + 4GB    -> 1x,   all effects low
     */
    fun autoGenerate(hw: HardwareDetector.Specs, gameId: String, gameWeight: GameWeight = GameWeight.NORMAL): GameProfile {
        val tier = classifyTier(hw)
        var profile = when (tier) {
            Tier.FLAGSHIP -> GameProfile(
                gameId = gameId, resolutionScale = 2.5f, blendingAccuracy = 2,
                anisotropicFiltering = 16, antiAliasing = true, skipDraw = false, generatedBy = "auto",
            )
            Tier.UPPER_MID -> GameProfile(
                gameId = gameId, resolutionScale = 1.5f, blendingAccuracy = 1,
                anisotropicFiltering = 8, antiAliasing = false, skipDraw = false, generatedBy = "auto",
            )
            Tier.MID -> GameProfile(
                gameId = gameId, resolutionScale = 1.0f, blendingAccuracy = 1,
                anisotropicFiltering = 4, antiAliasing = false, skipDraw = false, generatedBy = "auto",
            )
            Tier.LOW -> GameProfile(
                gameId = gameId, resolutionScale = 1.0f, blendingAccuracy = 0,
                anisotropicFiltering = 0, antiAliasing = false, skipDraw = false, generatedBy = "auto",
            )
        }

        // Heavy titles (GS-bound: lots of overdraw/blending — God of War, GT4, etc.) get
        // knocked down one notch from the hardware-only tier so the first boot isn't the
        // stutter-fest that teaches the user to distrust auto-config.
        if (gameWeight == GameWeight.HEAVY) {
            profile = GraphicsDowngrader.stepDown(profile, GraphicsDowngrader.Priority.RESOLUTION)
        }

        return profile
    }

    enum class GameWeight { LIGHT, NORMAL, HEAVY }

    private enum class Tier { FLAGSHIP, UPPER_MID, MID, LOW }

    private fun classifyTier(hw: HardwareDetector.Specs): Tier {
        val ram = hw.ramMb
        val chip = hw.chipset.lowercase()

        val flagshipChip = Regex("8 gen [23]|888|8\\+? gen 1|snapdragon 8|dimensity 9\\d{3}|tensor g[3-9]").containsMatchIn(chip)
        val upperMidChip = Regex("865|855|778g|780g|870|dimensity 8\\d{3}|dimensity 1200|tensor$|tensor g1|tensor g2").containsMatchIn(chip)
        val midChip = Regex("765|750g|695|730|732g|dimensity 7\\d{2}|dimensity 900|dimensity 1000|kirin 9").containsMatchIn(chip)

        return when {
            flagshipChip && ram >= 7000 -> Tier.FLAGSHIP
            (flagshipChip || upperMidChip) && ram >= 5500 -> Tier.UPPER_MID
            (upperMidChip || midChip) && ram >= 3500 -> Tier.MID
            ram >= 6000 && hw.gpuVendor == HardwareDetector.GpuVendor.ADRENO -> Tier.UPPER_MID
            ram >= 3500 -> Tier.MID
            else -> Tier.LOW
        }
    }
}
