package kr.c40.floor.core

/** 로봇 접속 설정. 에뮬레이터에서는 호스트 PC 의 mock 서버(10.0.2.2)를 가리킨다. */
data class RobotEndpoints(
    val httpBase: String,
    val coapHost: String,
    val coapPort: Int,
    /** ROS rosbridge. 기준 위치 방식(`/switch_floor_map_only`)에만 쓴다. */
    val rosbridgeUrl: String = "",
) {
    companion object {
        val ROBOT = RobotEndpoints("http://localhost:8089", "192.168.64.20", 5683, "ws://192.168.64.20:9090")
        val EMULATOR = RobotEndpoints("http://10.0.2.2:8089", "10.0.2.2", 5683, "ws://10.0.2.2:9090")
    }
}

data class GatewayResult(val ok: Boolean, val code: String, val body: String, val millis: Long)

/** 읽기 전용 접속부. GET 만 보낸다. */
interface RobotGateway {
    fun httpGet(path: String): GatewayResult
    fun coapGet(path: String): GatewayResult
}

/** 쓰기 접속부. [RobotCommands] 구현만 사용하며, 화면 코드는 직접 쓰지 않는다. */
interface WriteGateway {
    fun httpPost(path: String, json: String): GatewayResult
    fun coapPost(path: String, json: String): GatewayResult
}
