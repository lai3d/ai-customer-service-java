package dev.merlionos.customerservice.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One FAQ entry in one language.
 *
 * @param language BCP 47 language subtag, e.g. {@code en} or {@code zh}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record LocalizedFaq(String language, String question, String answer) {
}
