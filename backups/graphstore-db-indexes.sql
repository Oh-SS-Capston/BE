-- GraphStore DB index backup
-- Apply in a PostgreSQL console after schema creation or DB reset.
-- These indexes support GraphStore ingest/read queries by run_id, relation type,
-- symbol identity, evidence lookup, and link-table joins.

-- artifact
CREATE INDEX IF NOT EXISTS idx_artifact_run_kind_created_at
    ON artifact (run_id, kind, created_at DESC);

-- Artifact upsert by run_id + kind + path is already covered by the JPA unique
-- constraint ux_artifact_run_kind_path, so a separate duplicate index is not added.

-- module
CREATE INDEX IF NOT EXISTS idx_module_run_id
    ON module (run_id);

-- file_index
-- GraphStore ingest loads file_index by run_id and resolves file paths by run_id + path.
-- The JPA unique constraint ux_file_run_path already creates a PostgreSQL btree index
-- on (run_id, path), so a separate duplicate index is intentionally not added.

-- evidence
CREATE INDEX IF NOT EXISTS idx_evidence_run_hash
    ON evidence (run_id, hash);

CREATE INDEX IF NOT EXISTS idx_evidence_run_type_span
    ON evidence (run_id, evidence_type, start_line, end_line);

CREATE INDEX IF NOT EXISTS idx_evidence_run_type_file_span
    ON evidence (run_id, evidence_type, file_id, start_line, end_line);

CREATE INDEX IF NOT EXISTS idx_evidence_run_created_at
    ON evidence (run_id, created_at DESC);

-- symbol
-- GraphStore ingest now loads existing symbols only for the current facts by
-- run_id + qualified_name. The JPA unique constraint ux_symbol_run_qualified
-- already creates a PostgreSQL btree index on (run_id, qualified_name), so a
-- separate duplicate index is intentionally not added.

CREATE INDEX IF NOT EXISTS idx_symbol_run_kind
    ON symbol (run_id, symbol_kind);

CREATE INDEX IF NOT EXISTS idx_symbol_run_kind_symbol_id
    ON symbol (run_id, symbol_kind, symbol_id);

CREATE INDEX IF NOT EXISTS idx_symbol_run_updated_at
    ON symbol (run_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_symbol_owner_symbol_id
    ON symbol (owner_symbol_id);

CREATE INDEX IF NOT EXISTS idx_symbol_source_file_id
    ON symbol (source_file_id);

-- edge
CREATE INDEX IF NOT EXISTS idx_edge_run_id
    ON edge (run_id);

CREATE INDEX IF NOT EXISTS idx_edge_run_type
    ON edge (run_id, edge_type);

CREATE INDEX IF NOT EXISTS idx_edge_run_from_type_to
    ON edge (run_id, from_symbol_id, edge_type, to_symbol_id);

CREATE INDEX IF NOT EXISTS idx_edge_run_from_type_to_null
    ON edge (run_id, from_symbol_id, edge_type)
    WHERE to_symbol_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_edge_run_type_to_not_null
    ON edge (run_id, edge_type, to_symbol_id)
    WHERE to_symbol_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_edge_run_updated_at
    ON edge (run_id, updated_at DESC);

-- observation
CREATE INDEX IF NOT EXISTS idx_observation_run_id
    ON observation (run_id);

CREATE INDEX IF NOT EXISTS idx_observation_run_kind
    ON observation (run_id, kind);

CREATE INDEX IF NOT EXISTS idx_observation_run_site_symbol
    ON observation (run_id, site_symbol);

-- link tables
CREATE INDEX IF NOT EXISTS idx_symbol_evidence_evidence_id
    ON symbol_evidence (evidence_id);

CREATE INDEX IF NOT EXISTS idx_edge_evidence_evidence_id
    ON edge_evidence (evidence_id);

CREATE INDEX IF NOT EXISTS idx_observation_evidence_evidence_id
    ON observation_evidence (evidence_id);
