package kr.c40.floor.mock

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.eclipse.californium.core.CoapResource
import org.eclipse.californium.core.CoapServer
import org.eclipse.californium.core.coap.CoAP
import org.eclipse.californium.core.coap.MediaTypeRegistry
import org.eclipse.californium.core.config.CoapConfig
import org.eclipse.californium.core.network.CoapEndpoint
import org.eclipse.californium.core.server.resources.CoapExchange
import org.eclipse.californium.elements.config.Configuration
import java.net.InetSocketAddress

/** 가짜 로봇: 청소 앱의 HTTP(:8089)와 ROS 의 CoAP(:5683)를 흉내 낸다. 포트 0 이면 빈 포트를 쓴다. */
class MockRobotServer(val sim: RobotSim, httpPort: Int = 8089, coapPort: Int = 5683, bindAll: Boolean = true) {

    private val bind: (Int) -> InetSocketAddress = { if (bindAll) InetSocketAddress(it) else InetSocketAddress("127.0.0.1", it) }
    private val http: HttpServer = HttpServer.create(bind(httpPort), 0)
    private val coap: CoapServer

    val httpPort: Int get() = http.address.port
    val coapPort: Int get() = coap.endpoints.first().address.port

    init {
        routeHttp()
        val config = Configuration.createStandardWithoutFile()
        coap = CoapServer(config)
        coap.addEndpoint(CoapEndpoint.Builder().setInetSocketAddress(bind(coapPort)).setConfiguration(config).build())
        routeCoap()
    }

    fun start() {
        http.start()
        coap.start()
    }

    fun stop() {
        http.stop(0)
        coap.destroy()
    }

    // ---- HTTP (청소 앱 :8089) ------------------------------------------------------------------

    private fun routeHttp() {
        get("/mapFloorList") { sim.mapFloorList() }
        get("/getCurrentLoadMapInfo") { sim.currentMap() }
        get("/listCurrentMapNavigationPoints") { sim.navigationPoints() }
        post("/robotLoadMapOpenPosition") { body -> sim.loadMap(body).body }

        // 시험용 제어
        get("/mock/state") { sim.summary() }
        http.createContext("/mock/mode") { ex ->
            val name = ex.requestURI.query?.substringAfter("name=", "")?.uppercase().orEmpty()
            val mode = Mode.entries.find { it.name == name }
            if (mode != null) sim.mode = mode
            reply(ex, if (mode != null) 200 else 400, sim.summary())
        }
    }

    private fun get(path: String, body: () -> String) = http.createContext(path) { ex ->
        if (ex.requestMethod == "GET") reply(ex, 200, body()) else reply(ex, 405, "")
    }

    private fun post(path: String, handler: (String) -> String) = http.createContext(path) { ex ->
        if (ex.requestMethod == "POST") reply(ex, 200, handler(ex.requestBody.readBytes().toString(Charsets.UTF_8)))
        else reply(ex, 405, "")
    }

    private fun reply(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json;charset=UTF-8")
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
        ex.close()
    }

    // ---- CoAP (ROS 5683) -----------------------------------------------------------------------

    private fun routeCoap() {
        val root = coap.root
        val dirs = mutableMapOf<String, CoapResource>()
        fun dir(name: String) = dirs.getOrPut(name) { CoapResource(name).also { root.add(it) } }

        dir("position").add(JsonResource("status", { sim.positionStatus() }))
        dir("position").add(JsonResource("robotPosInfo", { sim.robotPos() }))
        dir("position").add(JsonResource("relocationPosition", null) { sim.relocate("/position/relocationPosition", it) })
        dir("clean").add(JsonResource("status", { sim.cleanStatus() }))
        dir("clean").add(JsonResource("positionStatus", { sim.cleanPositionStatus() }))
        dir("clean").add(JsonResource("relocationPosition", null) { sim.relocate("/clean/relocationPosition", it) })
        dir("charge").add(JsonResource("status", { sim.chargeStatus() }))
        dir("navigation").add(JsonResource("dest_posesV2", { sim.destPosesV2() }))
        // /task/status 는 실제 로봇에서 4.04 였으므로 일부러 만들지 않는다.
    }

    private class JsonResource(
        name: String,
        private val onGet: (() -> String)?,
        private val onPost: ((String) -> GatewayReply)? = null,
    ) : CoapResource(name) {
        override fun handleGET(exchange: CoapExchange) {
            val f = onGet ?: return exchange.respond(CoAP.ResponseCode.METHOD_NOT_ALLOWED)
            exchange.respond(CoAP.ResponseCode.CONTENT, f(), MediaTypeRegistry.APPLICATION_JSON)
        }

        override fun handlePOST(exchange: CoapExchange) {
            val f = onPost ?: return exchange.respond(CoAP.ResponseCode.METHOD_NOT_ALLOWED)
            val r = f(exchange.requestText)
            exchange.respond(CoAP.ResponseCode.CHANGED, r.body, MediaTypeRegistry.APPLICATION_JSON)
        }
    }
}
