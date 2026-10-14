package kr.c40.floor.mock

import kr.c40.floor.core.DryRunCommands
import kr.c40.floor.core.FloorSwitchSequencer
import kr.c40.floor.core.FloorTarget
import kr.c40.floor.core.GatewayRobotReader
import kr.c40.floor.core.LiveCommands
import kr.c40.floor.core.RealGateway
import kr.c40.floor.core.RobotCommands
import kr.c40.floor.core.RobotEndpoints
import kr.c40.floor.core.SequencerConfig
import kr.c40.floor.core.StepId
import kr.c40.floor.core.SwitchResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 실제 네트워크(HTTP + CoAP/UDP)를 거쳐 진짜 게이트웨이로 mock 로봇을 상대한다. */
class EndToEndTest {
    private lateinit var sim: RobotSim
    private lateinit var server: MockRobotServer
    private lateinit var gw: RealGateway

    // 지연을 줄여 테스트를 빠르게 한다 (시퀀서 설정도 같은 비율로 줄임).
    private val fast = SequencerConfig(verifyTimeoutMs = 4_000, pollIntervalMs = 100, settleMs = 150, mapFollowTimeoutMs = 1_500)

    @Before
    fun setUp() {
        sim = RobotSim(MockConfig(locateDelayMs = 400, mapFollowDelayMs = 300))
        server = MockRobotServer(sim, httpPort = 0, coapPort = 0, bindAll = false)
        server.start()
        gw = RealGateway(RobotEndpoints("http://127.0.0.1:${server.httpPort}", "127.0.0.1", server.coapPort))
    }

    @After
    fun tearDown() = server.stop()

    private fun switchTo(floor: Int, id: Int, commands: RobotCommands = LiveCommands(gw)): SwitchResult =
        FloorSwitchSequencer(GatewayRobotReader(gw), commands, config = fast).run(FloorTarget(floor, id)) { true }

    @Test
    fun `읽기 응답을 실측 형식 그대로 파싱한다`() {
        val reader = GatewayRobotReader(gw)
        assertEquals(5, reader.floors()!!.size)
        assertEquals(1, reader.currentMap()!!.floor)
        assertEquals(0, reader.positionStatus()!!.status)
        assertEquals(1, reader.robotPos()!!.floor)
        assertEquals(65535, reader.cleanStatus())
        assertEquals(98, reader.chargeStatus()!!.power)
        // zone_start_pose 는 제외되고 landmark 만 남는다
        assertEquals(listOf(101, 102, 103), reader.landmarks()!!.map { it.id })
    }

    @Test
    fun `1층에서 2층으로 전환한다`() {
        val r = switchTo(2, 102)
        assertTrue(r.steps.joinToString { "${it.step}:${it.message}" }, r.success)
        assertEquals(listOf("POST /clean/relocationPosition {\"action\":3,\"id\":102}"), sim.writes.toList())
        assertEquals(2, GatewayRobotReader(gw).currentMap()!!.floor)
    }

    @Test
    fun `연속으로 2층 3층 1층으로 전환한다`() {
        assertTrue(switchTo(2, 102).success)
        assertTrue(switchTo(3, 103).success)
        assertTrue(switchTo(1, 101).success)
        assertEquals(1, GatewayRobotReader(gw).robotPos()!!.floor)
    }

    @Test
    fun `dry-run 은 로봇에 아무것도 보내지 않는다`() {
        val dry = DryRunCommands()
        val r = switchTo(2, 102, dry)
        assertTrue(r.success && r.dryRun)
        assertTrue(sim.writes.isEmpty())
        assertEquals(1, GatewayRobotReader(gw).robotPos()!!.floor)
        assertEquals(1, dry.recorded.size)
    }

    @Test
    fun `로봇이 거부하면 위치 지정에서 멈춘다`() {
        sim.mode = Mode.REJECT
        val r = switchTo(2, 102)
        assertEquals(StepId.RELOCATE, r.failedAt)
        assertEquals(2, sim.writes.size)   // 첫 경로 + 재시도 경로
    }

    @Test
    fun `인식이 끝나지 않으면 제한 시간 뒤 멈춘다`() {
        sim.mode = Mode.NEVER_LOCATES
        assertEquals(StepId.VERIFY, switchTo(2, 102).failedAt)
    }

    @Test
    fun `다른 층으로 인식되면 멈춘다`() {
        sim.mode = Mode.WRONG_FLOOR
        assertEquals(StepId.VERIFY, switchTo(2, 102).failedAt)
    }

    @Test
    fun `청소 앱 지도가 따라오지 않으면 지도 전환 요청으로 맞춘다`() {
        sim.mode = Mode.MAP_STUCK
        val r = switchTo(2, 102)
        assertTrue(r.steps.joinToString { "${it.step}:${it.message}" }, r.success)
        assertTrue(sim.writes.last().startsWith("POST /robotLoadMapOpenPosition"))
        assertEquals(2, GatewayRobotReader(gw).currentMap()!!.floor)
    }

    @Test
    fun `청소 중이면 사전 점검에서 멈추고 아무것도 보내지 않는다`() {
        sim.mode = Mode.CLEANING
        val r = switchTo(2, 102)
        assertEquals(StepId.PRECHECK, r.failedAt)
        assertTrue(sim.writes.isEmpty())
    }

    @Test
    fun `floor 와 type 필드가 없는 응답 형식에서도 층 전환이 된다`() {
        sim.mode = Mode.LEGACY_FIELDS
        val r = switchTo(2, 102)
        assertTrue(r.steps.joinToString { "${it.step}:${it.message}" }, r.success)
    }

    @Test
    fun `알 수 없는 지표점은 사전 점검에서 막는다`() {
        val r = switchTo(2, 999)
        assertEquals(StepId.PRECHECK, r.failedAt)
        assertTrue(sim.writes.isEmpty())
    }

    @Test
    fun `서버가 없으면 읽기는 null 이고 사전 점검에서 멈춘다`() {
        server.stop()
        val reader = GatewayRobotReader(gw)
        assertEquals(null, reader.floors())
        assertEquals(null, reader.positionStatus())
        val r = FloorSwitchSequencer(reader, LiveCommands(gw), config = fast).run(FloorTarget(2, 102)) { true }
        assertFalse(r.success)
        assertEquals(StepId.PRECHECK, r.failedAt)
        assertNotNull(r.summary)
        // tearDown 의 중복 stop 이 안전해야 한다
        server = MockRobotServer(sim, 0, 0, bindAll = false)
    }
}
