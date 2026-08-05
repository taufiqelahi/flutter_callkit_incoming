package com.hiennv.flutter_callkit_incoming

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log

class InAppCallManager(
    context: Context,
) {
    companion object {
        private const val TAG = "InAppCallManager"

        /*
         * Android 13+:
         * PlnZe/plugin provides the incoming-call UI.
         */
        private const val SELF_MANAGED_ACCOUNT_ID =
            "plnze_self_managed_calls"

        /*
         * Android 6–12L:
         * Android Telecom/default Phone app manages the call UI.
         */
        private const val MANAGED_ACCOUNT_ID =
            "plnze_managed_calls"

        const val EXTRA_TELECOM_CALLKIT_DATA =
            "flutter_callkit_incoming.extra.TELECOM_CALLKIT_DATA"
    }

    private val appContext =
        context.applicationContext

    private val telecomManager: TelecomManager
        get() = appContext.getSystemService(
            Context.TELECOM_SERVICE,
        ) as TelecomManager

    /*
     * Android 12L is API 32.
     *
     * Android 6–12L:
     * managed Telecom account.
     *
     * Android 13+:
     * self-managed Telecom account.
     */
    private fun isLegacyManagedMode(): Boolean {
        return Build.VERSION.SDK_INT <=
                Build.VERSION_CODES.S_V2
    }

    fun getPhoneAccountHandle(): PhoneAccountHandle {
        val accountId = if (isLegacyManagedMode()) {
            MANAGED_ACCOUNT_ID
        } else {
            SELF_MANAGED_ACCOUNT_ID
        }

        return PhoneAccountHandle(
            ComponentName(
                appContext,
                CallkitConnectionService::class.java,
            ),
            accountId,
        )
    }

    fun registerPhoneAccount(): Boolean {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.M
        ) {
            Log.w(
                TAG,
                "Android Telecom requires Android 6 or later",
            )

            return false
        }

        return try {
            val capabilities =
                if (isLegacyManagedMode()) {
                    /*
                     * Android 6–12L:
                     *
                     * Let the system Telecom/Phone app
                     * manage the call UI.
                     */
                    PhoneAccount.CAPABILITY_CALL_PROVIDER
                } else {
                    /*
                     * Android 13+:
                     *
                     * Plugin provides its own incoming
                     * call notification and activity.
                     */
                    PhoneAccount.CAPABILITY_SELF_MANAGED
                }

            val builder = PhoneAccount.builder(
                getPhoneAccountHandle(),
                "PlnZe Calls",
            )
                .setCapabilities(capabilities)
                .setSupportedUriSchemes(
                    listOf(
                        PhoneAccount.SCHEME_SIP,
                        PhoneAccount.SCHEME_TEL,
                    ),
                )

            /*
             * Inform Telecom that this account can
             * support video calls.
             */
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {
                builder.setCapabilities(
                    capabilities or
                            PhoneAccount
                                .CAPABILITY_SUPPORTS_VIDEO_CALLING,
                )
            }

            telecomManager.registerPhoneAccount(
                builder.build(),
            )

            Log.d(
                TAG,
                "PhoneAccount registered: " +
                        "managed=${isLegacyManagedMode()}, " +
                        "account=${getPhoneAccountHandle().id}",
            )

            true
        } catch (error: SecurityException) {
            Log.e(
                TAG,
                "PhoneAccount registration security error",
                error,
            )

            false
        } catch (error: Exception) {
            Log.e(
                TAG,
                "PhoneAccount registration failed",
                error,
            )

            false
        }
    }

    fun isPhoneAccountEnabled(): Boolean {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.M
        ) {
            return false
        }

        return try {
            val account =
                telecomManager.getPhoneAccount(
                    getPhoneAccountHandle(),
                )

            val enabled =
                account?.isEnabled == true

            Log.d(
                TAG,
                "PhoneAccount enabled=$enabled " +
                        "account=${getPhoneAccountHandle().id}",
            )

            enabled
        } catch (error: Exception) {
            Log.e(
                TAG,
                "Unable to read PhoneAccount state",
                error,
            )

            false
        }
    }

    fun openCallingAccountSettings(): Boolean {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.M
        ) {
            return false
        }

        return try {
            val intent = Intent(
                TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS,
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK,
                )
            }

            appContext.startActivity(intent)

            true
        } catch (error: Exception) {
            Log.e(
                TAG,
                "Unable to open calling account settings",
                error,
            )

            false
        }
    }

    @SuppressLint("MissingPermission")
    fun reportIncomingCall(
        data: Bundle,
    ): Boolean {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.M
        ) {
            Log.w(
                TAG,
                "Incoming Telecom calls require Android 6+",
            )

            return false
        }

        if (!registerPhoneAccount()) {
            Log.e(
                TAG,
                "Incoming call rejected: " +
                        "PhoneAccount registration failed",
            )

            return false
        }

        /*
         * Managed accounts on Android 6–12L must
         * first be enabled by the user from the
         * Phone/Calling Accounts settings.
         */
        if (
            isLegacyManagedMode() &&
            !isPhoneAccountEnabled()
        ) {
            Log.w(
                TAG,
                "Incoming call rejected: " +
                        "PlnZe managed PhoneAccount is disabled",
            )

            return false
        }

        val callkitId = data.getString(
            CallkitConstants.EXTRA_CALLKIT_ID,
            "",
        ).trim()

        if (callkitId.isEmpty()) {
            Log.e(
                TAG,
                "Incoming call rejected: " +
                        "CallKit ID is missing",
            )

            return false
        }

        val callerHandle = data.getString(
            CallkitConstants.EXTRA_CALLKIT_HANDLE,
            "unknown",
        )
            .trim()
            .ifEmpty {
                "unknown"
            }

        val callType = data.getInt(
            CallkitConstants.EXTRA_CALLKIT_TYPE,
            0,
        )

        return try {
            /*
             * This bundle will later arrive in:
             *
             * CallkitConnectionService
             *     .onCreateIncomingConnection()
             */
            val extras = Bundle().apply {
                putBundle(
                    EXTRA_TELECOM_CALLKIT_DATA,
                    Bundle(data),
                )

                putParcelable(
                    TelecomManager
                        .EXTRA_INCOMING_CALL_ADDRESS,
                    Uri.fromParts(
                        PhoneAccount.SCHEME_SIP,
                        callerHandle,
                        null,
                    ),
                )

                putInt(
                    TelecomManager
                        .EXTRA_INCOMING_VIDEO_STATE,
                    if (callType > 0) {
                        VideoProfile
                            .STATE_BIDIRECTIONAL
                    } else {
                        VideoProfile
                            .STATE_AUDIO_ONLY
                    },
                )
            }

            telecomManager.addNewIncomingCall(
                getPhoneAccountHandle(),
                extras,
            )

            Log.d(
                TAG,
                "Incoming call reported to Telecom: " +
                        "callkitId=$callkitId, " +
                        "caller=$callerHandle, " +
                        "managed=${isLegacyManagedMode()}",
            )

            true
        } catch (error: SecurityException) {
            /*
             * Managed accounts throw a SecurityException
             * when the account has not been enabled by
             * the user.
             */
            Log.e(
                TAG,
                "addNewIncomingCall security error. " +
                        "Check whether PlnZe Calls is enabled.",
                error,
            )

            false
        } catch (error: Exception) {
            Log.e(
                TAG,
                "addNewIncomingCall failed",
                error,
            )

            false
        }
    }

    fun unregisterPhoneAccount() {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.M
        ) {
            return
        }

        try {
            telecomManager.unregisterPhoneAccount(
                getPhoneAccountHandle(),
            )

            Log.d(
                TAG,
                "PhoneAccount unregistered",
            )
        } catch (error: Exception) {
            Log.e(
                TAG,
                "PhoneAccount unregister failed",
                error,
            )
        }
    }
}