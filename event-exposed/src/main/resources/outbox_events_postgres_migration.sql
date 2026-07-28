-- Migrates outbox_events from the released schema
-- (id, event, created_at, published_at, trace_information)
-- to the bucketed retry/ordering schema: bucket, partition_key, sequence, attempts,
-- next_attempt_at, and the two partial indexes.
--
-- Replace 16 with the bucketCount the application is built with.
--
-- Run it on a drained outbox. The backfills only approximate what the new build writes: the
-- real partition key of an old row was never persisted, so partition_key = id makes every old
-- row a stream of its own, and the identity column numbers the existing rows in arbitrary
-- order — a backlog left in the table may replay out of order once. Adding the identity column
-- rewrites the table under an exclusive lock, which is instant when the outbox is empty.
BEGIN;

ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS bucket integer;
UPDATE outbox_events SET bucket = mod(abs(hashtext(id::text)), 16) WHERE bucket IS NULL;
ALTER TABLE outbox_events ALTER COLUMN bucket SET NOT NULL;

ALTER TABLE outbox_events ADD COLUMN partition_key uuid;
UPDATE outbox_events SET partition_key = id WHERE partition_key IS NULL;
ALTER TABLE outbox_events ALTER COLUMN partition_key SET NOT NULL;

ALTER TABLE outbox_events ADD COLUMN sequence bigint GENERATED ALWAYS AS IDENTITY;
ALTER TABLE outbox_events ADD COLUMN attempts integer NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD COLUMN next_attempt_at timestamp with time zone;

DROP INDEX IF EXISTS events_publisshed_idx;
DROP INDEX IF EXISTS outbox_events_bucket_idx;
CREATE INDEX outbox_events_bucket_idx ON outbox_events (bucket, sequence) WHERE published_at IS NULL;
CREATE INDEX outbox_events_blocked_idx ON outbox_events (bucket, partition_key) WHERE next_attempt_at IS NOT NULL;

COMMIT;
