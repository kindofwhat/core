package io.kweb

import com.github.salomonbrys.kotson.fromJson
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.Frame.Text
import io.ktor.http.cio.websocket.readText
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.respondText
import io.ktor.routing.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.*
import io.ktor.websocket.*
import io.kweb.Server2ClientMessage.Instruction
import io.kweb.browserConnection.KwebClientConnection
import io.kweb.browserConnection.KwebClientConnection.Caching
import io.kweb.dev.hotswap.KwebHotswapPlugin
import io.kweb.plugins.KWebPlugin
import kotlinx.coroutines.*
import kotlinx.coroutines.time.delay
import org.apache.commons.io.IOUtils
import java.io.*
import java.time.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList

/**
 * Created by ian on 12/31/16.
 */

typealias LogError = Boolean

typealias JavaScriptError = String

/**
 * The core kwebserver, and the starting point for almost any Kweb app.  This will element a HTTP server and respond
 * with a javascript page which will establish a websocket connection to retrieve and send instructions and data
 * between browser and server.
 *
 * @property port  The TCP port on which the HTTP server should listen
 * @property debug Should be set to true during development as it will provide useful warnings and other feedback,
 *                 but false during production because it is inefficient at scale
 * @property refreshPageOnHotswap Detects code-reloads by [HotSwapAgent](http://hotswapagent.org/) and refreshes
 *                                any connected webpage if this is detected
 * @property plugins A list of Kweb plugins to be loaded by Kweb
 * @property onError A handler for JavaScript errors (only detected if `debug == true`)
 * @property maxPageBuildTimeMS If `debug == true` this is the maximum time permitted to build a page before a
 *                              warning is logged
 * @property buildPage A lambda which will build the webpage to be served to the user, this is where your code should
 *                     go
 */
class Kweb(val port: Int,
           val debug: Boolean = true,
           val refreshPageOnHotswap: Boolean = false,
           val plugins: List<io.kweb.plugins.KWebPlugin> = java.util.Collections.emptyList(),
           val appServerConfigurator: (io.ktor.routing.Routing) -> Unit = {},
           val onError: ((List<StackTraceElement>, io.kweb.JavaScriptError) -> io.kweb.LogError) = { _, _ -> true },
           val maxPageBuildTimeMS: Long = 500,
           val clientStateTimeout : Duration = Duration.ofHours(1),
           profileJavascript : Boolean = false,
           val buildPage: WebBrowser.() -> Unit
) : Closeable {

    // TODO: This should be exposed via some diagnostic tool
    private val javascriptProfilingStackCounts = if (profileJavascript)
        ConcurrentHashMap<List<StackTraceElement>, AtomicInteger>() else null

    // private val server: Any
    private val clientState: ConcurrentHashMap<String, io.kweb.RemoteClientState> = java.util.concurrent.ConcurrentHashMap()
    private val mutableAppliedPlugins: MutableSet<io.kweb.plugins.KWebPlugin> = java.util.HashSet()
    val appliedPlugins: Set<io.kweb.plugins.KWebPlugin> get() = mutableAppliedPlugins

    private val server: JettyApplicationEngine

    private fun recordForProfiling() {
        if (javascriptProfilingStackCounts != null && this.outboundMessageCatcher.get() == null) {
            javascriptProfilingStackCounts.computeIfAbsent(Thread.currentThread().stackTrace.slice(3..7), { AtomicInteger(0) }).incrementAndGet()
        }
    }


    init {
        logger.info("Initializing Kweb listening on port $port")

        //TODO: Need to do housekeeping to deleteIfExists old client data

        val startHeadBuilder = StringBuilder()
        val endHeadBuilder = StringBuilder()

        if (refreshPageOnHotswap) {
            KwebHotswapPlugin.addHotswapReloadListener({ refreshAllPages() })
        }

        server = embeddedServer(Jetty, port) {
            install(DefaultHeaders)
          //  install(CallLogging)
            install(Compression)
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(10)
                timeout = Duration.ofSeconds(30)
            }
            routing {

                static("static") {
                    resources("static")
                }

                // Register custom state.
                appServerConfigurator.invoke(this)

                // TODO this is pretty awful but don't see an alternative....
                // Register plugins and allow plugin specific routing to be added.
                for (plugin in plugins) {
                    applyPlugin(plugin = plugin, appliedPlugins = mutableAppliedPlugins, endHeadBuilder = endHeadBuilder, startHeadBuilder = startHeadBuilder, routeHandler = this)
                }

                val resourceStream = Kweb::class.java.getResourceAsStream("kweb_bootstrap.html")
                val bootstrapHtmlTemplate = IOUtils.toString(resourceStream, Charsets.UTF_8)
                        .replace("<!-- START HEADER PLACEHOLDER -->", startHeadBuilder.toString())
                        .replace("<!-- END HEADER PLACEHOLDER -->", endHeadBuilder.toString())

                // Setup default KWeb routing.

                get("/robots.txt") {
                    call.response.status(HttpStatusCode.NotFound)
                    call.respondText("robots.txt not currently supported by kweb")
                }

                get("/favicon.ico") {
                    call.response.status(HttpStatusCode.NotFound)
                    call.respondText("favicons not currently supported by kweb")
                }

                static("/static") {
                    // When running under IDEA make sure that working directory is set to this sample's project folder
                    staticRootFolder = File("static")
                    files("images")
                }

                get("/{visitedUrl...}") {
                    val kwebSessionId = createNonce()

                    val remoteClientState = clientState.getOrPut(kwebSessionId) {
                        RemoteClientState(id = kwebSessionId, clientConnection = KwebClientConnection.Caching())
                    }

                    val httpRequestInfo = HttpRequestInfo(call.request)

                    try {
                        if (debug) {
                            warnIfBlocking(maxTimeMs = maxPageBuildTimeMS, onBlock = { thread ->
                                logger.warn {"buildPage lambda must return immediately but has taken > $maxPageBuildTimeMS ms.  More info at DEBUG loglevel"}

                                val logStatementBuilder = StringBuilder()
                                logStatementBuilder.appendln("buildPage lambda must return immediately but has taken > $maxPageBuildTimeMS ms, appears to be blocking here:")

                                thread.stackTrace.pruneAndDumpStackTo(logStatementBuilder)
                                val logStatement = logStatementBuilder.toString()
                                logger.debug { logStatement }
                            }) {
                                try {
                                    buildPage(WebBrowser(kwebSessionId, httpRequestInfo, this@Kweb))
                                } catch (e: Exception) {
                                    logger.error("Exception thrown building page", e)
                                }
                                logger.debug { "Outbound message queue size after buildPage is ${(remoteClientState.clientConnection as Caching).queueSize()}" }
                            }
                        } else {
                            try {
                                buildPage(WebBrowser(kwebSessionId, httpRequestInfo, this@Kweb))
                            } catch (e: Exception) {
                                logger.error("Exception thrown building page", e)
                            }
                            logger.debug { "Outbound message queue size after buildPage is ${(remoteClientState.clientConnection as Caching).queueSize()}" }
                        }
                        for (plugin in plugins) {
                            execute(kwebSessionId, plugin.executeAfterPageCreation())
                        }

                        val initialCachedMessages = remoteClientState.clientConnection as Caching

                        remoteClientState.clientConnection = Caching()

                        val bootstrapHtml = bootstrapHtmlTemplate
                                .replace("--CLIENT-ID-PLACEHOLDER--", kwebSessionId)
                                .replace("<!-- BUILD PAGE PAYLOAD PLACEHOLDER -->", initialCachedMessages.read().map { "handleInboundMessage($it);" }.joinToString(separator = "\n"))

                        call.respondText(bootstrapHtml, ContentType.Text.Html)
                    } catch (nfe: NotFoundException) {
                        call.response.status(HttpStatusCode.NotFound)
                        call.respondText("URL ${call.request.uri} not found.", ContentType.parse("text/plain"))
                    } catch (e: Exception) {
                        val logToken = random.nextLong().toString(16)

                        logger.error(e) { "Exception thrown while rendering page, code $logToken" }

                        call.response.status(HttpStatusCode.InternalServerError)
                        call.respondText("""
                        Internal Server Error.

                        Please include code $logToken in any message report to help us track it down.
""".trimIndent())
                    }
                }

                webSocket("/ws") {

                    val hello = gson.fromJson<Client2ServerMessage>(((incoming.receive() as Text).readText()))

                    if (hello.hello == null) {
                        throw RuntimeException("First message from client isn't 'hello'")
                    }

                    val remoteClientState = clientState.get(hello.id)
                            ?: throw RuntimeException("Unable to find server state corresponding to client id ${hello.id}")

                    assert(remoteClientState.clientConnection is Caching)
                    logger.debug("Received message from remoteClient ${remoteClientState.id}, flushing outbound message cache")
                    val oldConnection = remoteClientState.clientConnection as Caching
                    val webSocketClientConnection = KwebClientConnection.WebSocket(this)
                    remoteClientState.clientConnection = webSocketClientConnection
                    logger.debug("Set clientConnection for ${remoteClientState.id} to WebSocket")
                    oldConnection.read().forEach { webSocketClientConnection.send(it) }


                    try {
                        for (frame in incoming) {
                            try {
                                logger.debug { "Message received from client" }

                                if (frame is Text) {
                                    val message = gson.fromJson<Client2ServerMessage>(frame.readText())
                                    if (message.error != null) {
                                        handleError(message.error, remoteClientState)
                                    } else {
                                        when {
                                            message.callback != null -> {
                                                val (resultId, result) = message.callback
                                                val resultHandler = remoteClientState.handlers[resultId]
                                                        ?: throw RuntimeException("No data handler for $resultId for client ${remoteClientState.id}")
                                                resultHandler(result ?: "")
                                            }
                                            message.historyStateChange != null -> {
                                                
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                logger.error("Exception while receiving websocket message", e)
                            }
                        }
                    } finally {
                        logger.info("WS session disconnected for client id: ${remoteClientState.id}")
                        remoteClientState.clientConnection = Caching()
                    }
                }
            }
        }

        server.start()
        logger.info { "KWeb is listening on port $port" }

        GlobalScope.launch {
            while(true) {
                delay(Duration.ofMinutes(1))
                cleanUpOldClientStates()
            }
        }
    }

    private fun handleError(error: Client2ServerMessage.ErrorMessage, remoteClientState: RemoteClientState) {
        val debugInfo = remoteClientState.debugTokens[error.debugToken]
                ?: throw RuntimeException("DebugInfo message not found")
        val logStatementBuilder = StringBuilder()
        logStatementBuilder.appendln("JavaScript message: '${error.error.message}'")
        logStatementBuilder.appendln("Caused by ${debugInfo.action}: '${debugInfo.js}':")
        // TODO: Filtering the stacktrace like this seems a bit kludgy, although I can't think
        // TODO: of a specific reason why it would be bad.
        debugInfo.throwable.stackTrace.pruneAndDumpStackTo(logStatementBuilder)
        if (onError(debugInfo.throwable.stackTrace.toList(), error.error.message)) {
            logger.error(logStatementBuilder.toString())
        }
    }

    private fun applyPlugin(plugin: KWebPlugin,
                            appliedPlugins: MutableSet<KWebPlugin>,
                            endHeadBuilder: java.lang.StringBuilder,
                            startHeadBuilder: java.lang.StringBuilder,
                            routeHandler: Routing) {
        for (dependantPlugin in plugin.dependsOn) {
            if (!appliedPlugins.contains(dependantPlugin)) {
                applyPlugin(dependantPlugin, appliedPlugins, endHeadBuilder, startHeadBuilder, routeHandler)
                appliedPlugins.add(dependantPlugin)
            }
        }
        if (!appliedPlugins.contains(plugin)) {
            plugin.decorate(startHeadBuilder, endHeadBuilder)
            plugin.appServerConfigurator(routeHandler)
            appliedPlugins.add(plugin)
        }
    }

    private fun refreshAllPages() = GlobalScope.launch(Dispatchers.Default) {
        for (client in clientState.values) {
            val message = Server2ClientMessage(
                    yourId = client.id,
                    execute = Server2ClientMessage.Execute("window.location.reload(true);"), debugToken = null)
            client.clientConnection.send(message.toJson())
        }
    }

    /**
     * Allow us to catch outbound messages temporarily and only for this thread.  This is used for immediate
     * execution of event handlers, see `Element.immediatelyOn`
     */
    private val outboundMessageCatcher: ThreadLocal<MutableList<String>?> = ThreadLocal.withInitial { null }

    fun isCatchingOutbound() = outboundMessageCatcher.get() != null

    fun isNotCatchingOutbound() = !isCatchingOutbound()

    fun catchOutbound(f: () -> Unit): List<String> {
        require(outboundMessageCatcher.get() == null) { "Can't nest withThreadLocalOutboundMessageCatcher()" }

        val jsList = ArrayList<String>()
        outboundMessageCatcher.set(jsList)
        f()
        outboundMessageCatcher.set(null)
        return jsList
    }

    fun execute(clientId: String, javascript: String) {
        recordForProfiling()
        val wsClientData = clientState.get(clientId) ?: throw RuntimeException("Client id $clientId not found")
        wsClientData.lastModified = Instant.now()
        val debugToken: String? = if (!debug) null else {
            val dt = Math.abs(random.nextLong()).toString(16)
            wsClientData.debugTokens.put(dt, DebugInfo(javascript, "executing", Throwable()))
            dt
        }
        val outboundMessageCatcher = outboundMessageCatcher.get()
        if (outboundMessageCatcher == null) {
            wsClientData.send(Server2ClientMessage(yourId = clientId, debugToken = debugToken, execute = Server2ClientMessage.Execute(javascript)))
        } else {
            logger.debug("Temporarily storing message for $clientId in threadloacal outboundMessageCatcher")
            outboundMessageCatcher.add(javascript)
        }
    }

    fun send(clientId: String, instruction: Instruction) = send(clientId, listOf(instruction))

    fun send(clientId: String, instructions: List<Instruction>) {
        if (outboundMessageCatcher.get() != null) {
            throw RuntimeException("""
                Can't send instruction because there is an outboundMessageCatcher.  You should check for this with
                """.trimIndent())
        }
        val wsClientData = clientState.get(clientId) ?: throw RuntimeException("Client id $clientId not found")
        wsClientData.lastModified = Instant.now()
        val debugToken: String? = if (!debug) null else {
            val dt = Math.abs(random.nextLong()).toString(16)
            wsClientData.debugTokens.put(dt, DebugInfo(instructions.toString(), "instructions", Throwable()))
            dt
        }
        wsClientData.send(Server2ClientMessage(yourId = clientId, instructions = instructions, debugToken = debugToken))
    }

    fun executeWithCallback(clientId: String, javascript: String, callbackId: Int, handler: (String) -> Unit) {
        // TODO: Should return handle which can be used for cleanup of event listeners
        val wsClientData = clientState.get(clientId) ?: throw RuntimeException("Client id $clientId not found")
        val debugToken: String? = if (!debug) null else {
            val dt = Math.abs(random.nextLong()).toString(16)
            wsClientData.debugTokens.put(dt, DebugInfo(javascript, "executing with callback", Throwable()))
            dt
        }
        wsClientData.handlers.put(callbackId, handler)
        wsClientData.send(Server2ClientMessage(yourId = clientId, debugToken = debugToken, execute = Server2ClientMessage.Execute(javascript)))
    }

    fun removeCallback(clientId: String, callbackId: Int) {
        clientState[clientId]?.handlers?.remove(callbackId)
    }

    fun evaluate(clientId: String, expression: String, handler: (String) -> Unit) {
        recordForProfiling()
        val wsClientData = clientState.get(clientId)
                ?: throw RuntimeException("Failed to evaluate JavaScript because client id $clientId not found")
        val debugToken: String? = if (!debug) null else {
            val dt = Math.abs(random.nextLong()).toString(16)
            wsClientData.debugTokens.put(dt, DebugInfo(expression, "evaluating", Throwable()))
            dt
        }
        val callbackId = Math.abs(random.nextInt())
        wsClientData.handlers.put(callbackId, handler)
        wsClientData.send(Server2ClientMessage(yourId = clientId, evaluate = Server2ClientMessage.Evaluate(expression, callbackId), debugToken = debugToken))
    }

    override fun close() {
        logger.info("Shutting down Kweb")
        server.stop(0, 0, TimeUnit.SECONDS)
    }

    private fun cleanUpOldClientStates() {
        val now = Instant.now()
        val toRemove = clientState.entries.mapNotNull { (id: String, state: RemoteClientState) ->
            if (Duration.between(state.lastModified, now) > clientStateTimeout) {
                id
            } else {
                null
            }
        }
        if (toRemove.isNotEmpty()) {
            logger.info("Cleaning up client states for ids: $toRemove")
        }
        for (id in toRemove) {
            clientState.remove(id)
        }
    }

}

private data class RemoteClientState(val id: String, @Volatile var clientConnection: KwebClientConnection, val handlers: MutableMap<Int, (String) -> Unit> = HashMap(), val debugTokens: MutableMap<String, DebugInfo> = HashMap(), var lastModified :Instant = Instant.now()) {
    fun send(message: Server2ClientMessage) {
        clientConnection.send(gson.toJson(message))
    }

    override fun toString() = "RemoteClientState(id = $id)"
}

/**
 * @param request This is the raw ApplicationRequest object to [Ktor](https://github.com/Kotlin/ktor), the HTTP
 *                library used by Kweb.  It can be used to read various information about the inbound HTTP request,
 *                however you should use properties of [HttpRequestInfo] directly instead if possible.
 */
data class HttpRequestInfo(val request: ApplicationRequest) {

    val requestedUrl: String by lazy {
        with(request.origin) {
            "$scheme://$host:$port$uri"
        }
    }

    val cookies = request.cookies

    val remoteHost = request.origin.remoteHost

    val userAgent = request.headers["User-Agent"]
}

data class DebugInfo(val js: String, val action: String, val throwable: Throwable)

data class Server2ClientMessage(
        val yourId: String,
        val debugToken: String?,
        val execute: Execute? = null,
        val evaluate: Evaluate? = null,
        val instructions: List<Instruction>? = null
) {

    data class Instruction(val type: Type, val parameters: List<Any?>) {
        enum class Type {
            SetAttribute,
            CreateElement,
            AddText,
            RemoveAttribute
        }
    }

    data class Execute(val js: String)
    data class Evaluate(val js: String, val callbackId: Int)
}

data class Client2ServerMessage(
        val id: String,
        val hello: Boolean? = true,
        val error: ErrorMessage? = null,
        val callback: C2SCallback? = null,
        val historyStateChange : C2SHistoryStateChange? = null
) {

    data class ErrorMessage(val debugToken: String, val error: Error) {
        data class Error(val name: String, val message: String)
    }

    data class C2SCallback(val callbackId: Int, val data: String?)

    data class C2SHistoryStateChange(val newState : String)
}
