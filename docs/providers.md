# Chat providers


The provider is configuration, not code. Everything around the model — the advisor chain,
conversation memory, retrieval, both tools, SSE streaming, the metrics and spans — is written
against Spring AI's `ChatModel` interface, so nothing in `src/main/java` changes.

```bash
CHAT_PROVIDER=anthropic                      # default
CHAT_PROVIDER=openai       OPENAI_API_KEY=…
CHAT_PROVIDER=google-genai GEMINI_API_KEY=…

CHAT_PROVIDER=xai          XAI_API_KEY=…
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

### What only a live call found

Switching provider is configuration, but "the context starts" and "it works" are different
claims. Running the same conversation against OpenAI found two defects that no amount of
reading would have.

**Every provider's seeded `temperature` is rejected by its own current model.** Spring AI's
chat properties set one in a field initialiser for all three — Anthropic 0.8, OpenAI 0.7,
Google 0.7 — and the binder cannot null it back out. Claude Opus 5 returns HTTP 400 for any
sampling parameter; GPT-5 returns *"Unsupported value: 'temperature' does not support 0.7 with
this model. Only the default (1) value is supported."* This codebase had a workaround for
Anthropic and a comment asserting the others did not need one. The comment was wrong.
[`SeededSamplingParameterStripper`](../src/main/java/dev/merlionos/customerservice/config/SeededSamplingParameterStripper.java)
now covers all three, and removes only the *seeded* value — a temperature someone sets
deliberately is a choice about a model that accepts it, and it survives.

**OpenAI reports no token usage in a streamed response unless asked.** Every streamed turn came
back with nulls, so the conversation budget would never trigger and `chat.cost.usd` would stay
at zero while real money was spent. `spring.ai.openai.chat.options.stream-usage: true` fixes it.
Anthropic sends usage without being asked, which is exactly why this went unnoticed.

**A third trap, still live:** metrics are tagged with the model the provider *reports*, not the
one requested. Asking for `gpt-5` produces `model="gpt-5-2025-08-07"`, so a price keyed on
`gpt-5` never matches and the cost silently stays zero while tokens keep counting.

All four are verified live: each answers a question, calls a tool, and reports usage that
reaches the budget and the spans. Grok was the provider whose usage reporting exposed the
accounting bug described in [Cost and failure](reliability.md#a-turn-is-not-a-model-call).

### xAI is a provider, not a base-URL trick

Spring AI ships no xAI starter, and there are three ways to respond to that. Two are wrong.

The shortcut is to select `openai`, put the xAI key in `OPENAI_API_KEY` and override the base
URL. It works, and it lies: the configuration says OpenAI everywhere while talking to xAI, the
two cannot be configured side by side, and whoever reads the deployment later has to know the
trick. The opposite mistake is writing an `XaiChatModel` from scratch — xAI speaks OpenAI's wire
protocol, so that reimplements streaming, tool calling, retry and observation for no gain and a
permanent maintenance cost.

What is actually true is that xAI is a **separate provider** reached over a **shared protocol**,
and those are different things.
[`XaiChatConfig`](../src/main/java/dev/merlionos/customerservice/provider/XaiChatConfig.java)
reuses `OpenAiChatModel` — with the same `ToolCallingManager`, `RetryTemplate` and
`ObservationRegistry` Spring AI's own auto-configuration would supply, so tool calling, retry
and observability behave identically — while owning its credentials, base URL and model under
`spring.ai.xai`. `CHAT_PROVIDER=xai` sits alongside the providers Spring AI ships, and OpenAI's
own slot stays free.

The one thing this does not paper over: xAI's compatibility is xAI's to maintain. If they
diverge from OpenAI's protocol this breaks, and the class says so rather than hiding behind its
name.

### Choosing a Gemini model took four attempts

Worth recording because every plausible heuristic failed, and each failed differently:

| Model | Result |
| --- | --- |
| `gemini-3-pro-preview` | 404 — preview access not enabled on the account |
| `gemini-2.5-pro` | 404 — *listed by the models API* and "no longer available to new users" |
| `gemini-3.1-pro-preview` | 429 — free-tier quota for pro is `limit: 0` |
| `gemini-3.8-flash` | works |

"Prefer generally available over preview" is wrong: the GA model is closed to new accounts and
Google's own error directs them to a preview. "Prefer the most capable tier" is wrong: a free
key cannot call a pro model at all. The default is a flash model because that is what a reader
with a free key can actually run, and the config comment says so along with the one-line curl
that lists what a given key can reach.

### What this does not claim

Nothing here calls three APIs and compares them. The abstraction covers the request shape;
tool-call reliability, streaming chunk granularity, and how each provider treats a system prompt
differ in ways only live traffic reveals. A cross-provider contract test would need three sets
of credentials and would cost money on every run, so it does not belong in CI. And Grok's
protocol compatibility is xAI's to maintain, not something this repository can guarantee.

Claude remains the default. The sampling-parameter workaround in
[`SeededSamplingParameterStripper`](../src/main/java/dev/merlionos/customerservice/config/SeededSamplingParameterStripper.java)
is the only provider-specific code, and it stays inert under the others.

---

[← Back to the README](../README.md)
