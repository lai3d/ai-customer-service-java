package dev.merlionos.customerservice.provider;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Registers xAI as a chat provider in its own right, built on the OpenAI client.
 *
 * <p>Spring AI ships no xAI starter. The shortcut is to select {@code openai} and put an xAI
 * key in {@code OPENAI_API_KEY} with a base-URL override — it works, and it lies: the
 * configuration then says OpenAI everywhere while talking to xAI, the two cannot be configured
 * side by side, and whoever reads the deployment later has to know the trick.
 *
 * <p>The opposite mistake is writing an {@code XaiChatModel} from scratch. xAI speaks OpenAI's
 * wire protocol, so that reimplements streaming, tool calling, retry and observation for no
 * gain and a permanent maintenance cost.
 *
 * <p>What is actually true is that xAI is a <em>separate provider</em> reached over a
 * <em>shared protocol</em>. So this reuses {@link OpenAiChatModel} — including the same
 * {@link ToolCallingManager}, {@link RetryTemplate} and {@link ObservationRegistry} that Spring
 * AI's own auto-configuration would give it, so tool calling, retry policy, metrics and spans
 * behave identically — while owning its own credentials, base URL and model under
 * {@code spring.ai.xai}. Selecting it is {@code spring.ai.model.chat=xai}, alongside the
 * providers Spring AI ships.
 *
 * <p>One thing this does not paper over: xAI's compatibility is xAI's to maintain. If they
 * diverge from OpenAI's protocol, this breaks, and the fact that it is the OpenAI client is
 * stated here rather than hidden behind the name.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "xai")
public class XaiChatConfig {

    @Bean
    OpenAiApi xaiApi(XaiProperties properties,
                     ObjectProvider<RestClient.Builder> restClientBuilder,
                     ObjectProvider<WebClient.Builder> webClientBuilder) {

        Assert.hasText(properties.apiKey(),
                "spring.ai.xai.api-key must be set when spring.ai.model.chat=xai");

        return OpenAiApi.builder()
                .baseUrl(properties.baseUrl())
                .completionsPath(properties.completionsPath())
                .apiKey(properties.apiKey())
                .restClientBuilder(restClientBuilder.getIfAvailable(RestClient::builder))
                .webClientBuilder(webClientBuilder.getIfAvailable(WebClient::builder))
                .build();
    }

    @Bean
    OpenAiChatModel xaiChatModel(OpenAiApi xaiApi, XaiProperties properties,
                                 ToolCallingManager toolCallingManager,
                                 ObjectProvider<RetryTemplate> retryTemplate,
                                 ObjectProvider<ObservationRegistry> observationRegistry) {

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.model())
                // Usage is omitted from a streamed response unless asked for. Without this the
                // conversation budget never triggers and the cost meters stay at zero.
                .streamUsage(true)
                // No temperature on purpose: current frontier models reject the value Spring AI
                // would otherwise seed. Nothing seeds one here, so nothing has to strip it.
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(xaiApi)
                .defaultOptions(options)
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate.getIfAvailable(RetryTemplate::new))
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }
}
