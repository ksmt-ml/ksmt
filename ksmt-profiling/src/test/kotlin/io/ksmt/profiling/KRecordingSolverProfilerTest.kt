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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration

class KRecordingSolverProfilerTest {
    private val ctx = KContext()

    @Test
    fun `records an incremental session without replacing bulk delegate calls`() {
        val delegate = RecordingSolver(ctx)
        val profiler = profiler(delegate)
        val expressions = listOf(ctx.mkTrue(), ctx.mkFalse())

        profiler.withTask(KSolverTask("fork-7", kind = "tsa-fork")) {
            push()
            assert(expressions)
            checkWithAssumptions(listOf(ctx.mkTrue()), Duration.INFINITE)
            pop()
        }

        val profile = profiler.snapshot()
        assertEquals(1, delegate.bulkAssertionCalls)
        assertEquals(4, profile.events.size)
        assertEquals(KSolverProfile.CURRENT_SCHEMA_VERSION, profile.schemaVersion)

        assertIs<KSolverPushEvent>(profile.events[0])
        val assertion = assertIs<KSolverAssertBatchEvent>(profile.events[1])
        assertSame(expressions[0], assertion.expressions[0])
        assertEquals(2, assertion.metadata.activeAssertionCount)
        assertEquals(1, assertion.metadata.stackDepth)
        assertEquals("fork-7", assertion.metadata.task?.id)

        val check = assertIs<KSolverCheckEvent>(profile.events[2])
        assertEquals(0, check.checkId)
        assertEquals(KSolverStatus.UNKNOWN, check.status)
        assertEquals(1, check.assumptions.size)

        val pop = assertIs<KSolverPopEvent>(profile.events[3])
        assertEquals(0, pop.metadata.activeAssertionCount)
        assertEquals(0, pop.metadata.stackDepth)
        assertEquals(2, pop.metadata.totalAssertionCount)
    }

    @Test
    fun `attaches unknown reason only after caller requests it`() {
        val profiler = profiler(RecordingSolver(ctx))

        profiler.check()
        assertEquals(null, (profiler.snapshot().events.single() as KSolverCheckEvent).reasonOfUnknown)

        assertEquals("test unknown", profiler.reasonOfUnknown())
        val events = profiler.snapshot().events
        assertEquals("test unknown", (events[0] as KSolverCheckEvent).reasonOfUnknown)
        assertEquals(KSolverResultAccessKind.REASON_OF_UNKNOWN, (events[1] as KSolverResultAccessEvent).kind)
    }

    @Test
    fun `records failures and rethrows the same exception`() {
        val failure = IllegalStateException("delegate failure")
        val delegate = RecordingSolver(ctx, checkFailure = failure)
        val profiler = profiler(delegate)

        val actual = assertFailsWith<IllegalStateException> { profiler.check() }
        assertSame(failure, actual)

        val event = assertIs<KSolverCheckEvent>(profiler.snapshot().events.single())
        assertEquals(KSolverCallOutcome.FAILURE, event.metadata.outcome)
        assertEquals(IllegalStateException::class.java.name, event.metadata.failureType)
        assertEquals(null, event.status)
    }

    @Test
    fun `bounds retained events and reports truncation`() {
        val profiler =
            KRecordingSolverProfiler(
                delegate = RecordingSolver(ctx),
                solver = KSolverDescriptor("test"),
                maxRecordedEvents = 1,
            )

        profiler.push()
        profiler.pop()

        val profile = profiler.snapshot()
        assertEquals(1, profile.events.size)
        assertEquals("true", profile.attributes[KRecordingSolverProfiler.PROFILE_TRUNCATED_ATTRIBUTE])
        assertEquals("1", profile.attributes[KRecordingSolverProfiler.DROPPED_EVENT_COUNT_ATTRIBUTE])
    }

    @Test
    fun `preserves and records empty batches and zero-level pop`() {
        val delegate = RecordingSolver(ctx)
        val profiler = profiler(delegate)

        profiler.assert(emptyList())
        profiler.pop(0u)

        val events = profiler.snapshot().events
        assertEquals(1, delegate.bulkAssertionCalls)
        assertEquals(emptyList(), (events[0] as KSolverAssertBatchEvent).expressions)
        assertEquals(0u, (events[1] as KSolverPopEvent).levels)
    }

    @Test
    fun `links profile identity and records successful close`() {
        val profiler =
            KRecordingSolverProfiler(
                delegate = RecordingSolver(ctx),
                solver = KSolverDescriptor("test"),
                profileId = "profile-0",
                runId = "run-0",
                parentProfileId = "parent-0",
            )

        assertEquals(KSolverProfileState.ACTIVE, profiler.snapshot().state)
        profiler.close()

        val profile = profiler.snapshot()
        assertEquals("profile-0", profile.profileId)
        assertEquals("run-0", profile.runId)
        assertEquals("parent-0", profile.parentProfileId)
        assertEquals(KSolverProfileState.CLOSED, profile.state)
    }

    private fun profiler(delegate: RecordingSolver) =
        KRecordingSolverProfiler(
            delegate = delegate,
            solver = KSolverDescriptor("test", version = "v0"),
        )

    private class TestConfiguration : KSolverConfiguration {
        override fun setBoolParameter(param: String, value: Boolean) = Unit
        override fun setIntParameter(param: String, value: Int) = Unit
        override fun setStringParameter(param: String, value: String) = Unit
        override fun setDoubleParameter(param: String, value: Double) = Unit
        override fun optimizeForTheories(theories: Set<KTheory>?, quantifiersAllowed: Boolean) = Unit
    }

    private class RecordingSolver(
        private val ctx: KContext,
        private val checkFailure: Throwable? = null,
    ) : KSolver<TestConfiguration> {
        var bulkAssertionCalls = 0

        override fun configure(configurator: TestConfiguration.() -> Unit) = TestConfiguration().configurator()
        override fun assert(expr: KExpr<KBoolSort>) = Unit

        override fun assert(exprs: List<KExpr<KBoolSort>>) {
            bulkAssertionCalls++
        }

        override fun assertAndTrack(expr: KExpr<KBoolSort>) = Unit
        override fun push() = Unit
        override fun pop(n: UInt) = Unit

        override fun check(timeout: Duration): KSolverStatus {
            checkFailure?.let { throw it }
            return KSolverStatus.UNKNOWN
        }

        override fun checkWithAssumptions(
            assumptions: List<KExpr<KBoolSort>>,
            timeout: Duration,
        ): KSolverStatus = KSolverStatus.UNKNOWN

        override fun model(): KModel = KModelImpl(ctx, emptyMap(), emptyMap())
        override fun unsatCore(): List<KExpr<KBoolSort>> = emptyList()
        override fun reasonOfUnknown(): String = "test unknown"
        override fun interrupt() = Unit
        override fun close() = Unit
    }
}
