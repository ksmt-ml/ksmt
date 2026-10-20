package io.ksmt.profiling

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.KTheory
import io.ksmt.solver.model.KModelImpl
import io.ksmt.sort.KBoolSort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

class KRecordingSolverProfilerConcurrencyTest {
    private val ctx = KContext()

    @Test
    fun `events from several threads are stored in sequence order`() {
        val profiler = profiler(StubSolver(ctx))
        val threadCount = 8
        val callsPerThread = 200

        val threads = (0 until threadCount).map {
            thread {
                repeat(callsPerThread) {
                    profiler.push()
                    profiler.assert(ctx.mkTrue())
                    profiler.pop()
                }
            }
        }
        threads.forEach { it.join() }

        val events = profiler.snapshot().events
        assertEquals(threadCount * callsPerThread * 3, events.size)
        events.forEachIndexed { index, event ->
            assertEquals(index.toLong(), event.metadata.sequenceNumber)
        }
        assertEquals(threadCount * callsPerThread.toLong(), events.last().metadata.totalAssertionCount)
    }

    @Test
    fun `event limit keeps an exact prefix under concurrency`() {
        val profiler = KRecordingSolverProfiler(
            delegate = StubSolver(ctx),
            solver = KSolverDescriptor("test"),
            maxRecordedEvents = 50,
        )

        (0 until 4).map { thread { repeat(100) { profiler.push() } } }.forEach { it.join() }

        val profile = profiler.snapshot()
        assertEquals(50, profile.events.size)
        assertEquals(350L, profile.droppedEventCount)
        profile.events.forEachIndexed { index, event ->
            assertEquals(index.toLong(), event.metadata.sequenceNumber)
        }
    }

    @Test
    fun `interrupt is forwarded while another thread is inside check`() {
        val delegate = BlockingCheckSolver(ctx)
        val profiler = profiler(delegate)

        val checker = thread { profiler.check() }
        assertTrue(delegate.checkStarted.await(5, TimeUnit.SECONDS))

        // The check holds the profiler lock; interrupt must still reach the delegate.
        profiler.interrupt()
        checker.join(5_000)

        assertTrue(delegate.interrupted)
        val events = profiler.snapshot().events
        assertEquals(2, events.size)
        assertIs<KSolverCheckEvent>(events[0])
        val interrupt = assertIs<KSolverLifecycleEvent>(events[1])
        assertEquals(KSolverLifecycleEventKind.INTERRUPT, interrupt.kind)
        assertEquals(1L, interrupt.metadata.sequenceNumber)
    }

    @Test
    fun `observed duration stops growing after close`() {
        val profiler = profiler(StubSolver(ctx))

        profiler.close()
        val first = profiler.snapshot().observedDuration
        Thread.sleep(50)
        val second = profiler.snapshot().observedDuration

        assertEquals(first, second)
    }

    @Test
    fun `second close keeps the closed state`() {
        val profiler = profiler(StubSolver(ctx))

        profiler.close()
        profiler.close()

        val profile = profiler.snapshot()
        assertEquals(KSolverProfileState.CLOSED, profile.state)
        assertEquals(2, profile.events.size)
    }

    @Test
    fun `delegate exception is rethrown as the same object and recorded`() {
        val failure = IllegalStateException("check failure")
        val profiler = profiler(StubSolver(ctx, checkFailure = failure))

        val actual = assertFailsWith<IllegalStateException> { profiler.check() }

        assertSame(failure, actual)
        val check = assertIs<KSolverCheckEvent>(profiler.snapshot().events.single())
        assertEquals(KSolverCallOutcome.FAILURE, check.metadata.outcome)
        assertEquals(IllegalStateException::class.java.name, check.metadata.failureType)
    }

    @Test
    fun `failed close marks an active profile`() {
        val failure = IllegalStateException("close failure")
        val profiler = profiler(StubSolver(ctx, closeFailure = failure))

        val actual = assertFailsWith<IllegalStateException> { profiler.close() }

        assertEquals(failure, actual)
        assertEquals(KSolverProfileState.CLOSE_FAILED, profiler.snapshot().state)
    }

    @Test
    fun `active assertion count follows push assert and pop`() {
        val profiler = profiler(StubSolver(ctx))

        profiler.assert(ctx.mkTrue())
        profiler.push()
        profiler.assert(listOf(ctx.mkTrue(), ctx.mkFalse()))
        profiler.push()
        profiler.assertAndTrack(ctx.mkTrue())
        profiler.pop(2u)

        val metadata = profiler.snapshot().events.map { it.metadata }
        assertEquals(listOf(1L, 1L, 3L, 3L, 4L, 1L), metadata.map { it.activeAssertionCount })
        assertEquals(listOf(0, 1, 1, 2, 2, 0), metadata.map { it.stackDepth })
        assertEquals(4L, metadata.last().totalAssertionCount)
    }

    @Test
    fun `nested tasks restore the previous task even after an exception`() {
        val profiler = profiler(StubSolver(ctx))
        val outer = KSolverTask("outer")
        val inner = KSolverTask("inner")

        profiler.withTask(outer) {
            push()
            runCatching<Unit> {
                withTask(inner) {
                    push()
                    error("boom")
                }
            }
            push()
        }
        profiler.push()

        val tasks = profiler.snapshot().events.map { it.metadata.task?.id }
        assertEquals(listOf("outer", "inner", "outer", null), tasks)
    }

    @Test
    fun `assumption checks and tracked asserts are recorded`() {
        val profiler = profiler(StubSolver(ctx))
        val assumption = ctx.mkTrue()

        profiler.assertAndTrack(ctx.mkTrue())
        val status = profiler.checkWithAssumptions(listOf(assumption))
        profiler.unsatCore()

        val events = profiler.snapshot().events
        assertEquals(KSolverStatus.SAT, status)
        assertEquals(true, assertIs<KSolverAssertEvent>(events[0]).tracked)
        val check = assertIs<KSolverCheckEvent>(events[1])
        assertEquals(listOf(assumption), check.assumptions)
        val access = assertIs<KSolverResultAccessEvent>(events[2])
        assertEquals(KSolverResultAccessKind.UNSAT_CORE, access.kind)
        assertEquals(check.checkId, access.checkId)
    }

    @Test
    fun `result access before any check has no check id`() {
        val profiler = profiler(StubSolver(ctx))

        profiler.reasonOfUnknown()

        val access = assertIs<KSolverResultAccessEvent>(profiler.snapshot().events.single())
        assertNull(access.checkId)
    }

    private fun profiler(delegate: KSolver<TestConfiguration>) =
        KRecordingSolverProfiler(delegate = delegate, solver = KSolverDescriptor("test"))

    private class TestConfiguration : KSolverConfiguration {
        override fun setBoolParameter(param: String, value: Boolean) = Unit
        override fun setIntParameter(param: String, value: Int) = Unit
        override fun setStringParameter(param: String, value: String) = Unit
        override fun setDoubleParameter(param: String, value: Double) = Unit
        override fun optimizeForTheories(theories: Set<KTheory>?, quantifiersAllowed: Boolean) = Unit
    }

    private open class StubSolver(
        private val ctx: KContext,
        private val closeFailure: Throwable? = null,
        private val checkFailure: Throwable? = null,
    ) : KSolver<TestConfiguration> {
        override fun configure(configurator: TestConfiguration.() -> Unit) = TestConfiguration().configurator()
        override fun assert(expr: KExpr<KBoolSort>) = Unit
        override fun assertAndTrack(expr: KExpr<KBoolSort>) = Unit
        override fun push() = Unit
        override fun pop(n: UInt) = Unit
        override fun check(timeout: Duration): KSolverStatus {
            checkFailure?.let { throw it }
            return KSolverStatus.SAT
        }

        override fun checkWithAssumptions(
            assumptions: List<KExpr<KBoolSort>>,
            timeout: Duration,
        ): KSolverStatus = KSolverStatus.SAT

        override fun model(): KModel = KModelImpl(ctx, emptyMap(), emptyMap())
        override fun unsatCore(): List<KExpr<KBoolSort>> = emptyList()
        override fun reasonOfUnknown(): String = "stub"
        override fun interrupt() = Unit

        override fun close() {
            closeFailure?.let { throw it }
        }
    }

    /** A solver whose check blocks until it is interrupted. */
    private class BlockingCheckSolver(ctx: KContext) : StubSolver(ctx) {
        val checkStarted = CountDownLatch(1)
        private val interruptedLatch = CountDownLatch(1)

        @Volatile
        var interrupted = false

        override fun check(timeout: Duration): KSolverStatus {
            checkStarted.countDown()
            interruptedLatch.await(5, TimeUnit.SECONDS)
            return KSolverStatus.UNKNOWN
        }

        override fun interrupt() {
            interrupted = true
            interruptedLatch.countDown()
        }
    }
}
