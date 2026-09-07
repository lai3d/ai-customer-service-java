package dev.merlionos.customerservice.channels.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure parts: splitting an answer into Telegram messages, and the language a reply is in. */
class TelegramChannelTest {

    @Test
    @DisplayName("an answer is split at paragraphs, never over the limit, and a single huge paragraph is cut")
    void split() {
        assertThat(TelegramChannel.split("short")).containsExactly("short");
        String p1 = "a".repeat(3000);
        String p2 = "b".repeat(3000);
        assertThat(TelegramChannel.split(p1 + "\n\n" + p2)).containsExactly(p1, p2);
        assertThat(TelegramChannel.split("x\n\ny")).containsExactly("x\n\ny");
        List<String> cut = TelegramChannel.split("c".repeat(9000));
        assertThat(cut).hasSize(3);
        assertThat(cut).allSatisfy(part -> assertThat(part.length()).isLessThanOrEqualTo(TelegramApi.MESSAGE_LIMIT));
    }

    @Test
    @DisplayName("Chinese text, or a Chinese Telegram locale, gets Chinese sentences; otherwise English")
    void language() {
        assertThat(TelegramChannel.languageOf("退货有时间限制吗", Map.of("language_code", "en"))).isEqualTo("zh");
        assertThat(TelegramChannel.languageOf("/start", Map.of("language_code", "zh-hans"))).isEqualTo("zh");
        assertThat(TelegramChannel.languageOf("/start", Map.of("language_code", "en"))).isEqualTo("en");
        assertThat(TelegramChannel.languageOf("hello", null)).isEqualTo("en");
        assertThat(TelegramChannel.greeting("zh")).contains("/new");
    }
}
