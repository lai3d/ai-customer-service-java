package dev.merlionos.customerservice.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One question-and-answer pair, in every language it has been written in.
 *
 * <p>Each language becomes its own document. Translating an entry and embedding only the
 * English text would leave the multilingual model doing all the cross-lingual work at query
 * time; embedding both gives a Chinese question a Chinese passage to match, which is a shorter
 * distance to travel, while cross-lingual matching still works as a fallback.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record FaqEntry(String id, String category, List<LocalizedFaq> localized) {
}
