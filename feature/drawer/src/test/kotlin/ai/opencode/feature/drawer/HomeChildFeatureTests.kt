package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.BackgroundTimeClient
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.settings.NotificationsStatus
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.notifications.NotificationPermissionClient
import ai.opencode.feature.system.notifications.PushLevel
import ai.opencode.feature.system.notifications.PushSetting
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.server.EventStreamOverflow
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

/**
 * Home's child features, `HomeNotificationsFeature` and `HomeConnectionFeature`, on their own.
 *
 * Each permission runs in turn inside the one method.
 */
class HomeChildFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://home.example", username = "", password = "")

    @Test fun turningNotificationsOnAsksPermissionThenTellsTheServer() = runTest {
        for (permission in listOf(NotificationPermission.Enabled, NotificationPermission.Disabled)) {
            val off = PushSetting(isOn = false)
            val on = PushSetting(isOn = true)
            val state = HomeNotificationsFeature.State(
                server = server,
                status = NotificationsStatus(permission = NotificationPermission.NotDetermined, setting = off),
            )
            val told = Collections.synchronizedList(mutableListOf<PushSetting>())
            val store = TestStore(state, HomeNotificationsFeature(), this) {
                this[NotificationPermissionClient] = NotificationPermissionClient(
                    status = { NotificationPermission.NotDetermined },
                    request = { permission },
                    openSettings = {},
                )
                this[SessionNotificationsClient] = SessionNotificationsClient(
                    isAvailable = true,
                    setSetting = { _, setting ->
                        told += setting
                        true
                    },
                )
            }
            val settled = if (permission == NotificationPermission.Enabled) on else off
            store.send(HomeNotificationsFeature.Action.Toggled(true)) {
                it.copy(status = it.status.copy(setting = on, isChanging = true))
            }
            store.receive(HomeNotificationsFeature.Action.Changed(permission, settled, null)) {
                it.copy(status = it.status.copy(permission = permission, setting = settled, isChanging = false))
            }
            store.finish()
            assertEquals("$permission", if (permission == NotificationPermission.Enabled) listOf(on) else emptyList(), told.toList())
        }
    }

    @Test fun aChangeTheServerDidntTakeIsUndone() = runTest {
        val all = PushSetting(isOn = true, level = PushLevel.All)
        val state = HomeNotificationsFeature.State(
            server = server,
            status = NotificationsStatus(permission = NotificationPermission.Enabled, setting = all),
        )
        val store = TestStore(state, HomeNotificationsFeature(), this) {
            this[SessionNotificationsClient] = SessionNotificationsClient(isAvailable = true, setSetting = { _, _ -> false })
        }
        store.send(HomeNotificationsFeature.Action.LevelPicked(PushLevel.Attention)) {
            it.copy(status = it.status.copy(setting = all.copy(level = PushLevel.Attention), isChanging = true))
        }
        store.receive(HomeNotificationsFeature.Action.Changed(NotificationPermission.Enabled, all, "Couldn’t change notifications. Try again.")) {
            it.copy(status = it.status.copy(setting = all, isChanging = false, error = "Couldn’t change notifications. Try again."))
        }
        store.finish()
    }

    @Test fun connectionRetriesReplaceThePreviousTimerWithoutDisconnecting() = runTest {
        val clock = TestClock()
        val state = HomeConnectionFeature.State(server = server, isEventStreamLive = true)
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[Clock] = clock
        }
        store.send(HomeConnectionFeature.Action.SnapshotFailed) { it.copy(snapshotFailures = 1) }
        store.send(HomeConnectionFeature.Action.SnapshotFailed) { it.copy(snapshotFailures = 2) }
        clock.advance(1.seconds)
        clock.advance(1.seconds)
        store.receive(HomeConnectionFeature.Action.SnapshotRetryRequested)
        store.finish()
        assertTrue(store.state.isConnected)
        assertTrue(store.state.isEventStreamLive)
    }

    @Test fun connectionLossKeepsOneLastKnownDeadlineAcrossRepeatedFailures() = runTest {
        val clock = TestClock()
        val now = Instant.ofEpochSecond(100)
        val state = HomeConnectionFeature.State(server = server, isEventStreamLive = true)
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[Clock] = clock
            this[HomeSnapshotDate] = DateClient.constant(now)
        }
        store.send(HomeConnectionFeature.Action.ConnectionLost) {
            it.copy(
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                isEventStreamLive = false,
                lastKnownActivityDeadline = now.plusMillis(HomeFeature.lastKnownActivityLifetime.inWholeMilliseconds),
                isRecovering = true,
                recoveryFailures = 1,
            )
        }
        // A second failure inside the grace stays Reconnecting.
        store.send(HomeConnectionFeature.Action.ConnectionLost) { it.copy(recoveryFailures = 2) }
        clock.advance(2.5.seconds)
        store.receive(HomeConnectionFeature.Action.ReconnectingGraceElapsed) { it.copy(showsRecovery = true) }
        clock.advance(9.5.seconds)
        store.receive(HomeConnectionFeature.Action.OfflineGraceElapsed(pathDown = false)) {
            it.copy(
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected,
                isRecovering = false,
                showsRecovery = false,
                recoveryFailures = 0,
            )
        }
        clock.advance(HomeFeature.lastKnownActivityLifetime - 12.seconds)
        store.receive(HomeConnectionFeature.Action.LastKnownActivityExpired) { it.copy(lastKnownActivityDeadline = null) }
        store.finish()
    }

    @Test fun backgroundingAnOutageCancelsTheChildStream() = runTest {
        val clock = TestClock()
        var subscriptions = 0
        val state = HomeConnectionFeature.State(
            server = server,
            isStreaming = true,
            connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected,
        )
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                events = {
                    subscriptions += 1
                    flow { throw IOException("cannot connect to host") }
                },
            )
            this[BackgroundTimeClient] = BackgroundTimeClient { _, _ -> throw AssertionError("An outage must not hold background time") }
        }
        store.send(HomeConnectionFeature.Action.NetworkRerouted)
        store.receive(HomeConnectionFeature.Action.ConnectionLost)
        store.send(HomeConnectionFeature.Action.SceneEnteredBackground) { it.copy(isInBackground = true) }
        clock.advance(120.seconds)
        store.finish()
        assertEquals(1, subscriptions)
    }

    @Test fun rejectedCredentialsDoNotRestartTheChildStream() = runTest {
        val state = HomeConnectionFeature.State(server = server, isStreaming = true, isEventStreamLive = true)
        val store = TestStore(state, HomeConnectionFeature(), this)
        store.send(HomeConnectionFeature.Action.EventStreamFailed(OpenCodeClientError("Sign-in failed", statusCode = 401))) {
            it.copy(
                connectionErrorMessage = "Sign-in failed",
                credentialsRejected = true,
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected,
                isEventStreamLive = false,
            )
        }
        store.send(HomeConnectionFeature.Action.NetworkRerouted)
        store.send(HomeConnectionFeature.Action.RetryConnectionTapped)
        store.finish()
    }

    /**
     * Only a 401 is a credential problem; any other failure leaves Try Again working, and a later
     * 401 still stops the stream.
     */
    @Test fun otherStreamFailuresStillRetry() = runTest {
        val state = HomeConnectionFeature.State(server = server, isStreaming = true, isEventStreamLive = true)
        var subscriptions = 0
        val rejected = OpenCodeClientError("Sign-in failed", statusCode = 401)
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                events = {
                    subscriptions += 1
                    flow {
                        emit(OpenCodeServerEvent.Connected)
                        throw rejected
                    }
                },
            )
        }
        store.send(HomeConnectionFeature.Action.EventStreamFailed(OpenCodeClientError("The server had a problem", statusCode = 500))) {
            it.copy(
                connectionErrorMessage = "The server had a problem",
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected,
                isEventStreamLive = false,
            )
        }
        // Try Again shows Reconnecting at once: the person asked.
        store.send(HomeConnectionFeature.Action.RetryConnectionTapped) {
            it.copy(
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                showsRecovery = true,
                isRecovering = true,
            )
        }
        store.receive(HomeConnectionFeature.Action.ServerConnected(server)) {
            it.copy(
                connectionErrorMessage = null,
                hasConnected = true,
                isEventStreamLive = true,
                showsRecovery = false,
                isRecovering = false,
            )
        }
        store.receive(HomeConnectionFeature.Action.EventStreamFailed(rejected)) {
            it.copy(
                connectionErrorMessage = "Sign-in failed",
                credentialsRejected = true,
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected,
                isEventStreamLive = false,
            )
        }
        store.send(HomeConnectionFeature.Action.RetryConnectionTapped)
        store.finish()
        assertEquals(1, subscriptions)
    }

    @Test fun reconnectingClearsRejectedCredentials() = runTest {
        val state = HomeConnectionFeature.State(
            server = server,
            connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected,
            connectionErrorMessage = "Sign-in failed",
            credentialsRejected = true,
        )
        val store = TestStore(state, HomeConnectionFeature(), this)
        store.send(HomeConnectionFeature.Action.ServerConnected(server)) {
            it.copy(
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                connectionErrorMessage = null,
                credentialsRejected = false,
                hasConnected = true,
                isEventStreamLive = true,
            )
        }
        store.finish()
    }

    @Test fun overflowResubscribesAndEmitsConnectedAgain() = runTest {
        val clock = TestClock()
        var subscriptions = 0
        val second = Channel<OpenCodeServerEvent>(Channel.UNLIMITED)
        val now = Instant.ofEpochSecond(100)
        val state = HomeConnectionFeature.State(server = server, isStreaming = true)
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[Clock] = clock
            this[HomeSnapshotDate] = DateClient.constant(now)
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                events = {
                    subscriptions += 1
                    if (subscriptions > 1) {
                        second.consumeAsFlow()
                    } else {
                        // The second `.connected` overflows it.
                        flow {
                            emit(OpenCodeServerEvent.Connected)
                            throw EventStreamOverflow()
                        }
                    }
                },
            )
        }
        store.send(HomeConnectionFeature.Action.NetworkRerouted)
        store.receive(HomeConnectionFeature.Action.ServerConnected(server)) {
            it.copy(hasConnected = true, isEventStreamLive = true)
        }
        store.receive(HomeConnectionFeature.Action.ConnectionLost) {
            it.copy(
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                isEventStreamLive = false,
                lastKnownActivityDeadline = now.plusMillis(HomeFeature.lastKnownActivityLifetime.inWholeMilliseconds),
                isRecovering = true,
                recoveryFailures = 1,
            )
        }
        clock.advance(1.seconds)
        second.send(OpenCodeServerEvent.Connected)
        store.receive(HomeConnectionFeature.Action.ServerConnected(server)) {
            it.copy(isEventStreamLive = true, isRecovering = false, recoveryFailures = 0)
        }
        assertEquals(2, subscriptions)
        store.send(HomeConnectionFeature.Action.ConnectionLost) {
            it.copy(isEventStreamLive = false, isRecovering = true, recoveryFailures = 1)
        }
        store.send(HomeConnectionFeature.Action.SceneEnteredBackground) {
            it.copy(isInBackground = true, isRecovering = false, recoveryFailures = 0)
        }
        clock.advance(HomeFeature.lastKnownActivityLifetime)
        store.receive(HomeConnectionFeature.Action.LastKnownActivityExpired) { it.copy(lastKnownActivityDeadline = null) }
        store.finish()
    }
}
