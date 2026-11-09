CREATE TABLE IF NOT EXISTS spans (
 trace_id FixedString(32), span_id FixedString(16), parent_span_id String,
 service LowCardinality(String), name String, kind LowCardinality(String),
 start_time DateTime64(9,'UTC'), duration_ns UInt64, error Bool, http_status UInt16,
 route String, peer String, database Bool, attributes Map(String,String), version UInt64,
 INDEX idx_trace trace_id TYPE bloom_filter(0.01) GRANULARITY 1
) ENGINE=ReplacingMergeTree(version)
PARTITION BY toDate(start_time) ORDER BY (service,start_time,trace_id,span_id)
TTL toDate(start_time)+INTERVAL 7 DAY
SETTINGS non_replicated_deduplication_window=100000;

CREATE TABLE IF NOT EXISTS traces (
 trace_id FixedString(32), start_time DateTime64(9,'UTC'), end_time DateTime64(9,'UTC'),
 duration_ns UInt64, root_service LowCardinality(String), root_name String,
 services Array(String), span_count UInt32, error_count UInt32, kept_reason LowCardinality(String),
 known_incomplete Bool, finalization_reason LowCardinality(String), late_span_count UInt32, version UInt64,
 INDEX idx_time start_time TYPE minmax GRANULARITY 1
) ENGINE=ReplacingMergeTree(version) ORDER BY trace_id
TTL toDate(start_time)+INTERVAL 7 DAY
SETTINGS non_replicated_deduplication_window=100000;

CREATE TABLE IF NOT EXISTS trace_index (
 trace_id FixedString(32), start_time DateTime64(9,'UTC'), end_time DateTime64(9,'UTC'), version UInt64
) ENGINE=ReplacingMergeTree(version) ORDER BY trace_id
TTL toDate(start_time)+INTERVAL 7 DAY
SETTINGS non_replicated_deduplication_window=100000;

CREATE TABLE IF NOT EXISTS red_minute (
 minute DateTime('UTC'), service LowCardinality(String), operation String,
 requests UInt64, errors UInt64, latency_buckets Array(UInt64)
) ENGINE=MergeTree PARTITION BY toYYYYMM(minute) ORDER BY (service,operation,minute)
TTL minute+INTERVAL 30 DAY SETTINGS non_replicated_deduplication_window=100000;

CREATE TABLE IF NOT EXISTS edges_minute (
 minute DateTime('UTC'), caller LowCardinality(String), callee LowCardinality(String),
 callee_kind LowCardinality(String), calls UInt64, errors UInt64, latency_buckets Array(UInt64)
) ENGINE=MergeTree PARTITION BY toYYYYMM(minute) ORDER BY (caller,callee,minute)
TTL minute+INTERVAL 30 DAY SETTINGS non_replicated_deduplication_window=100000;
