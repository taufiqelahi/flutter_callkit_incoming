package com.hiennv.flutter_callkit_incoming

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Controls native Android power behavior during an active call.
 *
 * CPU wake lock:
 * Keeps the CPU available throughout the active call.
 *
 * Proximity wake lock:
 * Turns the display off when the device is close to the user's ear.
 *
 * CPU and proximity wake locks are managed independently.
 */
internal object CallkitPowerManager {

    private const val TAG = "CallkitPowerManager"

    private const val CPU_LOCK_TAG =
        "flutter_callkit_incoming:call_cpu"

    private const val PROXIMITY_LOCK_TAG =
        "flutter_callkit_incoming:call_proximity"

    private val lock = Any()

    private var callActive = false
    private var proximityEnabled = false

    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null

    /**
     * Activates power management for an accepted or outgoing call.
     *
     * The CPU wake lock remains active for the complete call.
     *
     * Proximity can later be enabled or disabled independently through
     * [setProximityEnabled].
     */
    fun activate(
        context: Context,
        enableProximity: Boolean
    ) {
        synchronized(lock) {
            callActive = true
            proximityEnabled = enableProximity

            val powerManager =
                context.applicationContext.getSystemService(
                    Context.POWER_SERVICE
                ) as PowerManager

            acquireCpuWakeLock(powerManager)
            updateProximityWakeLock(powerManager)

            Log.d(
                TAG,
                "Power management activated: proximity=$enableProximity"
            )
        }
    }

    /**
     * Updates only the proximity wake lock.
     *
     * Use this whenever these states change:
     * - local camera
     * - remote camera
     * - local screen sharing
     * - remote screen sharing
     *
     * Speaker mode and audio routing must not control proximity.
     */
    fun setProximityEnabled(
        context: Context,
        enabled: Boolean
    ) {
        synchronized(lock) {
            proximityEnabled = enabled

            val powerManager =
                context.applicationContext.getSystemService(
                    Context.POWER_SERVICE
                ) as PowerManager

            updateProximityWakeLock(powerManager)

            Log.d(
                TAG,
                "Proximity state updated: enabled=$enabled"
            )
        }
    }

    /**
     * Releases all power resources when the call finishes.
     */
    fun deactivate() {
        synchronized(lock) {
            callActive = false
            proximityEnabled = false

            releaseProximityWakeLock()
            releaseCpuWakeLock()

            proximityWakeLock = null
            cpuWakeLock = null

            Log.d(
                TAG,
                "All call wake locks released"
            )
        }
    }

    private fun acquireCpuWakeLock(
        powerManager: PowerManager
    ) {
        if (cpuWakeLock == null) {
            cpuWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                CPU_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
            }
        }

        val wakeLock = cpuWakeLock ?: return

        if (wakeLock.isHeld) {
            return
        }

        try {
            wakeLock.acquire()

            Log.d(
                TAG,
                "CPU wake lock acquired"
            )
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Unable to acquire CPU wake lock",
                exception
            )
        }
    }

    private fun updateProximityWakeLock(
        powerManager: PowerManager
    ) {
        val shouldEnable =
            callActive && proximityEnabled

        if (!shouldEnable) {
            releaseProximityWakeLock()
            return
        }

        val supported =
            powerManager.isWakeLockLevelSupported(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK
            )

        if (!supported) {
            Log.w(
                TAG,
                "Device does not support proximity wake lock"
            )
            return
        }

        if (proximityWakeLock == null) {
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                PROXIMITY_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
            }
        }

        val wakeLock = proximityWakeLock ?: return

        if (wakeLock.isHeld) {
            return
        }

        try {
            wakeLock.acquire()

            Log.d(
                TAG,
                "Proximity wake lock acquired"
            )
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Unable to acquire proximity wake lock",
                exception
            )
        }
    }

    private fun releaseCpuWakeLock() {
        val wakeLock = cpuWakeLock ?: return

        if (!wakeLock.isHeld) {
            return
        }

        try {
            wakeLock.release()

            Log.d(
                TAG,
                "CPU wake lock released"
            )
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Unable to release CPU wake lock",
                exception
            )
        }
    }

    private fun releaseProximityWakeLock() {
        val wakeLock = proximityWakeLock ?: return

        if (!wakeLock.isHeld) {
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                wakeLock.release(
                    PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY
                )
            } else {
                wakeLock.release()
            }

            Log.d(
                TAG,
                "Proximity wake lock released"
            )
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Unable to release proximity wake lock",
                exception
            )
        }
    }
}
