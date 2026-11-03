# ksmt-profiling

Solver-agnostic profiling for KSMT. The module records the chronology of calls made to a
`KSolver` (`configure`, `assert`, `push`, `pop`, `check`, `model`, `unsatCore`, ...) and exposes it
as an immutable `KSolverProfile`.

`KRecordingSolverProfiler` is the implementation. It wraps any `KSolver` and does not depend on a
specific solver. cvc5 is the primary target.

## Requirements

- JDK 17 (the version the module was built and tested with).
- No separate Gradle installation: use the `gradlew` wrapper from the repository root.
- Internet access on the first build to download dependencies.
- No cvc5 build is needed. The cvc5 jar and native libraries are stored in `ksmt-cvc5/dist`.
  See [`ksmt-cvc5/dist/build.md`](../ksmt-cvc5/dist/build.md) for their origin.

Make sure `JAVA_HOME` points to JDK 17. Run all commands from the repository root.

| OS | Wrapper |
| --- | --- |
| Linux / macOS | `./gradlew` |
| Windows (PowerShell) | `.\gradlew.bat` |

The examples below use `./gradlew`.

## Build and test

```bash
# Compile the module
./gradlew :ksmt-profiling:build

# Unit tests and static analysis
./gradlew :ksmt-profiling:test :ksmt-profiling:detekt
```

Expected result: `BUILD SUCCESSFUL`, 20 unit tests pass, 2 benchmark tests are skipped.

Test reports are written to `ksmt-profiling/build/test-results/test/`. If Gradle reports
`UP-TO-DATE` and does not run the tests again, add `:ksmt-profiling:cleanTest` before
`:ksmt-profiling:test`.

## Usage

```kotlin
val solver = KCvc5Solver(ctx)
val profiler = KRecordingSolverProfiler(
    delegate = solver,
    solver = KSolverDescriptor(name = "cvc5"),
    maxRecordedEvents = 1_000_000,
)

profiler.push()
profiler.assert(formula)
profiler.check()
profiler.pop(1u)

val profile = profiler.snapshot() // immutable chronology of the calls
profiler.close()
```

Use the profiler as a regular `KSolver`. Notes:

- Exceptions thrown by the wrapped solver are rethrown as the same object and are recorded in the
  profile.
- `configure` is recorded as an opaque event (`overrides = null`) because the contents of the
  lambda cannot be inspected. Options that must be visible in the profile should be passed through
  `KSolverProfilerRequest.initialConfiguration`.
- Events are stored in strict order. When `maxRecordedEvents` is reached, the profile keeps the
  first events and reports that it was truncated.
- `interrupt()` can be called from another thread while `check` is running.
- `model`, `unsatCore` and `reasonOfUnknown` accesses are linked to the last successful check.

## Overhead benchmark

`ProfilerOverheadBenchmark` compares a bare cvc5 solver with the same solver under the profiler
and estimates memory per recorded event. It is **disabled by default** so that it does not slow
down regular test runs. Enable it with the environment variable `KSMT_PROFILING_BENCHMARK=true`.

```bash
# Linux / macOS
KSMT_PROFILING_BENCHMARK=true ./gradlew :ksmt-profiling:cleanTest :ksmt-profiling:test \
    --tests "*ProfilerOverheadBenchmark*"
```

```powershell
# Windows (PowerShell)
$env:KSMT_PROFILING_BENCHMARK = 'true'
.\gradlew.bat :ksmt-profiling:cleanTest :ksmt-profiling:test --tests "*ProfilerOverheadBenchmark*"
Remove-Item Env:KSMT_PROFILING_BENCHMARK   # do not leave it enabled
```

Gradle does not print the test output by default. Add `-i` or read the `system-out` section of
`ksmt-profiling/build/test-results/test/TEST-io.ksmt.profiling.ProfilerOverheadBenchmark.xml`.

Reference numbers from one development machine (synthetic workload on cvc5, median of 7 runs):

| Workload | Without profiler | With profiler |
| --- | --- | --- |
| 20 000 x `push/assert/pop` | 41.9 ms | 51.1 ms (x1.22) |
| 2 000 x `push/assert/check/pop` | 94.6 ms | 109.1 ms (x1.15, high variance) |

Memory: about 100 bytes per recorded event. Absolute numbers depend on the machine, so compare the
two configurations only within a single run. The workload is synthetic and does not replace a
measurement on real TSA workloads.

## Known limitations

- The overhead was not measured on real TSA workloads or with several threads.
- Serialization of `KSolverProfile` is outside of KSMT.

## Troubleshooting

- **Gradle fails with a Java version error.** Check `JAVA_HOME` and `java -version`: JDK 17.
- **The benchmark runs on every test run.** `KSMT_PROFILING_BENCHMARK` is still set in your shell.
