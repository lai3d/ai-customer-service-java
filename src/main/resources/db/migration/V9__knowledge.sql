-- Managed knowledge: what the operations admin edits and publishes, next to the bundled
-- corpus the application ships with. The bundled corpus (src/main/resources/faq/faq.json)
-- is untouched by any of this and stays the fixture the Java and Go retrieval numbers are
-- compared on; at startup it is adopted as the first version here, once, without being
-- re-embedded.
--
-- An entry is a question-and-answer pair with a stable id; a revision is its text in one
-- language at one point in time; a version is the published document set a publication
-- built, each with its own corpus_version in the vector store; and exactly one version is
-- active. Retrieval filters by the active version at query time, so a publication that
-- fails or is never activated changes nothing a customer sees, and a switch is one row.

CREATE TABLE knowledge_entry (
    entry_id    varchar(64) PRIMARY KEY,
    category    varchar(32) NOT NULL,
    retired     boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL,
    created_by  varchar(64) NOT NULL
);

CREATE TABLE knowledge_revision (
    id          bigserial   PRIMARY KEY,
    entry_id    varchar(64) NOT NULL REFERENCES knowledge_entry (entry_id),
    language    varchar(8)  NOT NULL,
    question    text        NOT NULL,
    answer      text        NOT NULL,
    -- draft: not in any publication yet; published: in the latest publication;
    -- superseded: replaced by a later revision in a later publication.
    state       varchar(12) NOT NULL CHECK (state IN ('draft', 'published', 'superseded')),
    created_at  timestamptz NOT NULL,
    created_by  varchar(64) NOT NULL,
    note        text
);

-- One draft and one published revision per entry and language at a time.
CREATE UNIQUE INDEX knowledge_revision_one_draft ON knowledge_revision (entry_id, language) WHERE state = 'draft';
CREATE UNIQUE INDEX knowledge_revision_one_published ON knowledge_revision (entry_id, language) WHERE state = 'published';
CREATE INDEX knowledge_revision_entry ON knowledge_revision (entry_id, language, id);

CREATE TABLE knowledge_version (
    version         text        PRIMARY KEY,
    -- building: documents being embedded; ready: complete, retained, can be activated;
    -- active: what retrieval reads (exactly one); failed: the build did not complete and
    -- nothing was activated; retired: documents deleted, kept as history.
    state           varchar(12) NOT NULL CHECK (state IN ('building', 'ready', 'active', 'failed', 'retired')),
    document_count  integer,
    created_at      timestamptz NOT NULL,
    created_by      varchar(64) NOT NULL,
    activated_at    timestamptz,
    note            text,
    error           text
);

-- The revisions a version was built from: its immutable snapshot.
CREATE TABLE knowledge_version_document (
    version     text   NOT NULL REFERENCES knowledge_version (version),
    revision_id bigint NOT NULL REFERENCES knowledge_revision (id),
    PRIMARY KEY (version, revision_id)
);

-- The one row retrieval reads. Switched with an expected-version check, so an older
-- publication finishing late cannot overwrite a newer one.
CREATE TABLE knowledge_active (
    id          integer     PRIMARY KEY CHECK (id = 1),
    version     text        REFERENCES knowledge_version (version),
    switched_at timestamptz,
    switched_by varchar(64)
);

INSERT INTO knowledge_active (id, version) VALUES (1, NULL);
