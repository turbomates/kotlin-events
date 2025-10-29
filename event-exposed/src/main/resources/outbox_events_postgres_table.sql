 CREATE TABLE outbox_events (
                id uuid NOT NULL PRIMARY KEY,
                event jsonb NOT NULL,
                created_at timestamp with time zone DEFAULT timezone('UTC'::text, statement_timestamp()) NOT NULL,
                published_at timestamp with time zone,
                trace_id text,
                span_id text
            );
CREATE INDEX events_publisshed_idx ON outbox_events (published_at);