package dev.merlionos.customerservice.ticket;

import dev.merlionos.customerservice.target.ConditionalOnTarget;
import dev.merlionos.customerservice.target.DeploymentTarget;
import dev.merlionos.customerservice.ticket.api.TicketOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The ticket role: present in {@code all} and {@code ticket} processes, absent from the
 * others, where the chat side will reach it over HTTP instead.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnTarget(DeploymentTarget.TICKET)
public class TicketRoleConfiguration {

    @Bean
    TicketOperations ticketOperations() {
        return new LocalTicketOperations();
    }
}
