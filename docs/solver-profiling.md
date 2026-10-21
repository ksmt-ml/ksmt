# Solver workload profiling contracts

The optional `ksmt-profiling` module defines an observation-only boundary for
incremental solver workloads. Its stable production surface is the profile and
profiler contracts; it does not own configuration recommendation, model
training, configuration search, ONNX Runtime, or a model.

The `dev/litvinovmitch11/demo` branch additionally contains
`KRecordingSolverProfiler` as a non-production reference implementation. It is
kept there to exercise the contracts end to end and is not part of the
production implementation commitment.

## Profile model

`KSolverProfile` describes one solver session from profiler construction
through the corresponding solver `close` call. A snapshot may be taken while
the session is still active:

- stable profile/run identifiers and session state;
- solver name, version, and attributes;
- native-default or unknown base configuration plus observed overrides;
- ordered solver events timed with `kotlin.time.Duration`;
- asserted KSMT formulas and per-check assumptions;
- `push` and `pop` operations needed to reconstruct the active formula;
- check timeout and status, plus explicit access to a backend-specific reason
  for `UNKNOWN`;
- active and total assertion counts, task context, and failures.

Failed solver calls are retained because a profile is an observation and must
not silently rewrite workload history. They are not configurations to tune.
Result-access events explicitly reference the check that produced the accessed
result when such a check exists.

Configuration values are observational data only. The initial snapshot is
needed to determine whether a workload can be reproduced from native defaults;
it is not a recommendation. KSMT does not define patch
application, recommendation decisions, or a tuner lifecycle.

Expressions stay as in-memory `KExpr<KBoolSort>` objects. Serializing formulas
or producing consumer-specific feature tensors is intentionally outside the
KSMT contract.

## Extension point

`KSolverProfilerFactory` wraps an existing solver using explicit session IDs,
solver metadata, initial configuration, attributes, and an event limit.
`KSolverProfiler<Config>` extends `KSolver<Config>` and specifies a transparent
decorator. It exposes a structurally independent `snapshot` and
optional application task context. Recording may continue after a snapshot,
but the returned collections must remain stable. Profiling must not turn a
successful delegate call into a failure. The stable contract intentionally
does not select or promise a production implementation; the demo branch's
recording decorator is reference code for validation and infrastructure work.

The event limit bounds retained AST references. A profile retains the ordered
event prefix up to that limit, continues forwarding every solver call, and
increments `droppedEventCount` for each later event. Session state and observed
duration continue to advance. Consumers must treat `isTruncated` profiles as
ineligible for exact replay.

Consumers may use a profile for diagnostics, benchmarking, or external tuning,
but those policies and their output types belong to the consumer. In
particular, cvc5 option whitelists, configuration patches, feature projection,
ONNX adapters, decoding, and fallback do not belong in KSMT.
