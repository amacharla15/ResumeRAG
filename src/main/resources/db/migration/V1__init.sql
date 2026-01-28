CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS resume_profile (
                                              id BIGSERIAL PRIMARY KEY,
                                              profile_json JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS resume_chunks (
                                             id BIGSERIAL PRIMARY KEY,
                                             section TEXT NOT NULL,
                                             content TEXT NOT NULL,
                                             metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                                             tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', content)) STORED
    );

CREATE INDEX IF NOT EXISTS idx_resume_chunks_tsv ON resume_chunks USING GIN (tsv);
CREATE INDEX IF NOT EXISTS idx_resume_chunks_trgm ON resume_chunks USING GIN (content gin_trgm_ops);
