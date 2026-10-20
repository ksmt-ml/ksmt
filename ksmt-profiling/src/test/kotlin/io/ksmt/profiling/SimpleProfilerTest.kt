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
import kotlin.test.assertIs
import kotlin.time.Duration

class SimpleProfilerTest {

    @Test
    fun `profile contains push assert check pop in call order`() {
        val ctx = KContext()

        val fakeSolver = FakeSolver(ctx)

        val profiler = KRecordingSolverProfiler(
            delegate = fakeSolver,
            solver = KSolverDescriptor(name = "fake"),
        )

        val formula = ctx.mkTrue()

        profiler.push()
        profiler.assert(formula)
        val status = profiler.check()
        profiler.pop()

        assertEquals(KSolverStatus.SAT, status)

        val profile = profiler.snapshot()

        assertEquals(4, profile.events.size)

        // The first event is push.
        val push = assertIs<KSolverPushEvent>(profile.events[0])
        assertEquals(0L, push.metadata.sequenceNumber)
        assertEquals(1, push.metadata.stackDepth)

        // The second event is assert and it holds our formula.
        val assertEvent = assertIs<KSolverAssertEvent>(profile.events[1])
        assertEquals(formula, assertEvent.expression)
        assertEquals(1L, assertEvent.metadata.activeAssertionCount)

        // The third event is check and it holds the solver answer.
        val check = assertIs<KSolverCheckEvent>(profile.events[2])
        assertEquals(KSolverStatus.SAT, check.status)
        assertEquals(KSolverCallOutcome.SUCCESS, check.metadata.outcome)

        // The fourth event is pop: no scopes are left and the constraint is gone.
        val pop = assertIs<KSolverPopEvent>(profile.events[3])
        assertEquals(0, pop.metadata.stackDepth)
        assertEquals(0L, pop.metadata.activeAssertionCount)

        // But one constraint was added during the whole session.
        assertEquals(1L, pop.metadata.totalAssertionCount)
    }

    // A minimal real KSolverConfiguration
    private class FakeConfiguration : KSolverConfiguration {
        override fun setBoolParameter(param: String, value: Boolean) = Unit
        override fun setIntParameter(param: String, value: Int) = Unit
        override fun setStringParameter(param: String, value: String) = Unit
        override fun setDoubleParameter(param: String, value: Double) = Unit
        override fun optimizeForTheories(theories: Set<KTheory>?, quantifiersAllowed: Boolean) = Unit
    }

    // A fake solver: all methods are empty and check always returns SAT.
    private class FakeSolver(private val ctx: KContext) : KSolver<FakeConfiguration> {
        override fun configure(configurator: FakeConfiguration.() -> Unit) = FakeConfiguration().configurator()
        override fun assert(expr: KExpr<KBoolSort>) = Unit
        override fun assertAndTrack(expr: KExpr<KBoolSort>) = Unit
        override fun push() = Unit
        override fun pop(n: UInt) = Unit
        override fun check(timeout: Duration): KSolverStatus = KSolverStatus.SAT
        override fun checkWithAssumptions(
            assumptions: List<KExpr<KBoolSort>>,
            timeout: Duration,
        ): KSolverStatus = KSolverStatus.SAT

        override fun model(): KModel = KModelImpl(ctx, emptyMap(), emptyMap())
        override fun unsatCore(): List<KExpr<KBoolSort>> = emptyList()
        override fun reasonOfUnknown(): String = ""
        override fun interrupt() = Unit
        override fun close() = Unit
    }
}
