# Recorded MCP investigations

The investigator was Codex using the local Streamable HTTP MCP interface, as requested by the owner. Claude Code was not invoked. These are controlled synthetic-demo investigations with known injected faults, not blind diagnoses or an agent-accuracy benchmark. The application generated the HTTP/JDBC spans through the real OpenTelemetry Java agent.

## Payment-latency investigation

Captured at **2026-11-09T00:49:26.661176+00:00** against the rebuilt acceptance runtime. The client initialized MCP, listed all seven tools, then called `list_services`, `get_service_health`, `get_service_dependencies`, `find_slow_traces`, three `get_trace_summary` calls and `list_alerts`. All calls were read-only; no fault controls or local source files were exposed through MCP.

Observed checkout p95 was **815.847 ms** over the closed window00:46–00:49 UTC, compared with **14.898 ms** in00:43–00:46 UTC. The checkout→payment edge also had p95 **815.847 ms**, while checkout→inventory was **6.950 ms**. The checkout-latency alert was firing with available exemplars; its pending and firing timestamps were00:47:10.037802 and00:48:10.429826 UTC.

| Trace ID | Root ms | Largest attributed segment | Self ms | Root share |
|---|---:|---|---:|---:|
| `69a826768dbc680e45ae91e15cb6840c` | 808.403 | payment / POST /pay | 801.116 | 99.10% |
| `90cdd88c2d83300a26b023af08a473f4` | 808.770 | payment / POST /pay | 801.152 | 99.06% |
| `957c012a67c71446379ae5cb369af52d` | 807.530 | payment / POST /pay | 800.979 | 99.19% |

Diagnosis: the observed bottleneck was payment's `/pay` handler. Three inspected traces attributed approximately99.1% of root duration to that handler; database work and inventory were small in those examples. This agrees with the deliberately injected800 ms payment-handler delay. It establishes the tested synchronous attribution, not general causal correctness for arbitrary asynchronous workloads. Each summary retained finalization metadata (`root_idle`, no known incompleteness, zero late spans).

Raw initialized-session and tool-output JSON is retained locally under ignored `reports/runtime/mcp-A2.json`. It contains no bearer headers. The example IDs refer to this isolated local fixture; fresh runs generate different IDs.

## Inventory-error investigation

Captured at **2026-11-09T00:56:23.447831+00:00** through the same initialized read-only tool sequence, using `find_error_traces` instead of the slow-trace search. Checkout had587 observed errors among3,169 requests in the closed00:53–00:56 UTC window. Its error-rate alert was firing with available exemplars.

| Trace ID | Root ms | Deepest observed failing endpoint | HTTP status |
|---|---:|---|---:|
| `fc124692ddbc6a8215f4ef3049f0ab42` | 4.872 | inventory / POST /reserve | 500 |
| `c66c67c3d1b48bbcf330548af5c7ddda` | 9.195 | inventory / POST /reserve | 500 |
| `3f0a4f384d835011a50db7ad8a55124e` | 6.148 | inventory / POST /reserve | 500 |

Diagnosis: inventory `/reserve` returned500; checkout and gateway propagated503 responses. Each of the three inspected summaries named inventory first in its deepest-error ordering. Fast failure means the longest self-time segment need not be the origin of the error; the error-span chain supplies that evidence. Across the complete A4 measured load, the independent injection counter and TraceScope both recorded666 failures among3,601 requests (18.49%).

Raw output is retained locally at ignored `reports/runtime/mcp-A4.json`. These two recorded investigations demonstrate the observed incident paths, while the full acceptance suite remains failed on its separate A2 tail-exemplar70% requirement. The success of these examples does not override that failure.
