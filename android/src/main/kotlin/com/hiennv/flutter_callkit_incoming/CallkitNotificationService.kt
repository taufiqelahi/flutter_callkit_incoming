package com.hiennv.flutter_callkit_incoming

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

class CallkitNotificationService : Service() {

    companion object {
        private const val TAG = "CallkitNotification"

        private val foregroundActions = setOf(
            CallkitConstants.ACTION_CALL_START,
            CallkitConstants.ACTION_CALL_ACCEPT
        )

        fun startServiceWithAction(
            context: Context,
            action: String,
            data: Bundle?
        ) {
            if (data == null) {
                Log.w(
                    TAG,
                    "Service was not started because call data is missing"
                )

                CallkitPowerManager.deactivate()
                return
            }

            val shouldShowNotification =
                data.getBoolean(
                    CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW,
                    true
                )

            /*
             * A foreground service must show a notification.
             * Therefore, do not start this service when the ongoing-call
             * notification is disabled.
             */
            if (
                action in foregroundActions &&
                !shouldShowNotification
            ) {
                Log.d(
                    TAG,
                    "Service was not started because ongoing notification is disabled"
                )

                CallkitPowerManager.deactivate()
                return
            }

            val intent = Intent(
                context,
                CallkitNotificationService::class.java
            ).apply {
                this.action = action

                putExtra(
                    CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA,
                    data
                )
            }

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                action in foregroundActions
            ) {
                ContextCompat.startForegroundService(
                    context,
                    intent
                )
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            /*
             * Release immediately instead of waiting for Android to call
             * onDestroy().
             */
            CallkitPowerManager.deactivate()

            context.stopService(
                Intent(
                    context,
                    CallkitNotificationService::class.java
                )
            )
        }
    }

    private fun getCallkitNotificationManager():
        CallkitNotificationManager? {
        return FlutterCallkitIncomingPlugin
            .getInstance()
            ?.getCallkitNotificationManager()
    }

    override fun onCreate() {
        super.onCreate()

        Log.d(
            TAG,
            "Call notification service created"
        )
    }

private fun activateCallPowerManagement() {
    /*
     * The general CPU wake lock remains active for the whole call.
     *
     * Proximity starts disabled because the original call type does
     * not represent the current runtime media state.
     *
     * Flutter enables or disables proximity after checking:
     * - local camera
     * - remote camera
     * - local screen share
     * - remote screen share
     *
     * Speaker mode is intentionally ignored.
     */
    CallkitPowerManager.activate(
        context = applicationContext,
        enableProximity = false
    )
}

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val action = intent?.action

        /*
         * START_STICKY can restart the service without the original Intent.
         * Without the call Bundle, this service cannot safely rebuild the
         * foreground notification.
         */
        if (action == null) {
            Log.w(
                TAG,
                "Service restarted without an action"
            )

            CallkitPowerManager.deactivate()
            stopSelf(startId)

            return START_NOT_STICKY
        }

        val bundle = intent.getBundleExtra(
            CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA
        )

        if (bundle == null) {
            Log.w(
                TAG,
                "Missing call notification bundle"
            )

            CallkitPowerManager.deactivate()
            stopSelf(startId)

            return START_NOT_STICKY
        }

        val startedSuccessfully =
            when (action) {
                CallkitConstants.ACTION_CALL_START -> {
                    getCallkitNotificationManager()
                        ?.createNotificationChanel(bundle)

                    val foregroundStarted =
                        showOngoingCallNotification(bundle)

                    if (foregroundStarted) {
                        /*
                         * Outgoing call service successfully became a
                         * foreground service.
                         */
                        activateCallPowerManagement(bundle)
                    }

                    foregroundStarted
                }

                CallkitConstants.ACTION_CALL_ACCEPT -> {
                    getCallkitNotificationManager()
                        ?.clearIncomingNotification(
                            bundle,
                            true
                        )

                    val foregroundStarted =
                        showOngoingCallNotification(bundle)

                    if (foregroundStarted) {
                        /*
                         * Accepted incoming call successfully became a
                         * foreground service.
                         */
                        activateCallPowerManagement(bundle)
                    }

                    foregroundStarted
                }

                else -> {
                    Log.w(
                        TAG,
                        "Unknown service action: $action"
                    )

                    false
                }
            }

        if (!startedSuccessfully) {
            CallkitPowerManager.deactivate()
            stopSelf(startId)

            return START_NOT_STICKY
        }

        return START_STICKY
    }

    /**
     * Starts this service in foreground mode.
     *
     * Returns true only when startForeground() succeeds.
     * Wake locks must not be acquired when foreground startup fails.
     */
    @SuppressLint("MissingPermission")
    private fun showOngoingCallNotification(
        bundle: Bundle
    ): Boolean {
        val manager =
            getCallkitNotificationManager()

        if (manager == null) {
            Log.e(
                TAG,
                "CallkitNotificationManager is unavailable"
            )

            return false
        }

        val callkitNotification =
            manager.getOnGoingCallNotification(
                bundle,
                false
            ) ?: run {
                Log.e(
                    TAG,
                    "Could not create ongoing call notification"
                )

                return false
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var serviceTypes =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL

            val microphoneGranted =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

            val cameraGranted =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

            val isVideoCall =
                bundle.getInt(
                    CallkitConstants.EXTRA_CALLKIT_TYPE,
                    0
                ) == 1

            if (microphoneGranted) {
                serviceTypes =
                    serviceTypes or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }

            if (
                isVideoCall &&
                cameraGranted
            ) {
                serviceTypes =
                    serviceTypes or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }

            try {
                startForeground(
                    callkitNotification.id,
                    callkitNotification.notification,
                    serviceTypes
                )

                Log.d(
                    TAG,
                    "Foreground call service started with types=$serviceTypes"
                )

                return true
            } catch (
                securityException: SecurityException
            ) {
                Log.e(
                    TAG,
                    "Media foreground types rejected; trying phoneCall only",
                    securityException
                )

                return try {
                    startForeground(
                        callkitNotification.id,
                        callkitNotification.notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )

                    Log.d(
                        TAG,
                        "Foreground service started with phoneCall fallback"
                    )

                    true
                } catch (
                    fallbackException: Exception
                ) {
                    Log.e(
                        TAG,
                        "Unable to start phone-call foreground service",
                        fallbackException
                    )

                    false
                }
            } catch (
                exception: Exception
            ) {
                Log.e(
                    TAG,
                    "Unable to start call foreground service",
                    exception
                )

                return false
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                startForeground(
                    callkitNotification.id,
                    callkitNotification.notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )

                Log.d(
                    TAG,
                    "Foreground phone-call service started"
                )

                true
            } catch (
                exception: Exception
            ) {
                Log.e(
                    TAG,
                    "Unable to start phone-call foreground service",
                    exception
                )

                false
            }
        }

        return try {
            startForeground(
                callkitNotification.id,
                callkitNotification.notification
            )

            Log.d(
                TAG,
                "Foreground call service started"
            )

            true
        } catch (
            exception: Exception
        ) {
            Log.e(
                TAG,
                "Unable to start foreground service",
                exception
            )

            false
        }
    }

    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {
        super.onTaskRemoved(rootIntent)

        Log.d(
            TAG,
            "App removed from Recents; active call service continues"
        )

        /*
         * Do not stop the foreground service here.
         * Removing the app from Recent Apps must not end an active call.
         */
    }

    override fun onDestroy() {
        Log.d(
            TAG,
            "Call notification service destroyed"
        )

        /*
         * Safety fallback.
         * deactivate() should check whether each wake lock is currently held.
         */
        CallkitPowerManager.deactivate()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }
}
