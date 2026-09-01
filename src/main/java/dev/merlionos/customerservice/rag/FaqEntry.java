package dev.merlionos.customerservice.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One question-and-answer pair from the FAQ corpus. */
@JsonIgnoreProperties(ignoreUnknown = true)
record FaqEntry(String id, String category, String question, String answer) {
}
