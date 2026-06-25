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

            val shouldShowNotification =
                data?.getBoolean(
                    CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW,
                    true
                ) ?: false

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                action in foregroundActions &&
                shouldShowNotification
            ) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(
                context,
                CallkitNotificationService::class.java
            )

            context.stopService(intent)
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
        Log.d(TAG, "Call notification service created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val action = intent?.action

        /*
         * START_STICKY may restart the service with a null Intent.
         * For a normal Recent Apps swipe, the existing service should
         * remain alive because onTaskRemoved does not stop it.
         */
        if (action == null) {
            Log.w(TAG, "Service restarted with null action")
            return START_STICKY
        }

        val bundle = intent.getBundleExtra(
            CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA
        )

        if (bundle == null) {
            Log.w(TAG, "Missing call notification bundle")
            return START_STICKY
        }

        when (action) {
            CallkitConstants.ACTION_CALL_START -> {
                val shouldShow = bundle.getBoolean(
                    CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW,
                    true
                )

                if (shouldShow) {
                    getCallkitNotificationManager()
                        ?.createNotificationChanel(bundle)

                    showOngoingCallNotification(bundle)
                } else {
                    stopSelf()
                }
            }

            CallkitConstants.ACTION_CALL_ACCEPT -> {
                getCallkitNotificationManager()
                    ?.clearIncomingNotification(bundle, true)

                val shouldShow = bundle.getBoolean(
                    CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW,
                    true
                )

                if (shouldShow) {
                    showOngoingCallNotification(bundle)
                } else {
                    stopSelf()
                }
            }

            else -> {
                Log.d(TAG, "Unknown action: $action")
            }
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun showOngoingCallNotification(bundle: Bundle) {
        val manager = getCallkitNotificationManager()

        if (manager == null) {
            Log.e(TAG, "CallkitNotificationManager is unavailable")
            return
        }

        val callkitNotification =
            manager.getOnGoingCallNotification(bundle, false)

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

            if (isVideoCall && cameraGranted) {
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
            } catch (securityException: SecurityException) {
                /*
                 * A microphone/camera FGS can be rejected when Android
                 * considers the app ineligible for while-in-use access.
                 * Keep the call notification alive using phoneCall.
                 */
                Log.e(
                    TAG,
                    "Media FGS type rejected; using phoneCall only",
                    securityException
                )

                startForeground(
                    callkitNotification.id,
                    callkitNotification.notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            }
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            startForeground(
                callkitNotification.id,
                callkitNotification.notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(
                callkitNotification.id,
                callkitNotification.notification
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        Log.d(
            TAG,
            "App removed from Recents; active call service continues"
        )

        // Important:
        // Do not call stopForeground().
        // Do not call stopSelf().
    }

    override fun onDestroy() {
        Log.d(TAG, "Call notification service destroyed")
        super.onDestroy()

        /*
         * Do not remove the notification here unless the call
         * has actually ended.
         */
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
