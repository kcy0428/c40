package kr.c40.floor.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** ROS 서비스 호출 (rosbridge `call_service`). 쓰기 요청이므로 [RobotCommands] 구현만 사용한다. */
interface RosServiceCaller {
    fun callService(service: String, args: JSONObject, timeoutMs: Long = 20_000): GatewayResult

    /** 토픽에 메시지 하나를 보낸다 (advertise → publish). */
    fun publish(topic: String, type: String, msg: JSONObject): GatewayResult
}

/** OkHttp WebSocket 으로 rosbridge v2 프로토콜을 쓴다. 호출마다 연결을 새로 열고 닫는다. */
class OkHttpRosbridge(private val url: String) : RosServiceCaller {

    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()

    override fun callService(service: String, args: JSONObject, timeoutMs: Long): GatewayResult {
        val start = System.currentTimeMillis()
        val id = "c40floor-${System.nanoTime()}"
        val done = CountDownLatch(1)
        var result: GatewayResult? = null

        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val msg = JSONObject().put("op", "call_service").put("id", id).put("service", service).put("args", args)
                webSocket.send(msg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = try { JSONObject(text) } catch (_: Exception) { return }
                if (j.optString("op") != "service_response" || j.optString("id") != id) return
                // rosbridge 의 result 는 호출 성공 여부, values.result 는 서비스가 돌려준 성공 여부다.
                val values = j.optJSONObject("values")
                val ok = j.optBoolean("result", false) && (values == null || !values.has("result") || values.optBoolean("result"))
                result = GatewayResult(ok, "ROS", text, System.currentTimeMillis() - start)
                done.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result = GatewayResult(false, "ERR", "${t.javaClass.simpleName}: ${t.message}", System.currentTimeMillis() - start)
                done.countDown()
            }
        })
        val answered = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        ws.close(1000, null)
        return if (answered) result!! else GatewayResult(false, "TIMEOUT", "rosbridge 응답 없음", System.currentTimeMillis() - start)
    }

    override fun publish(topic: String, type: String, msg: JSONObject): GatewayResult {
        val start = System.currentTimeMillis()
        val opened = CountDownLatch(1)
        var failure: String? = null
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("op", "advertise").put("topic", topic).put("type", type).toString())
                opened.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = "${t.javaClass.simpleName}: ${t.message}"
                opened.countDown()
            }
        })
        if (!opened.await(5, TimeUnit.SECONDS) || failure != null) {
            ws.cancel()
            return GatewayResult(false, "ERR", failure ?: "rosbridge 연결 안 됨", System.currentTimeMillis() - start)
        }
        // 구독자가 새 발행자를 알아챌 시간을 준 뒤 보내고, 전송이 끝나도록 잠시 기다렸다 닫는다.
        Thread.sleep(800)
        val body = JSONObject().put("op", "publish").put("topic", topic).put("msg", msg)
        ws.send(body.toString())
        Thread.sleep(800)
        ws.send(JSONObject().put("op", "unadvertise").put("topic", topic).toString())
        ws.close(1000, null)
        return if (failure == null) GatewayResult(true, "ROS", "published $topic", System.currentTimeMillis() - start)
        else GatewayResult(false, "ERR", failure!!, System.currentTimeMillis() - start)
    }
}
