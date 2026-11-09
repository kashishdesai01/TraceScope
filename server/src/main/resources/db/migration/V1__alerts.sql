CREATE TABLE alert_rules (
 id UUID PRIMARY KEY,
 name VARCHAR(128) NOT NULL,
 service VARCHAR(128) NOT NULL,
 operation VARCHAR(256),
 kind TEXT NOT NULL CHECK(kind IN ('latency','error_rate')),
 percentile DOUBLE PRECISION,
 threshold DOUBLE PRECISION NOT NULL CHECK(threshold>0),
 window_sec INT NOT NULL DEFAULT 120 CHECK(window_sec BETWEEN 60 AND 3600 AND window_sec%60=0),
 for_sec INT NOT NULL DEFAULT 60 CHECK(for_sec BETWEEN 0 AND 3600),
 min_requests INT NOT NULL DEFAULT 20 CHECK(min_requests BETWEEN 1 AND 1000000),
 resolve_ratio DOUBLE PRECISION NOT NULL DEFAULT 0.8 CHECK(resolve_ratio>0 AND resolve_ratio<1),
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK((kind='latency' AND percentile IS NOT NULL AND percentile IN (0.5,0.95,0.99)) OR kind='error_rate'),
 CHECK(kind<>'error_rate' OR threshold<=1)
);
CREATE TABLE alert_events (
 id UUID PRIMARY KEY,
 rule_id UUID NOT NULL REFERENCES alert_rules(id),
 state TEXT NOT NULL CHECK(state IN ('pending','firing','resolved')),
 started_at TIMESTAMPTZ NOT NULL,
 firing_at TIMESTAMPTZ,
 recovery_since TIMESTAMPTZ,
 resolved_at TIMESTAMPTZ,
 current_value DOUBLE PRECISION,
 peak_value DOUBLE PRECISION,
 no_data BOOLEAN NOT NULL DEFAULT FALSE,
 resolution TEXT,
 exemplar_trace_ids TEXT[] NOT NULL DEFAULT '{}',
 exemplar_status TEXT NOT NULL DEFAULT 'not_requested',
 evaluated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX one_open_event_per_rule ON alert_events(rule_id) WHERE state<>'resolved';
CREATE INDEX events_started ON alert_events(started_at DESC);
