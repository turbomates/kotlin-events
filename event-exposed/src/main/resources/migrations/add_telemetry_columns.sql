-- Migration to add telemetry context columns to outbox_events table
-- This allows tracking trace and span IDs for distributed tracing

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS trace_id text,
    ADD COLUMN IF NOT EXISTS span_id text;

-- Optional: Add index if you need to query by trace_id
-- CREATE INDEX IF NOT EXISTS outbox_events_trace_id_idx ON outbox_events (trace_id);
