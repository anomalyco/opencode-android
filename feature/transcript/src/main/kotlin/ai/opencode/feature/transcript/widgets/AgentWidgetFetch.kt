package ai.opencode.feature.transcript.widgets

import ai.opencode.core.log.AppLog
import ai.opencode.core.tuning.DeveloperMode
import ai.opencode.core.tuning.InternalBuild
import com.anomalyco.psychoscript.FetchCompletion
import com.anomalyco.psychoscript.FetchRequest
import com.anomalyco.psychoscript.FetchResponse
import com.anomalyco.psychoscript.FetchTransport
import com.anomalyco.psychoscript.HttpHeader
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.http.HttpMethod
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Which hosts a live widget may fetch from: the one place a grant is decided. Every declared
 * capability is granted for now (specs/v2/widgets-live.md); a permission prompt goes here.
 */
object AgentWidgetCapabilities {
    fun grantedHosts(declared: List<String>?): List<String>? = declared?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }

    /** Dev mode: a debug build or Developer Mode. */
    val isDevMode: Boolean get() = InternalBuild.isDebug || DeveloperMode.isEnabled

    /** In dev mode `fetch` may also reach declared local hosts, `http:` included. */
    val allowsLocalNetwork: Boolean get() = isDevMode
}

/** Widget diagnostics for developers, under one logcat tag (`OpenCode/widget`): dev mode only, never per frame. */
internal object AgentWidgetDiagnostics {
    fun log(message: () -> String) {
        if (AgentWidgetCapabilities.isDevMode) AppLog.widget.notice(message)
    }
}

/**
 * A live widget's `fetch` transfer through OkHttp, one per run. The runner checks the URL, hosts,
 * methods, headers, sizes, rates and redirects, and resolves the host once; this carries one
 * exchange to one of those checked addresses (no second lookup that could rebind), with no
 * redirects and no cookies.
 * [failed] hears of each transfer that failed (for the widget's error report).
 */
internal class AgentWidgetFetchTransport(private val failed: (String) -> Unit) : FetchTransport {
    override fun start(request: FetchRequest, completion: FetchCompletion) {
        val call = try {
            val type = request.headers.firstOrNull { it.name.equals("content-type", ignoreCase = true) }?.value?.toMediaTypeOrNull()
            val body = request.body?.toRequestBody(type)
                ?: if (HttpMethod.requiresRequestBody(request.method)) ByteArray(0).toRequestBody(type) else null
            val builder = Request.Builder().url(request.url).method(request.method, body)
            request.headers.forEach { builder.addHeader(it.name, it.value) }
            val addresses = request.addresses.map { InetAddress.getByName(it) }
            client.newBuilder()
                .callTimeout(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .dns { host -> addresses.ifEmpty { throw UnknownHostException("$host wasn't resolved by the runner") } }
                .build()
                .newCall(builder.build())
        } catch (error: IllegalArgumentException) {
            completion.fail(error.message ?: "invalid request")
            return
        }
        calls[request.id] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                calls.remove(request.id)
                val message = e.message ?: "network error"
                if (!call.isCanceled()) failed("${request.method} ${request.url.substringBefore('?')}: $message")
                completion.fail(message)
            }

            override fun onResponse(call: Call, response: Response) {
                calls.remove(request.id)
                try {
                    response.use {
                        // One byte over the cap, so the runner reports TooLarge.
                        val max = request.maxResponseBytes.toLong() + 1
                        val source = it.body.source().apply { request(max) }
                        val bytes = source.buffer.readByteArray(minOf(source.buffer.size, max))
                        completion.succeed(FetchResponse(it.code.toUShort(), it.headers.map { (n, v) -> HttpHeader(n, v) }, bytes, null))
                    }
                } catch (e: IOException) {
                    completion.fail(e.message ?: "network error")
                }
            }
        })
    }

    override fun cancel(id: ULong) {
        calls.remove(id)?.cancel()
    }

    private companion object {
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .cookieJar(CookieJar.NO_COOKIES)
                .cache(null)
                .build()
        }
        val calls = ConcurrentHashMap<ULong, Call>()
    }
}
