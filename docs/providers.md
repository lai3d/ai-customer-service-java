# Chat providers


The provider is configuration, not code. Everything around the model — the advisor chain,
conversation memory, retrieval, both tools, SSE streaming, the metrics and spans — is written
against Spring AI's `ChatModel` interface, so nothing in `src/main/java` changes.

```bash
CHAT_PROVIDER=anthropic                      # default
CHAT_PROVIDER=openai       OPENAI_API_KEY=…
CHAT_PROVIDER=google-genai GEMINI_API_KEY=…

# Grok: xAI has no Spring AI starter, but its API is OpenAI-compatible
CHAT_PROVIDER=openai OPENAI_API_KEY=<xAI key> \
  OPENAI_BASE_URL=https://api.x.ai OPENAI_CHAT_MODEL=<grok model>
```

`ChatProviderSwitchingTest` boots the real context under each of the three providers and checks
that exactly one chat model is built and the rest of the wiring is unchanged — because
"provider-agnostic" is the kind of claim a README makes and nobody verifies.

### Two things that bite when you add the second starter

**`spring.ai.model.chat` stops being optional.** Every provider's auto-configuration is
`@ConditionalOnProperty(… matchIfMissing = true)`, so with the property unset they *all* build a
`ChatModel`, and `ChatClientAutoConfiguration` injects `ChatModel` directly with no
`@ConditionalOnSingleCandidate`. The result is `NoUniqueBeanDefinitionException` at startup —
loud and immediate, which is the good version of this problem.

**A provider starter brings every model type that provider supports.** The OpenAI starter adds
speech, transcription, image, and moderation auto-configurations, each of which builds by
default and throws for want of an API key. `spring.ai.model.audio.speech: none` and its
siblings are not tidying — without them the application does not start.

### What this does not claim

Nothing here calls three APIs and compares them. The abstraction covers the request shape;
tool-call reliability, streaming chunk granularity, and how each provider treats a system prompt
differ in ways only live traffic reveals. A cross-provider contract test would need three sets
of credentials and would cost money on every run, so it does not belong in CI. Grok in
particular rides on a compatibility layer maintained by xAI, not on first-class support.

Claude remains the default. The sampling-parameter workaround in
[`AnthropicSamplingParameterStripper`](../src/main/java/dev/merlionos/customerservice/config/AnthropicSamplingParameterStripper.java)
is the only provider-specific code, and it stays inert under the others.

---

[← Back to the README](../README.md)
