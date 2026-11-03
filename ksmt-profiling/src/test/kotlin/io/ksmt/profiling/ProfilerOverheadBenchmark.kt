package io.ksmt.profiling

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.cvc5.KCvc5Solver
import io.ksmt.sort.KBoolSort
import io.ksmt.utils.mkConst
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.Locale
import kotlin.math.sqrt

/**
 * Opt-in measurement of the profiler overhead on a real cvc5 solver. It is skipped by default;
 * run it with the environment variable `KSMT_PROFILING_BENCHMARK=true`, for example:
 * `$env:KSMT_PROFILING_BENCHMARK='true'; gradlew :ksmt-profiling:test --tests "*ProfilerOverheadBenchmark*" -i`
 *
 * The results are printed to the console. The numbers are synthetic and are not a replacement
 * for measurements on a real workload.
 */
@EnabledIfEnvironmentVariable(named = "KSMT_PROFILING_BENCHMARK", matches = "true")
class ProfilerOverheadBenchmark {

    @Test
    fun `time overhead on incremental workloads`() {
        println("=== Time overhead (cvc5, ms per run; lower is better) ===")
        report("light: push/assert/pop x$LIGHT_CYCLES", withCheck = false, cycles = LIGHT_CYCLES)
        report("heavier: push/assert/check/pop x$CHECK_CYCLES", withCheck = true, cycles = CHECK_CYCLES)
    }

    @Test
    fun `memory held by recorded events`() {
        println("=== Memory held by the event log (shared formulas, cvc5) ===")
        for (cycles in listOf(10_000, 100_000)) {
            val ctx = KContext()
            val formula = ctx.boolSort.mkConst("b")
            val profiler = profiled(ctx, maxEvents = Int.MAX_VALUE)
            val before = usedMemory()
            runWorkload(profiler, formula, formula, cycles, withCheck = false)
            val snapshot = profiler.snapshot()
            val after = usedMemory()
            val perEvent = (after - before).toDouble() / snapshot.events.size
            println(
                String.format(
                    Locale.ROOT, "events=%d, retained=%.1f MB, ~%.0f bytes/event (includes the snapshot copy)",
                    snapshot.events.size, (after - before) / MEGABYTE, perEvent,
                ),
            )
            profiler.close()
            check(snapshot.events.isNotEmpty())
        }
    }

    private fun report(title: String, withCheck: Boolean, cycles: Int) {
        val bare = measure { ctx, formula, assumption -> runWorkload(KCvc5Solver(ctx), formula, assumption, cycles, withCheck) }
        val recorded = measure { ctx, formula, assumption ->
            runWorkload(profiled(ctx, maxEvents = 1_000_000), formula, assumption, cycles, withCheck)
        }
        val limited = measure { ctx, formula, assumption ->
            runWorkload(profiled(ctx, maxEvents = 10), formula, assumption, cycles, withCheck)
        }
        println(title)
        println("  bare               ${bare.format()}")
        println("  profiled           ${recorded.format()}  (x${ratio(recorded, bare)})")
        println("  profiled, limit 10 ${limited.format()}  (x${ratio(limited, bare)})")
    }

    private fun ratio(a: Stats, b: Stats): String = String.format(Locale.ROOT, "%.2f", a.median / b.median)

    private fun profiled(ctx: KContext, maxEvents: Int): KRecordingSolverProfiler<*> =
        KRecordingSolverProfiler(
            delegate = KCvc5Solver(ctx),
            solver = KSolverDescriptor(name = "cvc5"),
            maxRecordedEvents = maxEvents,
        )

    private fun measure(run: (KContext, KExpr<KBoolSort>, KExpr<KBoolSort>) -> Unit): Stats {
        val samples = ArrayList<Double>()
        repeat(WARMUP + RUNS) { index ->
            val ctx = KContext()
            val formula = ctx.boolSort.mkConst("a")
            val assumption = ctx.boolSort.mkConst("b")
            val start = System.nanoTime()
            run(ctx, formula, assumption)
            if (index >= WARMUP) samples += (System.nanoTime() - start) / NANOS_PER_MILLI
        }
        return Stats.of(samples)
    }

    private fun <C : KSolverConfiguration> runWorkload(
        solver: KSolver<C>,
        formula: KExpr<KBoolSort>,
        other: KExpr<KBoolSort>,
        cycles: Int,
        withCheck: Boolean,
    ) {
        solver.use {
            solver.assert(formula)
            repeat(cycles) {
                solver.push()
                solver.assert(other)
                if (withCheck) solver.check()
                solver.pop()
            }
        }
    }

    private fun usedMemory(): Long {
        repeat(GC_ROUNDS) { System.gc() }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private class Stats(val median: Double, val mean: Double, val stdDev: Double) {
        fun format(): String =
            String.format(Locale.ROOT, "median=%8.2f mean=%8.2f stdDev=%7.2f", median, mean, stdDev)

        companion object {
            fun of(samples: List<Double>): Stats {
                val sorted = samples.sorted()
                val mean = sorted.average()
                val variance = if (sorted.size > 1) sorted.sumOf { (it - mean) * (it - mean) } / (sorted.size - 1) else 0.0
                val median = if (sorted.size % 2 == 1) {
                    sorted[sorted.size / 2]
                } else {
                    (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
                }
                return Stats(median, mean, sqrt(variance))
            }
        }
    }

    private companion object {
        const val LIGHT_CYCLES = 20_000
        const val CHECK_CYCLES = 2_000
        const val WARMUP = 3
        const val RUNS = 7
        const val GC_ROUNDS = 3
        const val NANOS_PER_MILLI = 1_000_000.0
        const val MEGABYTE = 1024.0 * 1024.0
    }
}
