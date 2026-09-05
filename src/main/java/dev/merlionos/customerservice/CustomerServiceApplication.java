package dev.merlionos.customerservice;

import dev.merlionos.customerservice.config.ChatRoleConfiguration;
import dev.merlionos.customerservice.internal.InternalProperties;
import dev.merlionos.customerservice.observability.ObservabilityProperties;
import dev.merlionos.customerservice.rag.KnowledgeRoleConfiguration;
import dev.merlionos.customerservice.ticket.TicketRoleConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * Not {@code @SpringBootApplication}: that would scan the whole package tree and every role
 * would be present in every process. Each role is a configuration class that scans its own
 * packages and is gated on {@code app.target}; what is common to every role -- observability,
 * the internal-endpoint token filter and the target mechanism itself -- is scanned here.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ConfigurationPropertiesScan
@ComponentScan(basePackageClasses = {ObservabilityProperties.class, InternalProperties.class})
@Import({ChatRoleConfiguration.class, KnowledgeRoleConfiguration.class, TicketRoleConfiguration.class})
public class CustomerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerServiceApplication.class, args);
    }
}
