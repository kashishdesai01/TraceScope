# Independent engineering review

A separate read-only reviewer examined the implementation against the original scope and revised contracts, focusing on observability correctness and failure handling. The reviewer did not implement the fixes or run competing load tests.

| Finding | Resolution | Regression evidence |
|---|---|---|
| P1: malformed HTTP status could poison a ClickHouse writer frame | Map invalid/non-100–599 status attributes to unknown, preserving explicit OTLP errors | Invalid boundaries/overflow/nonnumeric fixtures; actual OTLP ingest and ClickHouse persistence remain healthy |
| P2: unavailable payment attributed to inventory | Distinct target-specific failed HTTP destinations and known-target identities | Actual HTTP path sequences and exact failed-edge unit assertions; dual-target public-API acceptance assertions |
| P2: trace investigation discarded persisted incompleteness | Shared persisted metadata in detail/MCP; UI warnings retain finalization reason and late counts | In-bounds late-child real-storage regression, max-age fixture and MCP qualifications |
| P2: sparse charts compressed time and bridged unknown buckets | Explicit empty buckets, UTC timestamp scale, null latency/error gaps and isolated-point markers | Sparse 12:00/12:10/12:11 coordinate/path assertions and backend bucket coverage |
| P2: missing ancestry flattened valid descendant links | Detach only absent immediate parents; preserve available component hierarchy and inherited incompleteness warnings | Regression reproduced flattened roots, then passed with exact component roots, links, depths, order/duplicate invariance and warnings |
| P2: concurrent rule creators could exceed the supported cap | Shared transaction-scoped PostgreSQL advisory lock for manual/discovered creation | Two concurrent creators blocked at99 rules; manual/manual and manual/discovery outcomes leave exactly100 |

The reviewer rechecked the corrected source and assertions and independently read the latest Java XML results: **54 tests, zero failures/errors/skips**, including ten real-storage integration tests. No remaining substantiated defect was found in the reviewed paths. The frontend author gate passed **19 tests** and production build; the reviewer inspected its relevant regression assertions without repeating that workload.

The standalone critical-path response describes structural/timing approximation from supplied spans. Trace detail and MCP additionally expose retained completeness metadata. That distinction is documented and was assessed explicitly during review.

Subsequent acceptance-harness review found and corrected two measurement defects: A8 now independently computes accuracy rather than passing with an uninitialized zero, and A2 recovery runs even when attribution assertions fail. The original failed A8 result is invalidated. A fixed healthy warmup must pass load and alert-state qualification before measurements; its history is retained. These changes were independently re-reviewed on source; runtime acceptance remains separate.

Both local Codex MCP investigations were captured and independently checked against the raw responses. They predate the GC configuration change. Final rebuilt-image acceptance, browser and kind are separate runtime gates. Their current results must be read from the corresponding reports; source-review sign-off alone does not establish those passes. Remote CI is not claimed without a real workflow run.

The reviewer also approved the prospective warmup isolation correction: a fixed300-second warmup, healthy actual final120-second rule windows, no open boundary incident, and210 seconds of post-baseline alert observation. Earlier resolved startup history remains visible. All original A1–A8 thresholds remain unchanged; paging alone is not a new gate. Three harness policy tests passed with zero failures/errors/skips. Current-image Chromium passed3/3 and K1 passed the actual local kind install/traffic/API checks; the revised full fault-acceptance trial remains outstanding.

Attempt5 subsequently failed warmup with266 dropped iterations and2 failed HTTP requests/5,734; A1–A8 did not run. This does not satisfy the required full acceptance gate. Its raw evidence and the stopped stack volumes are retained.

Independent evidence audit confirmed K1 from the ready pods,1,201 successful requests with zero drops and exact five-edge graph. It also confirmed attempt5 failed before A1–A8, including two approximately24.6/24.9-second503 root traces and an inventory telemetry-export timeout. Zero k6 transport errors does not imply successful telemetry export; a later healthy writer snapshot does not establish continuous health. No specific source defect or exact cause was established by these artifacts. Host paging is context, not a demonstrated cause.

R6 was discovered in the saved failed-run traces after the preceding audit. The complete55-test Java gate passes after the fix (21 model,29 server,5 demo;zero failures/errors/skips), and independent source rereview passed. The implementing agent additionally verified the rebuilt server against the same saved partial trace and reran all three browser tests successfully. Final full fault acceptance remains pending. Prior runtime artifacts use the pre-R6 model and remain historical evidence.

R6 final independent evidence audit passed: the reviewer read all55 Java results and3 browser passes, compared every raw span and metadata field in the old/new saved trace, and verified every retained parent link and component depth. No remaining substantiated source defect was identified in that focused fix. Full fault acceptance remains outstanding.
