# Solver workload profiling contracts

The optional `ksmt-profiling` module defines an observation-only, interface
boundary for incremental solver workloads. It does not contain a profiler
implementation, configuration recommender, model training, configuration
search, ONNX Runtime, or a model.

## Profile model

`KSolverProfile` describes one bounded incremental workload run:

- solver name, version, and attributes;
- native-default or unknown base configuration plus observed overrides;
- ordered, timed solver events;
- asserted KSMT formulas and per-check assumptions;
- `push` and `pop` operations needed to reconstruct the active formula;
- check timeout, status, and optional reason for `UNKNOWN`;
- active and total assertion counts, task context, and failures.

Configuration values are observational data only. KSMT does not define patch
application, recommendation decisions, or a tuner lifecycle.

Expressions stay as in-memory `KExpr<KBoolSort>` objects. Serializing formulas
or producing consumer-specific feature tensors is intentionally outside the
KSMT contract.

## Extension point

`KSolverProfiler<Config>` extends `KSolver<Config>` and specifies the future
transparent profiling decorator. It exposes an immutable `snapshot` and
application task context. KSMT does not yet provide its implementation.

Consumers may use a profile for diagnostics, benchmarking, or external tuning,
but those policies and their output types belong to the consumer. In
particular, cvc5 option whitelists, configuration patches, feature projection,
ONNX adapters, decoding, and fallback do not belong in KSMT.
