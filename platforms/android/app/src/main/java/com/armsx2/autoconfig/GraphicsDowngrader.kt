package com.armsx2.autoconfig

/**
 * Steps a [GameProfile] down by exactly one notch, following a fixed priority
 * order chosen so the *least visually/behaviorally disruptive* knob goes
 * first: resolution scale first (softens the image but never changes game
 * logic timing), then blending, then anisotropic filtering, then AA, and
 * skip-draw only as a last resort (it can hide UI elements, so it's flagged
 * rather than auto-tuned further).
 */
object GraphicsDowngrader {

    enum class Priority { RESOLUTION, BLENDING, ANISOTROPY, ANTI_ALIASING, SKIP_DRAW }

    data class Result(val profile: GameProfile, val stepTaken: Priority?, val description: String)

    /** Applies the next applicable step in priority order. Returns the same
     *  profile with stepTaken = null if every knob is already at its floor
     *  (nothing left to downgrade). */
    fun downgradeOneLevel(profile: GameProfile): Result {
        if (profile.resolutionScale > GameProfile.RESOLUTION_LADDER.last()) {
            val next = nextResolution(profile.resolutionScale)
            return Result(
                profile.copy(resolutionScale = next, generatedBy = "downgrade"),
                Priority.RESOLUTION,
                "Resolution scale ${profile.resolutionScale}x -> ${next}x",
            )
        }
        if (profile.blendingAccuracy > 0) {
            val next = profile.blendingAccuracy - 1
            return Result(
                profile.copy(blendingAccuracy = next, generatedBy = "downgrade"),
                Priority.BLENDING,
                "Blending accuracy ${blendName(profile.blendingAccuracy)} -> ${blendName(next)}",
            )
        }
        if (profile.anisotropicFiltering > 0) {
            val next = nextAniso(profile.anisotropicFiltering)
            return Result(
                profile.copy(anisotropicFiltering = next, generatedBy = "downgrade"),
                Priority.ANISOTROPY,
                "Anisotropic filtering x${profile.anisotropicFiltering} -> ${if (next == 0) "bilinear" else "x$next"}",
            )
        }
        if (profile.antiAliasing) {
            return Result(
                profile.copy(antiAliasing = false, generatedBy = "downgrade"),
                Priority.ANTI_ALIASING,
                "Anti-aliasing on -> off",
            )
        }
        if (!profile.skipDraw) {
            return Result(
                profile.copy(skipDraw = true, generatedBy = "downgrade"),
                Priority.SKIP_DRAW,
                "Skip draw enabled (last resort — may need manual draw-range tuning per game)",
            )
        }
        return Result(profile, null, "Already at the lowest supported preset for this game")
    }

    /** Used by GameProfileManager.autoGenerate to pre-emptively knock a heavy
     *  title down without going through the full priority cascade. */
    fun stepDown(profile: GameProfile, priority: Priority): GameProfile = when (priority) {
        Priority.RESOLUTION -> profile.copy(resolutionScale = nextResolution(profile.resolutionScale))
        Priority.BLENDING -> profile.copy(blendingAccuracy = (profile.blendingAccuracy - 1).coerceAtLeast(0))
        Priority.ANISOTROPY -> profile.copy(anisotropicFiltering = nextAniso(profile.anisotropicFiltering))
        Priority.ANTI_ALIASING -> profile.copy(antiAliasing = false)
        Priority.SKIP_DRAW -> profile.copy(skipDraw = true)
    }

    private fun nextResolution(current: Float): Float {
        val ladder = GameProfile.RESOLUTION_LADDER
        val idx = ladder.indexOfFirst { it <= current + 0.001f }
        return if (idx == -1 || idx == ladder.lastIndex) ladder.last() else ladder[idx + 1]
    }

    private fun nextAniso(current: Int): Int {
        val ladder = GameProfile.ANISO_LADDER
        val idx = ladder.indexOfFirst { it == current }
        return if (idx == -1 || idx == ladder.lastIndex) 0 else ladder[idx + 1]
    }

    private fun blendName(v: Int) = when (v) { 2 -> "Full"; 1 -> "Basic"; else -> "None" }
}
