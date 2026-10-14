package kr.c40.floor.mock

/**
 * 가짜 로봇 실행: `gradlew :mock-robot:run --args="--mode normal"`
 *
 * 옵션: --http-port 8089  --coap-port 5683  --mode NORMAL|REJECT|NEVER_LOCATES|WRONG_FLOOR|MAP_STUCK|CLEANING|LEGACY_FIELDS
 *       --locate-delay-ms 3000  --map-follow-delay-ms 2000
 * 실행 중 모드 변경: http://localhost:8089/mock/mode?name=REJECT , 상태 확인: /mock/state
 */
fun main(args: Array<String>) {
    fun opt(name: String) = args.indexOf("--$name").let { if (it >= 0 && it + 1 < args.size) args[it + 1] else null }

    val cfg = MockConfig(
        locateDelayMs = opt("locate-delay-ms")?.toLong() ?: 3_000,
        mapFollowDelayMs = opt("map-follow-delay-ms")?.toLong() ?: 2_000,
    )
    val sim = RobotSim(cfg)
    opt("mode")?.let { m -> sim.mode = Mode.valueOf(m.uppercase()) }

    val server = MockRobotServer(sim, opt("http-port")?.toInt() ?: 8089, opt("coap-port")?.toInt() ?: 5683)
    server.start()
    println("mock-robot 실행 중: HTTP :${server.httpPort}  CoAP(UDP) :${server.coapPort}  모드=${sim.mode}")
    println("모드 변경: http://localhost:${server.httpPort}/mock/mode?name=REJECT  상태: /mock/state")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}
