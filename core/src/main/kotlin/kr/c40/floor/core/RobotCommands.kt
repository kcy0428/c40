package kr.c40.floor.core

import org.json.JSONObject

/**
 * 로봇 상태를 바꾸는 요청. 시퀀서만 사용한다.
 * 기본 구현은 [DryRunCommands] 이고, [LiveCommands] 는 화면에서 "실제 전송 허용"을 켠 경우에만 만든다.
 */
interface RobotCommands {
    val isDryRun: Boolean

    /** CoAP `POST /clean/relocationPosition {"action":3,"id":<지표점 id>}` ([코드], 로봇에서는 미검증). */
    fun relocateToLandmark(id: Int): GatewayResult

    /** 청소 앱이 첫 요청이 실패했을 때 재시도하는 경로 `/position/relocationPosition`. */
    fun relocateToLandmarkFallback(id: Int): GatewayResult

    /** `POST :8089/robotLoadMapOpenPosition {"mapId":...}` 로 청소 앱의 지도를 맞춘다 ([코드], 미검증). */
    fun loadMap(mapId: String): GatewayResult
}

class LiveCommands(private val w: WriteGateway) : RobotCommands {
    override val isDryRun = false

    override fun relocateToLandmark(id: Int) = coap("/clean/relocationPosition", id)
    override fun relocateToLandmarkFallback(id: Int) = coap("/position/relocationPosition", id)

    override fun loadMap(mapId: String): GatewayResult {
        val r = w.httpPost("/robotLoadMapOpenPosition", JSONObject().put("mapId", mapId).toString())
        val body = try { JSONObject(r.body) } catch (_: Exception) { null }
        return if (r.ok && body?.optBoolean("status", false) == true) r else r.copy(ok = false)
    }

    private fun coap(path: String, id: Int): GatewayResult {
        val r = w.coapPost(path, JSONObject().put("action", 3).put("id", id).toString())
        val body = try { JSONObject(r.body) } catch (_: Exception) { null }
        return if (r.ok && body?.optInt("code", -1) == 0) r else r.copy(ok = false)
    }
}

/** 보낼 요청을 기록만 하고 아무것도 전송하지 않는다. */
class DryRunCommands : RobotCommands {
    override val isDryRun = true
    val recorded = mutableListOf<String>()

    override fun relocateToLandmark(id: Int) = record("POST /clean/relocationPosition {\"action\":3,\"id\":$id}")
    override fun relocateToLandmarkFallback(id: Int) = record("POST /position/relocationPosition {\"action\":3,\"id\":$id}")
    override fun loadMap(mapId: String) = record("POST :8089/robotLoadMapOpenPosition {\"mapId\":\"$mapId\"}")

    private fun record(text: String): GatewayResult {
        recorded += text
        return GatewayResult(true, "DRY-RUN", text, 0)
    }
}
