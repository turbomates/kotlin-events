 CREATE TABLE event_sourcing (
                id uuid NOT NULL PRIMARY KEY,
                root_id text NOT NULL,
                data jsonb NOT NULL,
                created_at timestamp with time zone DEFAULT timezone('UTC'::text, statement_timestamp()) NOT NULL
            );
CREATE INDEX event_sourcing_root_id_idx ON event_sourcing (root_id);
