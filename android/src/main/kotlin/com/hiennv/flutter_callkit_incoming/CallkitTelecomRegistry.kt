package com.hiennv.flutter_callkit_incoming

import android.telecom.DisconnectCause
import java.util.concurrent.ConcurrentHashMap

object CallkitTelecomRegistry {
    private val connections =
        ConcurrentHashMap<String, CallkitTelecomConnection>()

    fun getCallkitId(data: android.os.Bundle): String {
        return data.getString(
            CallkitConstants.EXTRA_CALLKIT_ID,
            "",
        ).trim()
    }

    fun register(
        callkitId: String,
        connection: CallkitTelecomConnection,
    ) {
        if (callkitId.isEmpty()) return

        connections[callkitId] = connection
    }

    fun unregister(callkitId: String) {
        if (callkitId.isEmpty()) return

        connections.remove(callkitId)
    }

    fun answer(callkitId: String) {
        connections[callkitId]?.answerFromAppUi()
    }

    fun reject(callkitId: String) {
        connections[callkitId]?.finishFromAppUi(
            DisconnectCause.REJECTED,
        )
    }

    fun disconnect(callkitId: String) {
        connections[callkitId]?.finishFromAppUi(
            DisconnectCause.LOCAL,
        )
    }

    fun timeout(callkitId: String) {
        connections[callkitId]?.finishFromAppUi(
            DisconnectCause.MISSED,
        )
    }

    fun clearAll() {
        val activeConnections =
            connections.values.toList()

        connections.clear()

        activeConnections.forEach { connection ->
            connection.finishFromAppUi(
                DisconnectCause.LOCAL,
            )
        }
    }
}