package ai.opencode.server.live

import ai.opencode.server.OpenCodeServer
import ai.opencode.server.internal.bool
import ai.opencode.server.internal.lng
import ai.opencode.server.internal.obj
import ai.opencode.server.internal.opt
import ai.opencode.server.internal.req
import ai.opencode.server.internal.str
import ai.opencode.server.model.OpenCodePushBell
import ai.opencode.server.model.OpenCodePushDevice
import ai.opencode.server.model.OpenCodePushLevel
import ai.opencode.server.model.OpenCodePushRegistration
import ai.opencode.server.model.OpenCodePushState
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.RetryPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val PUSH = "/api/experimental/push"

@Serializable
private data class DeviceBody(
    val callbackURL: String,
    val expiresAt: Long,
    val publicKey: String,
    val platform: String,
    val name: String,
    val settings: Settings,
) {
    @Serializable
    data class Settings(val default: OpenCodePushLevel)
}

@Serializable
private data class DeviceReply(val data: Registered) {
    @Serializable
    data class Registered(val publicKey: String)
}

/** Registers this phone and returns the server's public key. The same registration twice gives the same answer. */
internal suspend fun requestRegisterPushDevice(server: OpenCodeServer, id: String, registration: OpenCodePushRegistration): String {
    val body = DeviceBody(
        callbackURL = registration.callbackURL,
        expiresAt = registration.expiresAt,
        publicKey = registration.publicKey,
        platform = "android",
        name = registration.name,
        settings = DeviceBody.Settings(registration.defaultLevel),
    )
    val reply = request<DeviceReply>(
        "$PUSH/device/$id", method = "PUT", body = encodedBody(body),
        idempotency = RequestIdempotency.Idempotent, retry = RetryPolicy.once, server = server,
    )
    return reply.data.publicKey
}

internal suspend fun requestRemovePushDevice(server: OpenCodeServer, id: String) {
    perform("$PUSH/device/$id", method = "DELETE", idempotency = RequestIdempotency.Idempotent, retry = RetryPolicy.once, server = server)
}

internal suspend fun requestPushState(server: OpenCodeServer): OpenCodePushState {
    val response = perform(PUSH, server = server)
    return decoded(response.data, decode = ::decodePushState)
}

/** This phone's own bell for one session; null removes it. */
internal suspend fun requestSetPushSessionBell(server: OpenCodeServer, id: String, sessionID: String, bell: OpenCodePushBell?) {
    val path = "$PUSH/device/$id/session/$sessionID"
    if (bell == null) {
        perform(path, method = "DELETE", idempotency = RequestIdempotency.Idempotent, retry = RetryPolicy.once, server = server)
        return
    }
    perform(path, method = "PUT", body = encodedBody(bell), idempotency = RequestIdempotency.Idempotent, retry = RetryPolicy.once, server = server)
}

internal fun decodePushState(element: JsonElement): OpenCodePushState {
    val root = element.obj().req("data") { it.obj() }
    val settings = root.opt("settings") { it.obj() }
    val devices = mutableListOf<OpenCodePushDevice>()
    val deviceList = root["devices"] as? JsonArray
    if (deviceList != null) {
        for (item in deviceList) {
            val device = item.obj()
            val default = device.opt("settings") { it.obj().opt("default") { level -> level.str() } }
            devices += OpenCodePushDevice(
                id = device.req("id") { it.str() },
                name = device.opt("name") { it.str() } ?: "",
                platform = device.opt("platform") { it.str() } ?: "",
                expiresAt = device.opt("expiresAt") { it.lng() } ?: 0,
                defaultLevel = default?.let(OpenCodePushLevel::of) ?: OpenCodePushLevel.all,
                paused = device.opt("paused") { it.bool() } ?: false,
                sessions = decodeBells(device["sessions"]),
            )
        }
    }
    return OpenCodePushState(
        paused = settings?.opt("paused") { it.bool() } ?: false,
        pausedUntil = settings?.opt("until") { it.lng() },
        devices = devices,
        sessions = decodeBells(root["sessions"]),
    )
}

/** Bells with a level this app doesn't know are left out. */
private fun decodeBells(element: JsonElement?): Map<String, OpenCodePushBell> {
    val bells = LinkedHashMap<String, OpenCodePushBell>()
    val map = element as? JsonObject ?: return bells
    for ((sessionID, value) in map) {
        val bell = value.obj()
        val level = OpenCodePushLevel.of(bell.req("level") { it.str() }) ?: continue
        bells[sessionID] = OpenCodePushBell(level, bell.opt("until") { it.lng() })
    }
    return bells
}
