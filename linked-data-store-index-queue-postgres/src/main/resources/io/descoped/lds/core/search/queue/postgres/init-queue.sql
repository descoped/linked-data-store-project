-- The updates the search index has not taken. A row is added by the saga of a write and removed when the index has
-- taken it. available_at: when it can be taken (later while it is taken, or put off); the clock is the database's,
-- so that several LDS share one. version: the version as the index names it, with its zone.
CREATE TABLE IF NOT EXISTS lds_index_queue (
    sequence     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    namespace    text        NOT NULL,
    entity       text        NOT NULL,
    id           text        NOT NULL,
    version      text        NOT NULL,
    operation    text        NOT NULL CHECK (operation IN ('CREATE_OR_OVERWRITE', 'DELETE')),
    added        timestamptz NOT NULL DEFAULT now(),
    available_at timestamptz NOT NULL DEFAULT now(),
    attempts     integer     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS lds_index_queue_available ON lds_index_queue (available_at, sequence);
