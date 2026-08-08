package com.armsx2.autoconfig

import android.content.Context
import android.util.Log
import com.armsx2.config.LiveGsApplyQueue
import com.armsx2.config.Settings
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridges the auto-config feature to the app's existing settings pipeline.
 * Deliberately does NOT reimplement a settings channel: it produces a
 * [Settings] value (or a mutation of the current one) and hands it to
 * [LiveGsApplyQueue], the same hot-reload path the manual settings UI uses
 * (see Settings.applyGsLive / LiveGsApplyQueue.applySettings), so applying a
 * profile — including a stutter-recovery downgrade — never needs a restart.
 *
 * Overhead: profile lookup is one file read (cached in-memory afterward,
 * see [cachedProfiles]) and applying a level is 5 field writes into an
 * existing data class + one already-batched IPC to native. Thermal polling
 * (if enabled) is on a 1 Hz background timer touching a sysfs read, well
 * under the <1% CPU budget.
 */
object AutoConfigEngine {
    private const val TAG = "AutoConfig/Engine"

    private val cachedProfiles = HashMap<String, GameProfile>()
    private var currentGameId: String? = null
    private var lastThermalDowngradeAt = AtomicLong(0)
    private const val THERMAL_DOWNGRADE_COOLDOWN_MS = 30_000L

    var appContext: Context? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        GameProfileManager.init(context.applicationContext.getExternalFilesDir(null))
    }

    /**
     * Call this once a game's serial/GameID is known and the GS renderer has
     * produced at least one frame (so GL_RENDERER is queryable — see
     * HardwareDetector.detectGpu). Returns the [Settings] to launch with:
     * the caller's existing base Settings merged with the resolved profile.
     *
     * If a profile already exists on disk for this GameID, it's used as-is
     * (the user or a previous downgrade may have tuned it). Otherwise one is
     * generated from detected hardware and saved so it's editable afterward.
     */
    fun onGameLoaded(gameId: String, baseSettings: Settings, gameWeight: GameProfileManager.GameWeight = GameProfileManager.GameWeight.NORMAL): Settings {
        currentGameId = gameId
        val ctx = appContext ?: return baseSettings

        val profile = cachedProfiles[gameId]
            ?: GameProfileManager.load(gameId)
            ?: GameProfileManager.autoGenerate(HardwareDetector.detect(ctx), gameId, gameWeight)
                .also { GameProfileManager.save(it) }

        cachedProfiles[gameId] = profile
        Log.i(TAG, "Loaded profile for $gameId: res=${profile.resolutionScale}x blend=${profile.blendingAccuracy} " +
            "aniso=${profile.anisotropicFiltering} aa=${profile.antiAliasing} skipDraw=${profile.skipDraw} (${profile.generatedBy})")

        return applyProfileToSettings(baseSettings, profile)
    }

    /**
     * Call from wherever the user manually edits a GS setting the profile
     * tracks (resolution, blending, anisotropy, FXAA). Diffs against the
     * cached profile and, if changed, persists — mirroring ConfigStore's
     * "auto-save on change" behavior but scoped to the auto-config fields.
     */
    fun onSettingsChanged(gameId: String, settings: Settings) {
        val existing = cachedProfiles[gameId] ?: GameProfileManager.load(gameId) ?: return
        val updated = existing.copy(
            resolutionScale = settings.upscaleFloat,
            blendingAccuracy = settings.accurateBlendingUnit,
            anisotropicFiltering = settings.maxAnisotropy,
            antiAliasing = settings.fxaa,
            skipDraw = settings.skipDrawStart != 0 || settings.skipDrawEnd != 0,
            generatedBy = "user",
        )
        if (updated == existing) return
        cachedProfiles[gameId] = updated
        GameProfileManager.save(updated)
        Log.i(TAG, "Manual edit saved to profile for $gameId")
    }

    /**
     * The "Reload Graphics -1 Level" action. Pops the current profile down
     * one notch (see GraphicsDowngrader's priority order), pushes it live via
     * the existing GS hot-reload path, saves it, and returns a short log
     * string suitable for a toast / overlay message.
     */
    fun downgradeOneLevel(currentSettings: Settings): String {
        val gameId = currentGameId ?: return "No game loaded"
        val profile = cachedProfiles[gameId] ?: GameProfileManager.load(gameId)
            ?: return "No profile to downgrade"

        val result = GraphicsDowngrader.downgradeOneLevel(profile)
        if (result.stepTaken == null) {
            Log.i(TAG, "downgradeOneLevel($gameId): ${result.description}")
            return result.description
        }

        cachedProfiles[gameId] = result.profile
        GameProfileManager.save(result.profile)

        val newSettings = applyProfileToSettings(currentSettings, result.profile)
        LiveGsApplyQueue.applySettings(newSettings)

        Log.i(TAG, "downgradeOneLevel($gameId): ${result.description}")
        return result.description
    }

    /**
     * Feed periodic temperature samples here (e.g. from a 1 Hz poller reading
     * HardwareDetector.sampleThermal). If temp exceeds the detected
     * threshold, drops resolution scale by 0.5x — once per cooldown window,
     * so a sustained hot state doesn't spiral the game down to 1x in seconds.
     */
    fun onThermalWarning(tempC: Float, thresholdC: Float, currentSettings: Settings) {
        if (tempC < thresholdC) return
        val now = System.currentTimeMillis()
        val last = lastThermalDowngradeAt.get()
        if (now - last < THERMAL_DOWNGRADE_COOLDOWN_MS) return
        if (!lastThermalDowngradeAt.compareAndSet(last, now)) return

        val gameId = currentGameId ?: return
        val profile = cachedProfiles[gameId] ?: GameProfileManager.load(gameId) ?: return
        val steppedRes = (profile.resolutionScale - 0.5f).coerceAtLeast(GameProfile.RESOLUTION_LADDER.last())
        if (steppedRes == profile.resolutionScale) return

        val updated = profile.copy(resolutionScale = steppedRes, generatedBy = "downgrade")
        cachedProfiles[gameId] = updated
        GameProfileManager.save(updated)
        LiveGsApplyQueue.applySettings(applyProfileToSettings(currentSettings, updated))
        Log.w(TAG, "Thermal downgrade for $gameId: ${tempC}C >= ${thresholdC}C threshold, res -> ${steppedRes}x")
    }

    private fun applyProfileToSettings(base: Settings, profile: GameProfile): Settings = base.copy(
        upscaleFloat = profile.resolutionScale,
        accurateBlendingUnit = profile.blendingAccuracy,
        maxAnisotropy = profile.anisotropicFiltering,
        fxaa = profile.antiAliasing,
        skipDrawStart = if (profile.skipDraw) 1 else 0,
        skipDrawEnd = if (profile.skipDraw) 1 else 0,
    )
}
