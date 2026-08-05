package com.hiennv.flutter_callkit_incoming

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.M)
class InAppCallManager(
    context: Context,
) {
    companion object {
        private const val TAG = "InAppCallManager"

        private const val ACCOUNT_ID =
            "flutter_callkit_incoming_self_managed_account"

        const val EXTRA_TELECOM_CALLKIT_DATA =
            "flutter_callkit_incoming.extra.TELECOM_CALLKIT_DATA"
    }

    private val appContext = context.applicationContext

    private val telecomManager: TelecomManager
        get() = appContext.getSystemService(
            Context.TELECOM_SERVICE,
        ) as TelecomManager

    fun getPhoneAccountHandle(): PhoneAccountHandle {
        return PhoneAccountHandle(
            ComponentName(
                appContext,
                CallkitConnectionService::class.java,
            ),
            ACCOUNT_ID,
        )
    }

    fun registerPhoneAccount(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }

        return try {
            val handle = getPhoneAccountHandle()

            val phoneAccount = PhoneAccount.builder(
                handle,
                "PlnZe Calls",
            )
                .setCapabilities(
                    PhoneAccount.CAPABILITY_SELF_MANAGED,
                )
                .setSupportedUriSchemes(
                    listOf(PhoneAccount.SCHEME_SIP),
                )
                .build()

            telecomManager.registerPhoneAccount(phoneAccount)

            Log.d(
                TAG,
                "Self-managed PhoneAccount registered",
            )

            true
        } catch (error: Exception) {
            Log.e(
                TAG,
                "PhoneAccount registration failed",
                error,
            )

            false
        }
    }

    fun unregisterPhoneAccount() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        try {
            telecomManager.unregisterPhoneAccount(
                getPhoneAccountHandle(),
            )
        } catch (error: Exception) {
            Log.e(
                TAG,
                "PhoneAccount unregister failed",
                error,
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun reportIncomingCall(data: Bundle): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }

        if (!registerPhoneAccount()) {
            return false
        }

        val callkitId = data.getString(
            CallkitConstants.EXTRA_CALLKIT_ID,
            "",
        ).trim()

        if (callkitId.isEmpty()) {
            Log.e(TAG, "Incoming Telecom call ID is empty")
            return false
        }

        val callerHandle = data.getString(
            CallkitConstants.EXTRA_CALLKIT_HANDLE,
            "unknown",
        ).trim().ifEmpty {
            "unknown"
        }

        val callType = data.getInt(
            CallkitConstants.EXTRA_CALLKIT_TYPE,
            0,
        )

        val phoneAccountHandle = getPhoneAccountHandle()

        return try {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !telecomManager.isIncomingCallPermitted(
                    phoneAccountHandle,
                )
            ) {
                Log.w(
                    TAG,
                    "Telecom rejected incoming call permission",
                )

                return false
            }

            val telecomExtras = Bundle().apply {
                putBundle(
                    EXTRA_TELECOM_CALLKIT_DATA,
                    Bundle(data),
                )

                putParcelable(
                    TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                    Uri.fromParts(
                        PhoneAccount.SCHEME_SIP,
                        callerHandle,
                        null,
                    ),
                )

                putInt(
                    TelecomManager.EXTRA_INCOMING_VIDEO_STATE,
                    if (callType > 0) {
                        VideoProfile.STATE_BIDIRECTIONAL
                    } else {
                        VideoProfile.STATE_AUDIO_ONLY
                    },
                )
            }

            telecomManager.addNewIncomingCall(
                phoneAccountHandle,
                telecomExtras,
            )

            Log.d(
                TAG,
                "Incoming call reported to Telecom: $callkitId",
            )

            true
        } catch (error: SecurityException) {
            Log.e(
                TAG,
                "Telecom security error",
                error,
            )

            false
        } catch (error: Exception) {
            Log.e(
                TAG,
                "Unable to report Telecom incoming call",
                error,
            )

            false
        }
    }
}