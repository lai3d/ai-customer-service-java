package dev.merlionos.customerservice.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.merlionos.customerservice.rag.api.ImportMode;
import dev.merlionos.customerservice.rag.api.RagProperties;
import dev.merlionos.customerservice.tenancy.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * The bundled golden set for the default tenant, seeded once into an empty {@code golden_case}
 * table, the way the bundled corpus is adopted: it belongs to the bundled FAQ and to nobody's
 * customers. A tenant's own set comes from its pilot's real questions, through the admin.
 */
@Component
public class GoldenSeeder {

    private static final Logger log = LoggerFactory.getLogger(GoldenSeeder.class);
    public static final String BUNDLED_ACTOR = "bundled";

    record Bundled(String notice, List<Case> cases) {
    }

    record Case(String question, String language, List<String> expectedEntryIds, List<String> mustContain, List<String> anyOf,
                List<String> mustNotContain, String expectTool, String forbidTool, boolean expectRefusal, String note) {
    }

    private final GoldenCases cases;
    private final RagProperties rag;

    public GoldenSeeder(GoldenCases cases, RagProperties rag) {
        this.cases = cases;
        this.rag = rag;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(200)
    public void seed() {
        // An import-once process is a job that exits from its own ready listener, off the
        // event thread; anything that runs after it races the closing pool and loses.
        if (rag.importMode() == ImportMode.ONCE) {
            return;
        }
        seedBundledIfEmpty();
    }

    /** @return how many cases were seeded; zero when the table already had the default tenant's */
    public int seedBundledIfEmpty() {
        if (cases.count(Tenant.DEFAULT) > 0) {
            return 0;
        }
        try (InputStream in = new ClassPathResource("golden/golden.json").getInputStream()) {
            Bundled bundled = new ObjectMapper().readValue(in, Bundled.class);
            for (Case c : bundled.cases()) {
                cases.create(new GoldenCase(null, Tenant.DEFAULT, c.question(), c.language(), c.expectedEntryIds(), c.mustContain(),
                        c.anyOf(), c.mustNotContain(), c.expectTool(), c.forbidTool(), c.expectRefusal(), true, c.note(), null, null), BUNDLED_ACTOR);
            }
            log.info("Seeded the bundled golden set: {} cases for the default tenant", bundled.cases().size());
            return bundled.cases().size();
        }
        catch (java.io.IOException e) {
            throw new IllegalStateException("The bundled golden set could not be read", e);
        }
    }
}
