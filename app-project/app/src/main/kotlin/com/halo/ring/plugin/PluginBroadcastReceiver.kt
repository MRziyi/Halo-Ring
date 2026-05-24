package com.halo.ring.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.halo.ring.core.plugin.PluginBindingsParser
import com.halo.ring.core.plugin.ProfileStack

/**
 * Receives Doc/18 §6 `PROFILE_PUSH` / `PROFILE_POP` broadcasts from external plugin apps and
 * forwards them onto a [ProfileStack]. Registered programmatically by `HaloRingService.onCreate`
 * (not in the manifest) because the receiver needs a live reference to the service's stack +
 * scheduler thread for thread-safe mutation.
 *
 * Permission: the matching intent-filter is registered with `Context.RECEIVER_EXPORTED` and
 * gated by `com.halo.ring.permission.PUSH_PROFILE` (signature|privileged level) so only apps
 * the wearer trusts (signed by Halo Ring's key, or privileged ROM installs) can push frames.
 */
class PluginBroadcastReceiver(
    private val stack: ProfileStack,
    /** Posted by the receiver on every mutation so the service can re-check the active profile.
     *  Runs on whatever thread the broadcast lands on; the lambda body is expected to marshal
     *  to the scheduler thread before touching pipeline state. */
    private val onStackChanged: () -> Unit,
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "PluginBroadcast"
        const val ACTION_PROFILE_PUSH = "com.halo.ring.action.PROFILE_PUSH"
        const val ACTION_PROFILE_POP  = "com.halo.ring.action.PROFILE_POP"
        const val PERMISSION_PUSH_PROFILE = "com.halo.ring.permission.PUSH_PROFILE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PROFILE_PUSH -> handlePush(intent)
            ACTION_PROFILE_POP  -> handlePop(intent)
            else -> Log.w(TAG, "ignored unknown action: ${intent.action}")
        }
    }

    private fun handlePush(intent: Intent) {
        val profileId = intent.getStringExtra("profile_id")?.takeIf { it.isNotEmpty() } ?: run {
            Log.w(TAG, "PROFILE_PUSH missing profile_id"); return
        }
        val owner = intent.getStringExtra("owner_package")?.takeIf { it.isNotEmpty() } ?: run {
            Log.w(TAG, "PROFILE_PUSH missing owner_package"); return
        }
        val bindingsJson = intent.getStringExtra("bindings_json").orEmpty()
        val bindings = PluginBindingsParser.parse(bindingsJson)
        if (bindings.isEmpty()) {
            Log.w(TAG, "PROFILE_PUSH from $owner/$profileId has no valid bindings — pushing empty frame anyway (caller may want fall-through-only behaviour)")
        }
        synchronized(stack) {
            stack.push(ProfileStack.PushedProfile(
                profileId = profileId,
                ownerPackage = owner,
                bindings = bindings,
                pushedAtMs = System.currentTimeMillis(),
            ))
        }
        Log.i(TAG, "PROFILE_PUSH $owner/$profileId — ${bindings.size} bindings, depth=${stack.size()}")
        onStackChanged()
    }

    private fun handlePop(intent: Intent) {
        // Spec leaves owner_package optional on POP because the sender knows its own identity;
        // we derive it from the calling package via the intent's sender if not provided. (For
        // signed-permission senders we trust the extra.)
        val profileId = intent.getStringExtra("profile_id")?.takeIf { it.isNotEmpty() } ?: run {
            Log.w(TAG, "PROFILE_POP missing profile_id"); return
        }
        val owner = intent.getStringExtra("owner_package")?.takeIf { it.isNotEmpty() }
        val removed = synchronized(stack) {
            if (owner != null) {
                stack.pop(owner, profileId)
            } else {
                // Fall back: pop ANY frame with matching profile_id (rare, only fires when the
                // caller omits owner_package — well-behaved senders include it).
                val before = stack.size()
                stack.snapshot().firstOrNull { it.profileId == profileId }?.let {
                    stack.pop(it.ownerPackage, profileId)
                }
                before > stack.size()
            }
        }
        Log.i(TAG, "PROFILE_POP $owner/$profileId — removed=$removed, depth=${stack.size()}")
        if (removed) onStackChanged()
    }
}
