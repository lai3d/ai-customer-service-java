package dev.merlionos.customerservice.tenancy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The mapping between what a client calls a conversation and what every table keys on.
 *
 * <p>Before tenants, the client's id was the key, looked up by value alone -- so with two
 * customers behind one deployment, a guessed id would have read and appended to another
 * customer's history. Now the client's id is scoped to its tenant here, and the id the
 * memory, the lease, the budget, the turns and the tickets key on is one the client never
 * chose. Existing conversations kept their id as both, being the default tenant's and unique
 * already; the migration wrote their rows.
 */
@Component
public class Conversations {

    private final JdbcTemplate jdbc;

    public Conversations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The internal id for this tenant's conversation, created on first use.
     *
     * <p>Three statements on purpose, and they must stay three. Two first turns racing on one
     * client id both miss the read, both insert; the loser's {@code ON CONFLICT DO NOTHING}
     * waits for the winner's transaction and then does nothing, and the loser's <em>second</em>
     * read, a fresh snapshot taken after the winner committed, sees the winner's row. Folded
     * into one statement (a CTE that inserts and reads in the same snapshot) the loser reads
     * nothing and the first turn of a conversation fails; the Go implementation shipped
     * exactly that and saw it in four runs out of five at twelve-way concurrency (issue #74).
     * {@code ConversationsTest.firstTurnRace} is the test that fails if this is tidied up.
     */
    public String resolve(String tenantId, String externalId) {
        String external = externalId == null || externalId.isBlank() ? UUID.randomUUID().toString() : externalId;
        Optional<String> existing = find(tenantId, external);
        if (existing.isPresent()) {
            return existing.get();
        }
        // A new conversation whose client id is the UUID just made keeps it as its internal
        // id too, which is what the pre-tenancy rows look like and what the demo page sees.
        String internal = externalId == null || externalId.isBlank() ? external : UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO conversation (id, tenant_id, external_id, created_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, external_id) DO NOTHING
                """, internal, tenantId, external, Timestamp.from(Instant.now()));
        // Two first turns racing: the loser's insert did nothing and the winner's id is the one.
        return find(tenantId, external).orElseThrow();
    }

    /**
     * A conversation an evaluation run owns, marked so nothing counts it as a customer's:
     * not deflection, not the overview, not what staff read. Its id is minted here.
     */
    public String createEvaluation(String tenantId, String externalId) {
        String internal = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO conversation (id, tenant_id, external_id, created_at, kind) VALUES (?, ?, ?, ?, 'evaluation')
                ON CONFLICT (tenant_id, external_id) DO NOTHING
                """, internal, tenantId, externalId, Timestamp.from(Instant.now()));
        return find(tenantId, externalId).orElseThrow();
    }

    public Optional<String> find(String tenantId, String externalId) {
        return jdbc.query("SELECT id FROM conversation WHERE tenant_id = ? AND external_id = ?",
                (rs, i) -> rs.getString(1), tenantId, externalId).stream().findFirst();
    }

    /** The client-facing id of an internal one, for echoing back and for the admin's views. */
    public Optional<String> externalIdOf(String internalId) {
        return jdbc.query("SELECT external_id FROM conversation WHERE id = ?",
                (rs, i) -> rs.getString(1), internalId).stream().findFirst();
    }
}
