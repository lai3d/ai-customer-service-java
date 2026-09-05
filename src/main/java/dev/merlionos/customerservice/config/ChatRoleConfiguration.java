package dev.merlionos.customerservice.config;

import dev.merlionos.customerservice.admin.AdminSecurityConfiguration;
import dev.merlionos.customerservice.chat.ChatService;
import dev.merlionos.customerservice.clients.ServicesProperties;
import dev.merlionos.customerservice.cost.ConversationBudget;
import dev.merlionos.customerservice.orders.OrderLookup;
import dev.merlionos.customerservice.provider.XaiChatConfig;
import dev.merlionos.customerservice.target.ConditionalOnTarget;
import dev.merlionos.customerservice.target.DeploymentTarget;
import dev.merlionos.customerservice.tools.OrderTools;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The chat role: the public API and SSE, the advisor chain, the tool adapters, the order
 * mock, memory, budget, the provider set-up and the operations admin with its staff login.
 * Present in {@code all} and {@code chat} processes.
 *
 * <p>Packages are listed by a class in each rather than by name so a rename is a compile
 * error instead of a silently empty scan.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnTarget(DeploymentTarget.CHAT)
@EnableScheduling // the budget sweep
@ComponentScan(basePackageClasses = {
        AdminSecurityConfiguration.class, // admin: the operations admin and its staff login
        ChatService.class,          // chat
        ServicesProperties.class,   // clients: the HTTP adapters, present only when exactly chat
        ChatRoleConfiguration.class, // config
        ConversationBudget.class,   // cost
        OrderLookup.class,          // orders
        XaiChatConfig.class,        // provider
        OrderTools.class            // tools
})
public class ChatRoleConfiguration {
}
