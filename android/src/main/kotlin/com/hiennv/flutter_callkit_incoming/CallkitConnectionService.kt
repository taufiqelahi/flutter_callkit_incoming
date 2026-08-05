package com.hiennv.flutter_callkit_incoming

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.VideoProfile
import android.telecom.TelecomManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class CallkitConnectionService : ConnectionService() {
    companion object {
        private const val TAG =
            "CallkitConnectionService"
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val data = request
            ?.extras
            ?.getBundle(
                InAppCallManager.EXTRA_TELECOM_CALLKIT_DATA,
            )

        if (data == null) {
            Log.e(
                TAG,
                "Telecom incoming call data missing",
            )

            return Connection.createFailedConnection(
                DisconnectCause(
                    DisconnectCause.ERROR,
                ),
            )
        }

        val callkitId =
            CallkitTelecomRegistry.getCallkitId(data)

        if (callkitId.isEmpty()) {
            return Connection.createFailedConnection(
                DisconnectCause(
                    DisconnectCause.ERROR,
                ),
            )
        }

        val connection = CallkitTelecomConnection(
            context = applicationContext,
            data = Bundle(data),
            callkitId = callkitId,
        )

        CallkitTelecomRegistry.register(
            callkitId,
            connection,
        )

        Log.d(
            TAG,
            "Incoming Telecom connection created: $callkitId",
        )

        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        super.onCreateIncomingConnectionFailed(
            connectionManagerPhoneAccount,
            request,
        )

        val data = request
            ?.extras
            ?.getBundle(
                InAppCallManager.EXTRA_TELECOM_CALLKIT_DATA,
            )
            ?: return

        applicationContext.sendBroadcast(
            CallkitIncomingBroadcastReceiver
                .getIntentTimeout(
                    applicationContext,
                    data,
                ),
        )
    }
}

class CallkitTelecomConnection(
    private val context: Context,
    private val data: Bundle,
    private val callkitId: String,
) : Connection() {
    companion object {
        private const val TAG =
            "CallkitTelecomConnection"
    }

    private val finished = AtomicBoolean(false)

    init {
        val callerName = data.getString(
            CallkitConstants.EXTRA_CALLKIT_NAME_CALLER,
            "Incoming call",
        ).orEmpty().ifBlank {
            "Incoming call"
        }

        val callerHandle = data.getString(
            CallkitConstants.EXTRA_CALLKIT_HANDLE,
            "unknown",
        ).orEmpty().ifBlank {
            "unknown"
        }

        val callType = data.getInt(
            CallkitConstants.EXTRA_CALLKIT_TYPE,
            0,
        )

        setAddress(
            Uri.fromParts(
                PhoneAccount.SCHEME_SIP,
                callerHandle,
                null,
            ),
            TelecomManager.PRESENTATION_ALLOWED,
        )

        setCallerDisplayName(
            callerName,
            TelecomManager.PRESENTATION_ALLOWED,
        )

        connectionProperties =
            PROPERTY_SELF_MANAGED

        setAudioModeIsVoip(true)

        videoState = if (callType > 0) {
            VideoProfile.STATE_BIDIRECTIONAL
        } else {
            VideoProfile.STATE_AUDIO_ONLY
        }

        setRinging()
    }

    override fun onShowIncomingCallUi() {
        if (finished.get()) return

        Log.d(
            TAG,
            "Telecom requested incoming call UI",
        )

        /*
         * Reuse the plugin's existing incoming-call pipeline:
         *
         * BroadcastReceiver
         * -> CallkitNotificationManager
         * -> CallStyle/full-screen intent
         * -> CallkitIncomingActivity
         */
        context.sendBroadcast(
            CallkitIncomingBroadcastReceiver
                .getIntentIncoming(
                    context,
                    Bundle(data),
                ),
        )
    }

    override fun onAnswer() {
        answerFromSystem()
    }

    override fun onAnswer(videoState: Int) {
        this.videoState = videoState

        answerFromSystem()
    }

    private fun answerFromSystem() {
        if (finished.get()) return

        setActive()

        context.sendBroadcast(
            CallkitIncomingBroadcastReceiver
                .getIntentAccept(
                    context,
                    Bundle(data),
                ),
        )
    }

    override fun onReject() {
        if (!finishConnection(
                DisconnectCause.REJECTED,
            )
        ) {
            return
        }

        context.sendBroadcast(
            CallkitIncomingBroadcastReceiver
                .getIntentDecline(
                    context,
                    Bundle(data),
                ),
        )
    }

    override fun onDisconnect() {
        if (!finishConnection(
                DisconnectCause.LOCAL,
            )
        ) {
            return
        }

        context.sendBroadcast(
            CallkitIncomingBroadcastReceiver
                .getIntentEnded(
                    context,
                    Bundle(data),
                ),
        )
    }

    override fun onAbort() {
        if (!finishConnection(
                DisconnectCause.CANCELED,
            )
        ) {
            return
        }

        context.sendBroadcast(
            CallkitIncomingBroadcastReceiver
                .getIntentEnded(
                    context,
                    Bundle(data),
                ),
        )
    }

    override fun onSilence() {
        FlutterCallkitIncomingPlugin
            .getInstance()
            ?.getCallkitSoundPlayerManager()
            ?.stop()
    }

    fun answerFromAppUi() {
        if (finished.get()) return

        setActive()
    }

    fun finishFromAppUi(
        disconnectCode: Int,
    ) {
        finishConnection(disconnectCode)
    }

    private fun finishConnection(
        disconnectCode: Int,
    ): Boolean {
        if (!finished.compareAndSet(false, true)) {
            return false
        }

        try {
            setDisconnected(
                DisconnectCause(disconnectCode),
            )
        } finally {
            destroy()

            CallkitTelecomRegistry.unregister(
                callkitId,
            )
        }

        return true
    }
}