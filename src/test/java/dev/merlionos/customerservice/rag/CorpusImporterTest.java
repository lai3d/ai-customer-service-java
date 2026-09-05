package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.PostgresTestcontainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fresh database has no corpus, and until the importer has finished the process must say
 * so where Kubernetes looks. Ordered, because the claim is a sequence: DOWN, import, UP,
 * and a second import that changes nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CorpusImporterTest {

    @Autowired TestRestTemplate rest;
    @Autowired CorpusImporter importer;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Order(1)
    @DisplayName("with nothing imported, readiness is DOWN and liveness is UP")
    void notReadyBeforeImport() {
        ResponseEntity<String> readiness = rest.getForEntity("/actuator/health/readiness", String.class);
        ResponseEntity<String> liveness = rest.getForEntity("/actuator/health/liveness", String.class);

        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(readiness.getBody()).contains("\"corpus\"").contains("corpus not yet imported");
        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store", Integer.class)).isZero();
    }

    @Test
    @Order(2)
    @DisplayName("the import writes the corpus and records the version, and readiness follows")
    void importThenReady() {
        assertThat(importer.importIfMissing()).isEqualTo(CorpusImporter.Outcome.IMPORTED);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store", Integer.class)).isEqualTo(36);
        assertThat(jdbc.queryForMap("SELECT * FROM corpus_import"))
                .containsEntry("document_count", 36)
                .containsKey("completed_at");
        ResponseEntity<String> readiness = rest.getForEntity("/actuator/health/readiness", String.class);
        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody()).contains("\"documents\":36");
    }

    @Test
    @Order(3)
    @DisplayName("an already-imported version is skipped, not re-embedded")
    void skipsARecordedVersion() {
        Object completedAt = jdbc.queryForMap("SELECT completed_at FROM corpus_import").get("completed_at");

        assertThat(importer.importIfMissing()).isEqualTo(CorpusImporter.Outcome.ALREADY_PRESENT);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM corpus_import", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT completed_at FROM corpus_import").get("completed_at")).isEqualTo(completedAt);
    }
}
