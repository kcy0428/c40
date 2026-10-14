package kr.c40.floor

/** 로봇 접속 설정. 에뮬레이터에서는 호스트 PC 의 mock 서버(10.0.2.2)를 가리킨다. */
data class RobotEndpoints(
    val httpBase: String,
    val coapHost: String,
    val coapPort: Int,
) {
    companion object {
        val ROBOT = RobotEndpoints("http://localhost:8089", "192.168.64.20", 5683)
        val EMULATOR = RobotEndpoints("http://10.0.2.2:8089", "10.0.2.2", 5683)
    }
}

data class GatewayResult(val ok: Boolean, val code: String, val body: String, val millis: Long)

/**
 * 읽기 전용 게이트웨이. 쓰기 요청(위치 지정, 지도 로드, 작업 시작)은 일부러 두지 않았다.
 * 쓰기가 필요해지면 별도 인터페이스로 분리하고 "실행 허용" 설정으로 막는다.
 */
interface RobotGateway {
    fun httpGet(path: String): GatewayResult
    fun coapGet(path: String): GatewayResult
}
