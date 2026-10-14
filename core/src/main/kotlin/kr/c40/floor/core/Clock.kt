package kr.c40.floor.core

/** 시퀀서의 대기 시간을 테스트에서 가상으로 흘려보내기 위한 시계. */
interface Clock {
    fun nowMs(): Long
    fun sleep(ms: Long)
}

object SystemClock : Clock {
    override fun nowMs() = System.currentTimeMillis()
    override fun sleep(ms: Long) = Thread.sleep(ms)
}
