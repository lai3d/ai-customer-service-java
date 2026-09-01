package dev.merlionos.customerservice.chat;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Publishes what retrieval found, the moment it finds it.
 *
 * <p>The obvious place to read {@code RETRIEVED_DOCUMENTS} is the streamed response, since it
 * carries the advisor context -- and that is where this started. It was wrong twice over. The
 * documents only surfaced when the first token arrived, so a client could not show what was
 * being consulted while the model was still thinking; and when the model call failed, no
 * response was ever emitted and the retrieval was reported as never having happened. A support
 * agent debugging a bad answer needs to see the passages most urgently in exactly that case.
 *
 * <p>Sitting after {@link QuestionAnswerAdvisor} in the chain, this reads the documents it just
 * put in the request context and publishes them before the model is called at all.
 */
@Component
public class RetrievalReportingAdvisor implements BaseAdvisor {

    private static final String METADATA_ENTRY_ID = "entry_id";
    private static final String METADATA_LANGUAGE = "language";

    private final TurnEventBus turnEventBus;

    RetrievalReportingAdvisor(TurnEventBus turnEventBus) {
        this.turnEventBus = turnEventBus;
    }

    /**
     * After {@code QuestionAnswerAdvisor}, whose order is 0. Lower runs first in {@code before},
     * so anything greater than 0 sees the documents it retrieved.
     */
    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        Map<String, Object> context = request.context();

        Object conversationId = context.get(ChatMemory.CONVERSATION_ID);
        Object documents = context.get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);

        if (conversationId instanceof String id && documents instanceof List<?> list) {
            List<TurnEvent.Passage> passages = list.stream()
                    .filter(Document.class::isInstance)
                    .map(Document.class::cast)
                    .map(RetrievalReportingAdvisor::toPassage)
                    .toList();
            turnEventBus.publish(id, new TurnEvent.Retrieval(passages));
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        return response;
    }

    private static TurnEvent.Passage toPassage(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new TurnEvent.Passage(
                String.valueOf(metadata.get(METADATA_ENTRY_ID)),
                String.valueOf(metadata.get(METADATA_LANGUAGE)),
                document.getScore() == null ? 0d : document.getScore());
    }
}
