 CREATE TABLE outbox_events (
                id uuid NOT NULL PRIMARY KEY,
                event jsonb NOT NULL,
                bucket integer NOT NULL,
                partition_key uuid NOT NULL,
                sequence bigint GENERATED ALWAYS AS IDENTITY,
                attempts integer DEFAULT 0 NOT NULL,
                next_attempt_at timestamp with time zone,
                created_at timestamp with time zone DEFAULT timezone('UTC'::text, statement_timestamp()) NOT NULL,
                published_at timestamp with time zone,
                trace_information jsonb NOT NULL
            );
CREATE INDEX outbox_events_bucket_idx ON outbox_events (bucket, sequence) WHERE published_at IS NULL;
CREATE INDEX outbox_events_blocked_idx ON outbox_events (bucket, partition_key) WHERE next_attempt_at IS NOT NULL;
