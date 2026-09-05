package dev.merlionos.customerservice.admin;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

/**
 * What is persisted of a customer conversation: the messages Spring AI's chat memory kept in
 * {@code spring_ai_chat_memory}, which is the chat role's own table. That is the model's
 * context window, windowed and swept as such, not an operational record -- and it is all
 * there is. Retrieval evidence and tool results exist only in the SSE stream of the turn
 * that produced them; the page says so rather than showing an empty panel that implies
 * they were recorded and lost. Persisting them is the proposal's stage 2, not this slice.
 */
public class ConversationTranscripts {

    /** @param type USER, ASSISTANT, SYSTEM or TOOL, as Spring AI stores it */
    public record Message(String type, String content, Instant at) {
    }

    public static final String NOT_PERSISTED = "Retrieval evidence and tool results are not persisted; they were "
            + "shown in the customer's stream at the time and exist nowhere else. Messages are the model's memory "
            + "of the conversation, windowed, not a complete operational record.";

    private final JdbcTemplate jdbc;

    public ConversationTranscripts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Message> messages(String conversationId) {
        return jdbc.query("SELECT type, content, \"timestamp\" FROM spring_ai_chat_memory WHERE conversation_id = ? "
                        + "ORDER BY \"timestamp\"",
                (rs, i) -> new Message(rs.getString("type"), rs.getString("content"),
                        rs.getTimestamp("timestamp").toInstant()),
                conversationId);
    }
}
