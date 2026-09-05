-- Staff accounts for the operations admin under /admin. Staff, not customers: the public chat
-- endpoints know nothing of this table, and nothing here is a customer identity.
--
-- The password is a bcrypt hash carrying Spring Security's {id} prefix, so the algorithm can
-- change later without a rewrite of every row. Two roles, checked here as well as in code:
-- a row with a third value is a bug wherever it came from.
CREATE TABLE staff_account (
    username      varchar(64)  PRIMARY KEY,
    password_hash text         NOT NULL,
    role          varchar(16)  NOT NULL CHECK (role IN ('admin', 'support')),
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL,
    created_by    varchar(64)
);

-- Staff sessions, in Postgres rather than in a JVM: the chat role runs as two replicas behind
-- one Service, and a login held in one process's memory is the per-replica state ADR 001
-- removed. This is Spring Session JDBC's own schema (schema-postgresql.sql in the
-- spring-session-jdbc jar), copied verbatim so that Flyway owns it and Spring Session's
-- initialiser stays off, for the same reason Spring AI's initialisers do (see V1).
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
