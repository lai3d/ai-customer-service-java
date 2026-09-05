package dev.merlionos.customerservice.config;

import dev.merlionos.customerservice.chat.ChatService;
import dev.merlionos.customerservice.cost.ConversationBudget;
import dev.merlionos.customerservice.orders.OrderLookup;
import dev.merlionos.customerservice.provider.XaiChatConfig;
import dev.merlionos.customerservice.target.ConditionalOnTarget;
import dev.merlionos.customerservice.target.DeploymentTarget;
import dev.merlionos.customerservice.tools.OrderTools;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * The chat role: the public API and SSE, the advisor chain, the tool adapters, the order
 * mock, memory, budget and the provider set-up. Present in {@code all} and {@code chat}
 * processes.
 *
 * <p>Packages are listed by a class in each rather than by name so a rename is a compile
 * error instead of a silently empty scan.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnTarget(DeploymentTarget.CHAT)
@ComponentScan(basePackageClasses = {
        ChatService.class,          // chat
        ChatRoleConfiguration.class, // config
        ConversationBudget.class,   // cost
        OrderLookup.class,          // orders
        XaiChatConfig.class,        // provider
        OrderTools.class            // tools
})
public class ChatRoleConfiguration {
}
