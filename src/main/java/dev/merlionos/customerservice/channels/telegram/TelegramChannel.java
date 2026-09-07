package dev.merlionos.customerservice.channels.telegram;

import dev.merlionos.customerservice.chat.ChatService;
import dev.merlionos.customerservice.chat.ConversationBusyException;
import dev.merlionos.customerservice.cost.ConversationBudgetExceededException;
import dev.merlionos.customerservice.tenancy.Conversations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One Telegram update into one turn of the same {@link ChatService} the widget uses, and
 * the answer back as a message. A chat is a conversation of the tenant ({@code tg-<chat>-<n>});
 * {@code /new} starts another, {@code /start} greets. The answer is plain text -- Telegram's
 * markdown is its own dialect and a mismatched asterisk would swallow a sentence -- split
 * at paragraphs when it is longer than one message. Every failure is a sentence in the chat,
 * in the language the customer wrote in as far as a first character can tell.
 *
 * <p>The customer is not signed in to any panel here; {@link TelegramIdentity} asks the
 * tenant's panel which of its users the Telegram account is bound to, and the subscription
 * tool reads that account. Unbound, the tool says how to bind.
 */
@Component
public class TelegramChannel {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);

    private final ChatService chat;
    private final Conversations conversations;
    private final TelegramBots bots;
    private final TelegramApi api;
    private final TelegramIdentity identity;

    public TelegramChannel(ChatService chat, Conversations conversations, TelegramBots bots, TelegramApi api, TelegramIdentity identity) {
        this.chat = chat;
        this.conversations = conversations;
        this.bots = bots;
        this.api = api;
        this.identity = identity;
    }

    /** Handles one update; anything that is not a text message in a chat is ignored. */
    @SuppressWarnings("unchecked")
    public void handle(TelegramBot bot, Map<String, Object> update) {
        Object message = update.get("message");
        if (!(message instanceof Map<?, ?> m) || !(m.get("chat") instanceof Map<?, ?> chatObj) || chatObj.get("id") == null) {
            return;
        }
        long chatId = ((Number) chatObj.get("id")).longValue();
        Object textObj = m.get("text");
        if (textObj == null) {
            api.sendMessage(bot.botToken(), chatId, "I can read text only for now.\n我目前只能读文字消息。");
            return;
        }
        String text = String.valueOf(textObj).strip();
        String language = languageOf(text, (Map<String, Object>) m.get("from"));
        try {
            if (text.equals("/start") || text.startsWith("/start ")) {
                api.sendMessage(bot.botToken(), chatId, greeting(language));
                return;
            }
            if (text.equals("/new")) {
                bots.newConversation(bot.tenantId(), chatId);
                api.sendMessage(bot.botToken(), chatId, language.equals("zh") ? "好的，我们重新开始。" : "Starting a new conversation.");
                return;
            }
            if (text.length() > 8000) {
                text = text.substring(0, 8000);
            }
            api.sendTyping(bot.botToken(), chatId);
            String external = bots.conversationOf(bot.tenantId(), chatId);
            String conversation = conversations.resolve(bot.tenantId(), external);
            Map<String, Object> from = (Map<String, Object>) m.get("from");
            Long telegramUser = from == null || from.get("id") == null ? null : ((Number) from.get("id")).longValue();
            String answer = chat.ask(bot.tenantId(), conversation, text, identity.resolve(bot.tenantId(), chatId, telegramUser));
            for (String part : split(answer == null || answer.isBlank() ? (language.equals("zh") ? "抱歉，这次没有得到回复，请再问一次。"
                    : "Sorry, no answer came back this time; please ask again.") : answer)) {
                api.sendMessage(bot.botToken(), chatId, part);
            }
        }
        catch (ConversationBusyException e) {
            api.sendMessage(bot.botToken(), chatId, language.equals("zh") ? "上一条还在回复中，请稍等。" : "I'm still answering your last message; one moment.");
        }
        catch (ConversationBudgetExceededException e) {
            api.sendMessage(bot.botToken(), chatId, language.equals("zh") ? "这个会话已达到上限，发送 /new 开始新会话。"
                    : "This conversation has reached its limit. Send /new to start another.");
        }
        catch (RuntimeException e) {
            log.warn("Telegram turn failed for tenant {} chat {}: {}", bot.tenantId(), chatId, e.toString());
            api.sendMessage(bot.botToken(), chatId, language.equals("zh") ? "客服暂时不可用，请稍后再试。"
                    : "The assistant is unavailable right now. Please try again in a minute.");
        }
    }

    static String greeting(String language) {
        return language.equals("zh")
                ? "你好！可以问我订单、退货、物流、付款或账户的问题。发送 /new 开始新会话。"
                : "Hi! Ask me about orders, returns, shipping, payments or your account. Send /new to start a new conversation.";
    }

    /** Chinese if the text has a CJK character, else from Telegram's language code, else English. */
    static String languageOf(String text, Map<String, Object> from) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                return "zh";
            }
        }
        Object code = from == null ? null : from.get("language_code");
        return code != null && String.valueOf(code).toLowerCase(Locale.ROOT).startsWith("zh") ? "zh" : "en";
    }

    /** Whole paragraphs per message where possible, never more than Telegram's limit. */
    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\n\n")) {
            while (paragraph.length() > TelegramApi.MESSAGE_LIMIT) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                parts.add(paragraph.substring(0, TelegramApi.MESSAGE_LIMIT));
                paragraph = paragraph.substring(TelegramApi.MESSAGE_LIMIT);
            }
            if (current.length() + paragraph.length() + 2 > TelegramApi.MESSAGE_LIMIT) {
                parts.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(paragraph);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }
}
