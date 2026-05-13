package com.halo.ring.core

/**
 * Which glasses are we running on. Resolved at startup from `Build.*` + system properties — see
 * [DeviceProfileResolver]. Even though we ship two flavors, both flavors also run this check so the
 * GENERIC fallback lets us develop on a regular Android phone without crashing.
 */
enum class DeviceProfile {
    ROKID_GLASSES,
    RAYNEO_X3PRO,
    GENERIC_ANDROID,   // dev fallback / unknown glasses
}

/**
 * Detect [DeviceProfile] from build identifiers. Exact predicate values are placeholders — fill in
 * from real `getprop` output during phase-0 verification (§18.7 step 1).
 */
fun interface DeviceProfileResolver {
    fun resolve(): DeviceProfile
}
