 CREATE TABLE outbox_events (
                id uuid NOT NULL PRIMARY KEY,
                event jsonb NOT NULL,
                bucket integer NOT NULL,
                created_at timestamp with time zone DEFAULT timezone('UTC'::text, statement_timestamp()) NOT NULL,
                published_at timestamp with time zone,
                trace_information jsonb NOT NULL
            );
CREATE INDEX events_publisshed_idx ON outbox_events (published_at);
CREATE INDEX outbox_events_bucket_idx ON outbox_events (bucket, created_at, id) WHERE published_at IS NULL;
