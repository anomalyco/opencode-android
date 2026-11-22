package ai.opencode.feature.transcript.widgets

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeWidgetProgram
import ai.opencode.server.model.OpenCodeWidgetRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Compiled widget template programs by `name@version`, fetched (or read from disk) and compiled once and
 * shared by every widget using that version. The few most recently used stay compiled; one in
 * use by a widget stays alive through its controller.
 */
class AgentWidgetPrograms(
    private val engine: () -> AgentWidgetEngine,
    private val transport: () -> AgentWidgetTransport,
    private val limit: Int = 12,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entries = LinkedHashMap<String, Deferred<Compiled>>(16, 0.75f, true)

    /** A widget template version's compiled program, shared by every widget using it: [step] or [live] by its mode. */
    class Compiled(val info: OpenCodeWidgetProgram, val step: AgentWidgetProgram?, val live: AgentWidgetLiveProgram?)

    /** The widget template's program doesn't compile on this device. */
    class CompileFailed(override val cause: AgentWidgetEngineException, val info: OpenCodeWidgetProgram) : Exception(cause.message, cause)

    /** Throws the fetch's error or [CompileFailed]; a failed version is fetched again next time. */
    suspend fun program(server: OpenCodeServer, directory: String?, ref: OpenCodeWidgetRef): Compiled {
        val engine = engine()
        val transport = transport()
        val deferred = synchronized(entries) {
            entries.getOrPut(ref.key) {
                scope.async {
                    val info = transport.program(server, directory, ref)
                    val started = System.nanoTime()
                    val compiled = try {
                        if (info.isLive) {
                            Compiled(info, null, engine.compileLive(info.program))
                        } else {
                            Compiled(info, engine.compile(info.program), null)
                        }
                    } catch (error: AgentWidgetEngineException) {
                        throw CompileFailed(error, info)
                    }
                    AppLog.perf.info {
                        "Widget ${ref.key} compiled${if (info.isLive) " (live)" else ""} in ${(System.nanoTime() - started) / 1_000} µs (${info.program.length} chars)"
                    }
                    compiled
                }
            }.also {
                // An evicted program a widget still holds keeps stepping; the collector frees it.
                while (entries.size > limit) entries.remove(entries.keys.first())
            }
        }
        return try {
            deferred.await()
        } catch (error: Throwable) {
            synchronized(entries) { if (entries[ref.key] === deferred) entries.remove(ref.key) }
            throw error
        }
    }

    companion object Key : DependencyKey<AgentWidgetPrograms> {
        override val liveValue: AgentWidgetPrograms by lazy {
            AgentWidgetPrograms(engine = { dependency(AgentWidgetEngine) }, transport = { dependency(AgentWidgetTransport) })
        }
        override val testValue: AgentWidgetPrograms
            get() = AgentWidgetPrograms(engine = { dependency(AgentWidgetEngine) }, transport = { dependency(AgentWidgetTransport) })
    }
}

/** Runs [block] on the widget's own lane, so its steps never overlap or block the main thread. */
internal suspend inline fun <T> AgentWidgetLane.run(crossinline block: () -> T): T = withContext(dispatcher) { block() }

@JvmInline
internal value class AgentWidgetLane(val dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
    companion object {
        /** A step's run needs up to ~450 KB of stack at 100 view levels (psychoscript's budget is 512 KiB). */
        const val STACK_BYTES = 512L shl 10

        private val threads by lazy {
            val count = java.util.concurrent.atomic.AtomicInteger()
            val size = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            java.util.concurrent.ThreadPoolExecutor(size, size, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue()) { task ->
                Thread(null, task, "AgentWidgetStep-${count.incrementAndGet()}", STACK_BYTES).apply { isDaemon = true }
            }.apply { allowCoreThreadTimeOut(true) }.asCoroutineDispatcher()
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun make() = AgentWidgetLane(threads.limitedParallelism(1))
    }
}
