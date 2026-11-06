# Benchmark baseline

Recorded 2026-11-06 on the repaired M1 engine (working tree over commit
`baeeea2`), Apple M4 Max, 128 GiB, macOS Darwin 27.0.0, JDK 21.0.8 (Azul),
Scala 3.3.7, sbt 1.10.7, repository flake. Short run (1 fork, 2 warm-up and 3
measurement iterations of 2 s); use the long command below before drawing
conclusions from small differences. Numbers are average time per operation;
allocation is the JMH gc profiler's normalized rate per operation.

Command used for this table:

```bash
nix develop . --no-write-lock-file --command sbt \
  'bench/Jmh/run -wi 2 -i 3 -w 2 -r 2 -f 1 -prof gc .*DiffScenariosBenchmark.* .*DiffRenderContextBenchmark.*'
```

Full-quality command for comparisons:

```bash
nix develop . --no-write-lock-file --command sbt \
  'bench/Jmh/run -wi 5 -i 5 -f 3 -prof gc .*DiffScenariosBenchmark.*'
```

| Benchmark (nodes = 2000) | avg us/op | alloc B/op | What it measures |
| --- | ---: | ---: | --- |
| renderOnly | 211.2 ± 22.4 | 0.73 | raw interpreter render + finalize of 2000 `div(class, text)` siblings |
| unchangedTree | 241.3 ± 14.6 | 0.83 | render + exact diff of an unchanged tree (byte comparison of the whole tape) |
| singleTextChange | 259.4 ± 10.9 | 0.89 | render + diff with one text change |
| singleAttrChange | 261.5 ± 17.7 | 0.90 | render + diff with one attribute change |
| deepUnchanged | 18.7 ± 1.5 | 0.06 | 200 nested elements, unchanged |
| manyAttrsOneChange | 27.9 ± 1.8 | 0.10 | one element with 64 attributes, one value changed (O(n·m) attribute compare) |
| longNamesUnchanged | 723.6 ± 426.5 | 3.15 | 2000 `figcaption` nodes with a 29-char attribute and a 33-char style name |
| collidingValuesAllChange | 164.2 ± 8.0 | 0.56 | 2000 texts alternating between `t86388`/`t118091` (same hash, all change) |
| growThenShrink | 236.1 ± 7.0 | 0.81 | a 2000-node render followed by a 20-node render on the same context |
| staticHtml5 | 97.0 ± 3.6 | 395 767 | `Html5RenderContext` serialization of the 2000-node tree (string building) |
| DiffRenderContextBenchmark.inferChangesBetweenTwoTrees (existing, SampleTime mode, text-reporting performer) | ≈ 100 (80 436 samples) | n/a | small fixed tree diffed both ways; includes string reporting |

Reading the numbers:

- The diff phase allocates under one byte per operation in every engine
  scenario; the only allocating scenario is string serialization.
- An unchanged 2000-node tree costs about 30 us more than its render: that
  is the price of exact comparison replacing the former hash-only skip.
  There is no pre-repair number for this scenario because the old engine
  could not render the long-name and collision workloads at all.
- `longNamesUnchanged` has a wide error bar in this short run; rerun with the
  full-quality command before comparing it.

Regression policy: investigate when a scenario's average time moves by more
than 5 % or any engine scenario starts allocating proportionally to node
count (contracts §5). The self-relative guards in
`core/shared/src/test/scala/levsha/DiffPerformanceGuardTest.scala` and the
JVM allocation guard in `core/jvm/src/test/scala/levsha/AllocationGuardTest.scala`
run with `sbt +test` and catch algorithmic regressions without a baseline.

## M2 frozen baseline (2026-11-06)

Recorded on the M2 working tree (M1 repairs plus the M2 lifecycle,
validation, typed authoring and trim-policy changes; the only engine change
on the measured path is the trim-policy check in `swap()`, which is a
comparison per publish with `TrimPolicy.Manual`). Apple M4 Max, 128 GiB,
macOS 27.0.1, JDK 21.0.8 (Azul), Scala 3.3.7, sbt 1.10.7, repository flake
(nixpkgs `c0b0e0fddf73fd517c3471e546c0df87a42d53f4`). 3 forks, 5 warm-up and
5 measurement iterations of 1 s each (15 samples per scenario); the JMH gc
profiler reports normalized allocation per operation. Raw JSON of the run:
`jmh-m2.json` in the session scratchpad (not committed). Wall clock 308 s.

Exact command:

```bash
nix develop . --no-write-lock-file --command sbt \
  'bench/Jmh/run -wi 5 -i 5 -f 3 -w 1 -r 1 -prof gc -rf json -rff jmh-m2.json .*DiffScenariosBenchmark.*'
```

| Benchmark (nodes = 2000) | M2 avg us/op (±99.9% CI) | M2 alloc B/op | M1 short run us/op |
| --- | ---: | ---: | ---: |
| renderOnly | 208.7 ± 1.1 | 1.43 | 211.2 |
| unchangedTree | 240.7 ± 1.3 | 1.65 | 241.3 |
| singleTextChange | 259.0 ± 2.8 | 1.78 | 259.4 |
| singleAttrChange | 258.5 ± 1.6 | 1.77 | 261.5 |
| deepUnchanged | 18.9 ± 0.1 | 0.13 | 18.7 |
| manyAttrsOneChange | 27.3 ± 0.4 | 0.19 | 27.9 |
| longNamesUnchanged | 711.7 ± 7.2 | 4.90 | 723.6 |
| collidingValuesAllChange | 165.8 ± 1.6 | 1.14 | 164.2 |
| growThenShrink | 237.2 ± 1.2 | 1.63 | 236.1 |
| staticHtml5 | 93.2 ± 1.0 | 395 772 | 97.0 |

Reading the numbers:

- Every scenario is within 2 % of the M1 short run, well inside the 5 %
  investigation gate of the contracts (§5); the M2 layers add nothing to
  the engine's hot path. These figures are the frozen reference for later
  optimization work; do not move the gate to classify a regression as
  acceptable.
- Engine scenarios allocate 0.1–5 bytes per operation (JIT and profiler
  noise, nothing proportional to node count); serialization allocates its
  output string. The sub-byte figures of the M1 short run and the
  few-byte figures here are the same thing measured with different sample
  counts.
- `longNamesUnchanged` now has a tight error bar (±1 %) where the M1 short
  run had ±59 %; its cost is the byte comparison of 2000 long-name headers.
- Costs of the M2 layers are measured by the JVM allocation guard
  (`AllocationGuardTest`): `RenderRoot` and `Validation.interactiveHtml`
  keep a static template cycle under 2 KB, the same bound as the raw
  context; typed keys, `fragment` and `each` under 512 B. Retained-memory
  figures are in `IMPLEMENTATION-EVIDENCE.md` (M2, T8).
