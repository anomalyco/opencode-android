package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.BackgroundTaskClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.InAppNoticeMode
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.drawer.notices.DeliveredNotificationsClient
import ai.opencode.feature.drawer.notices.InAppNoticeSignal
import ai.opencode.feature.session.InAppNotice
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.system.background.BackgroundRunServiceClient
import ai.opencode.feature.system.notifications.SessionPushNotifications
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.CancellationException
import ai.opencode.core.strings.localized
import ai.opencode.feature.composer.PermissionSummary
import ai.opencode.feature.composer.RequestDockAnswer
import ai.opencode.feature.system.notifications.SessionNotificationReply
import ai.opencode.feature.system.notifications.SessionNotificationRequest
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeFormFieldType
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.replyForm
import ai.opencode.feature.composer.R as ComposerR
import ai.opencode.feature.system.R as SystemR
import ai.opencode.server.R as ServerR

/** What Stay Connected During Runs' service follows. */
internal data class BackgroundRunDemand(val wanted: Boolean, val isInBackground: Boolean)

internal val State.backgroundRunDemand: BackgroundRunDemand
    get() = BackgroundRunDemand(
        wanted = isStreaming && hasConnected && !credentialsRejected && activeSessionIDs.isNotEmpty(),
        isInBackground = connection.isInBackground,
    )

/**
 * Stay Connected During Runs (Experimental): the service runs while any session on this server
 * works. Android refuses to start it from the background, so a run first seen there goes without.
 */
internal fun syncBackgroundRuns(state: State): HomeReduced {
    val client = dependency(BackgroundRunServiceClient)
    val demand = state.backgroundRunDemand
    if (!dependency(ExperimentalFeatures)(ExperimentalFeature.backgroundRuns) || !demand.wanted) {
        return Reduced(state, Effect.run { client.stop() })
    }
    if (demand.isInBackground) return Reduced(state)
    return Reduced(state, Effect.run { client.start() })
}

internal val HomeSettingsFeature.Action.switchesBackgroundRuns: Boolean
    get() {
        if (this is HomeSettingsFeature.Action.ExperimentalFeatureChanged) return feature == ExperimentalFeature.backgroundRuns
        if (this is HomeSettingsFeature.Action.ExperimentalFeatureApplied) return feature == ExperimentalFeature.backgroundRuns
        return false
    }

/** What Stay Connected During Runs' notifications read: Home's live state while its switch is on. */
internal data class BackgroundNoticeReading(
    val isOn: Boolean,
    val isHeld: Boolean,
    val isInBackground: Boolean,
    val input: InAppNoticeInput,
)

internal fun backgroundNoticeReading(state: State): BackgroundNoticeReading {
    if (!dependency(ExperimentalFeatures)(ExperimentalFeature.backgroundRuns)) {
        return BackgroundNoticeReading(isOn = false, isHeld = false, isInBackground = false, input = InAppNoticeInput.off)
    }
    return BackgroundNoticeReading(
        isOn = true,
        isHeld = state.connection.isBackgroundHeld,
        isInBackground = state.connection.isInBackground,
        input = liveNoticeInput(state, InAppNoticeMode.all),
    )
}

/**
 * While the app is away, notifies a session that starts needing the user or finishes a run since
 * the last live reading. Readings in the foreground only move the baseline, so what was pending
 * when the app left stays quiet, and a reconnect compares against the last reading before the drop.
 */
internal fun backgroundNoticesChanged(reading: BackgroundNoticeReading, state: State): HomeReduced {
    val baseline = state.backgroundNoticeBaseline
    val follows = reading.isOn && (reading.isHeld || (reading.isInBackground && baseline.isLive))
    if (!follows) {
        if (baseline == InAppNoticeSignal.none) return Reduced(state)
        return Reduced(state.copy(backgroundNoticeBaseline = InAppNoticeSignal.none))
    }
    val signal = reading.input.signal
    if (!signal.isLive) return Reduced(state)
    val s = state.copy(backgroundNoticeBaseline = signal)
    if (!reading.isInBackground) return Reduced(s)
    val effects = mutableListOf<Effect<Action>>()
    for ((event, _) in InAppNoticeSignal.events(from = baseline, to = signal)) {
        effects += postBackgroundNotice(s, event)
    }
    return Reduced(s, Effect.merge(effects))
}

private fun postBackgroundNotice(state: State, event: InAppNotice.Event): Effect<Action> {
    val delivered = dependency(DeliveredNotificationsClient)
    val client = dependency(OpenCodeClient)
    val background = dependency(BackgroundTaskClient)
    val server = state.server
    val sessionID = event.sessionID
    val isNeedsInput = event.kind == InAppNotice.Kind.needsInput
    val root = state.sessions.firstOrNull { it.id == sessionID }
    return Effect.run<Action> {
        val end = background.begin("Session notification")
        try {
            var status: String? = SessionPushNotifications.STATUS_NEEDS_INPUT
            if (!isNeedsInput) {
                val items = try {
                    client.transcript(server, sessionID, runOutcomeFetchLimit)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
                status = runOutcomeStatus(items)
            }
            if (status == null) {
                AppLog.stream.info { "background_notice_skipped session=$sessionID stopped" }
            } else if (isNeedsInput) {
                val request = if (root == null) null else notificationRequest(client, server, root)
                AppLog.stream.info { "background_notice_posted session=$sessionID status=$status answers=${request?.choices?.size ?: 0}" }
                delivered.postRequest(server.persistenceID, sessionID, event.title, request, null)
            } else {
                AppLog.stream.info { "background_notice_posted session=$sessionID status=$status" }
                delivered.post(server.persistenceID, sessionID, event.title, status)
            }
        } finally {
            end()
        }
    }.cancellable(HomeFeature.CancelID.BackgroundNotice(sessionID), cancelInFlight = true)
}

private const val runOutcomeFetchLimit = 20

/** A finished run's notification status from its transcript's tail; null when the user stopped it. */
internal fun runOutcomeStatus(items: List<OpenCodeTranscriptItem>?): String? {
    if (items == null) return SessionPushNotifications.STATUS_SUCCEEDED
    for (item in items.asReversed()) {
        if (item is OpenCodeTranscriptItem.UserText) break
        if (item is OpenCodeTranscriptItem.RunError) {
            if (item.error.isStop) return null
            return SessionPushNotifications.STATUS_FAILED
        }
    }
    return SessionPushNotifications.STATUS_SUCCEEDED
}

/** The one request a session's family waits on, with the answers a notification can offer; null when it's not exactly one. */
private suspend fun notificationRequest(client: OpenCodeClient, server: OpenCodeServer, root: OpenCodeSession): SessionNotificationRequest? {
    val pending = try {
        familyRequests(client, server, root)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        return null
    }
    if (pending.permissions.size + pending.forms.size != 1) return null
    val permission = pending.permissions.firstOrNull()
    if (permission != null) {
        val source = permission.source
        var call: OpenCodeToolCall? = null
        if (source != null) {
            val items = try {
                client.message(server, permission.sessionID, source.messageID)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
            call = RequestDockAnswer.toolCall(permission, listOf(items))
        }
        return permissionNotification(permission, call)
    }
    return formNotification(pending.forms.first())
}

private suspend fun familyRequests(client: OpenCodeClient, server: OpenCodeServer, root: OpenCodeSession): OpenCodePendingRequests {
    val family = client.sessionFamily(server, root)
    val ids = mutableListOf(root.id)
    for (session in family.sessions) {
        if (session.id !in ids) ids += session.id
    }
    return client.familyPendingRequests(server, ids, root.directory)
}

/** The permission card's caption and summary, with Allow Once and Deny; Always Allow needs the card's scope check. */
internal fun permissionNotification(permission: OpenCodePermissionRequest, call: OpenCodeToolCall?): SessionNotificationRequest {
    var heading = OpenCodeToolCall.displayName(permission.action)
    var description = call?.input?.obj?.get("description")?.string?.trim()
    if (description.isNullOrEmpty()) description = permission.filePreview?.first?.substringAfterLast('/')
    if (!description.isNullOrEmpty()) heading = "$heading · $description"
    val command = call?.input?.obj?.get("command")?.string?.ifEmpty { null }
    return SessionNotificationRequest(
        sessionID = permission.sessionID,
        requestID = permission.id,
        isPermission = true,
        heading = heading,
        detail = PermissionSummary(permission, command).text,
        choices = listOf(
            SessionNotificationRequest.Choice(localized(ComposerR.string.composer_request_allow_once), permissionOnce),
            SessionNotificationRequest.Choice(localized(ComposerR.string.composer_request_deny), permissionReject),
        ),
    )
}

/**
 * A form with one question: its choices as buttons when there are a few, an inline reply when it
 * takes typed text, or nothing to answer from the notification.
 */
internal fun formNotification(form: OpenCodeFormRequest): SessionNotificationRequest {
    val fields = form.activeFields(form.defaultAnswers)
    val field = fields.singleOrNull()
    var heading = form.title
    var detail = ""
    if (field != null) {
        val header = field.title?.trim()
        if (!header.isNullOrEmpty()) heading = header
        detail = field.description?.trim() ?: ""
    }
    val choices = mutableListOf<SessionNotificationRequest.Choice>()
    var textReply: SessionNotificationRequest.TextReply? = null
    if (field != null && field.type == OpenCodeFormFieldType.String) {
        val options = field.options ?: emptyList()
        if (options.size <= maxNotificationChoices) {
            for (option in options) {
                choices += SessionNotificationRequest.Choice(option.label, option.value)
            }
        }
        val takesText = options.isEmpty() || field.custom == true
        if (takesText && choices.size < maxNotificationChoices && options.size == choices.size) {
            textReply = SessionNotificationRequest.TextReply(
                localized(R.string.drawer_notification_answer),
                localized(ComposerR.string.composer_review_answer_placeholder),
            )
        }
    }
    return SessionNotificationRequest(
        sessionID = form.sessionID,
        requestID = form.id,
        isPermission = false,
        heading = heading,
        detail = detail,
        choices = choices,
        textReply = textReply,
    )
}

private const val maxNotificationChoices = 3
private const val permissionOnce = "once"
private const val permissionReject = "reject"

/**
 * Sends an answer given from a notification through the same client calls the request card uses,
 * after checking the request still waits. Success clears the notification; anything else puts a
 * short line on it so it never reads as answered.
 */
internal fun answerFromNotification(state: State, reply: SessionNotificationReply): HomeReduced {
    val delivered = dependency(DeliveredNotificationsClient)
    val client = dependency(OpenCodeClient)
    val background = dependency(BackgroundTaskClient)
    val server = state.server
    val root = state.sessions.firstOrNull { it.id == reply.rootSessionID }
    val isOn = dependency(ExperimentalFeatures)(ExperimentalFeature.backgroundRuns)
    if (!isOn || reply.serverKey != server.persistenceID || root == null) {
        val failure = localized(SystemR.string.system_session_answer_unsent)
        return Reduced(state, Effect.run { delivered.postRequest(reply.serverKey, reply.rootSessionID, reply.title, null, failure) })
    }
    return Reduced(
        state,
        Effect.run<Action> {
            val end = background.begin("Notification answer")
            try {
                val failure = sendNotificationAnswer(client, server, root, reply)
                if (failure == null) {
                    AppLog.send.info { "notification_answer_sent request=${reply.requestID}" }
                    delivered.cancel(reply.rootSessionID)
                } else {
                    AppLog.send.notice { "notification_answer_failed request=${reply.requestID}" }
                    delivered.postRequest(server.persistenceID, reply.rootSessionID, reply.title, null, failure)
                }
            } finally {
                end()
            }
        }.cancellable(HomeFeature.CancelID.NotificationReply(reply.requestID), cancelInFlight = false),
    )
}

/** Null once the answer went through, else the line the notification shows instead. */
private suspend fun sendNotificationAnswer(client: OpenCodeClient, server: OpenCodeServer, root: OpenCodeSession, reply: SessionNotificationReply): String? {
    try {
        val pending = familyRequests(client, server, root)
        if (reply.isPermission) {
            val permission = pending.permissions.firstOrNull { it.id == reply.requestID }
                ?: return localized(ServerR.string.server_request_no_longer_waiting)
            val answer = if (reply.value == permissionOnce) OpenCodePermissionReply.Once else OpenCodePermissionReply.Reject
            client.replyPermission(server, permission.sessionID, permission.id, answer, null)
            return null
        }
        val form = pending.forms.firstOrNull { it.id == reply.requestID }
            ?: return localized(ServerR.string.server_request_no_longer_waiting)
        val field = form.activeFields(form.defaultAnswers).singleOrNull()
            ?: return localized(SystemR.string.system_session_answer_unsent)
        val answers = form.defaultAnswers + (field.key to OpenCodeFormValue.Str(reply.value))
        val validated = form.validatedAnswer(answers)
        val invalid = validated.exceptionOrNull()
        if (invalid != null) return invalid.message ?: localized(SystemR.string.system_session_answer_unsent)
        client.replyForm(server, form.sessionID, form.id, validated.getOrThrow(), directory = root.directory)
        return null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: OpenCodeClientError) {
        if (error.requestSettled) return error.message
        return localized(SystemR.string.system_session_answer_unsent)
    } catch (_: Exception) {
        return localized(SystemR.string.system_session_answer_unsent)
    }
}
