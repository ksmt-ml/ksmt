# Solver workload profiling and configuration recommendation contracts

The optional `ksmt-tuning` module defines an interface-only boundary. It does
not contain a profiler implementation, model training, configuration search,
ONNX Runtime, or a model.

## Profile model

`KSolverProfile` describes one bounded incremental workload run:

- solver name, version, and attributes;
- native-default or unknown base configuration plus known overrides;
- ordered, timed solver events;
- asserted KSMT formulas and per-check assumptions;
- `push` and `pop` operations needed to reconstruct the active formula;
- check timeout, status, and optional reason for `UNKNOWN`;
- active and total assertion counts, task context, and failures.

Expressions stay as in-memory `KExpr<KBoolSort>` objects. Serializing formulas
or producing model tensors is intentionally outside the KSMT contract.

## Extension points

`KSolverProfiler<Config>` extends `KSolver<Config>` and specifies the future
transparent profiling decorator. It exposes an immutable `snapshot` and
application task context. KSMT does not yet provide its implementation.

`KSolverTuner` consumes an immutable profile and returns either
`UseConfiguration` or `KeepCurrentConfiguration`. The tuner never owns or
mutates a solver. An external library implements this interface, and the
application applies a successful patch while constructing a fresh solver for a
subsequent run.

The first external implementation targets cvc5 and performs fast inference
with a pre-trained model. Training, runtime search, replay evaluators, and time
budgets are not part of this contract. The cvc5 option whitelist, feature
projection, ONNX adapter, and output validation do not belong in KSMT.
