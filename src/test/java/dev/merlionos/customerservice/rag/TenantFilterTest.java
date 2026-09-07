package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.TenantFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;

import static org.assertj.core.api.Assertions.assertThat;

/** The tenant clause as the advisor sends it and as the store reads it back. */
class TenantFilterTest {

    @Test
    @DisplayName("what the chat side puts in the advisor context is what the store reads out")
    void roundTrip() {
        Filter.Expression parsed = new FilterExpressionTextParser().parse(TenantFilter.text("acme-1"));

        assertThat(TenantFilter.tenantOf(parsed)).contains("acme-1");
        assertThat(TenantFilter.tenantOf(TenantFilter.expression("beta"))).contains("beta");
        assertThat(TenantFilter.without(parsed)).as("nothing else was asked").isNull();
    }

    @Test
    @DisplayName("a tenant clause combined with another filter is found, and the other filter is what remains")
    void combined() {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression other = b.eq("language", "zh").build();
        Filter.Expression combined = b.and(b.eq(TenantFilter.KEY, "acme"), b.eq("language", "zh")).build();

        assertThat(TenantFilter.tenantOf(combined)).contains("acme");
        assertThat(TenantFilter.without(combined)).isEqualTo(other);
        assertThat(TenantFilter.tenantOf(other)).as("no tenant clause, no tenant").isEmpty();
        assertThat(TenantFilter.without(other)).isSameAs(other);
        assertThat(TenantFilter.tenantOf(null)).isEmpty();
    }
}
