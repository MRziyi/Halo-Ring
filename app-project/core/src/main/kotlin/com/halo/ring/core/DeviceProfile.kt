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
 * Detect [DeviceProfile] from build identifiers. Concrete impl lives in
 * [com.halo.ring.di.AppGraph.detectDeviceProfile] (Build.BRAND / MANUFACTURER / MODEL probe).
 * Hardware-specific predicate values are still subject to refinement once we have real
 * `getprop` output from production glasses (Doc/13 §C7 / §C8).
 */
fun interface DeviceProfileResolver {
    fun resolve(): DeviceProfile
}
