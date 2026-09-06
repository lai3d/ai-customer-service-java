-- Which knowledge version a turn was grounded on. The passages carried it in their metadata
-- from the first version on; the row did not. With publications and rollbacks, a turn's
-- retrieval rows say what was found only together with the version it was found in, so
-- a flagged answer can be read against the text that produced it (knowledge_version,
-- knowledge_version_document). Null on rows written before this column existed.
ALTER TABLE turn_retrieval ADD COLUMN corpus_version text;
