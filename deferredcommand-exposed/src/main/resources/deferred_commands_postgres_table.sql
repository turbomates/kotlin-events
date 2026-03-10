CREATE TABLE deferred_commands (
    id uuid NOT NULL PRIMARY KEY,
    command jsonb NOT NULL,
    execute_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT timezone('UTC'::text, statement_timestamp()) NOT NULL
);
CREATE INDEX deferred_commands_execute_at_idx ON deferred_commands (execute_at);
