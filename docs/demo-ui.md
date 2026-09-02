# The demo UI


`docker compose up` serves a single-page demo at **http://localhost:8080** — one HTML file, no
build step, no `npm install`.

It is deliberately not a chat widget. A widget's job is to make the AI feel seamless and
invisible; this repository's substance *is* the invisible part. So the page is a glass box:
conversation on the left, and on the right, for every turn, the passages retrieval found with
their scores, the tools the model called, the tokens spent, and a link to that turn's trace in
Jaeger.

That required real work in the backend, not just a page. The stream now carries typed events —
`retrieval`, `tool`, `message`, `usage`, `error` — instead of bare tokens:

```
event:retrieval
data:{"passages":[{"entryId":"shipping-cost","language":"zh","score":0.934}, …]}

event:tool
data:{"tool":"lookup_order_status","outcome":"found"}

event:message
data:{"text":"运费"}

event:usage
data:{"inputTokens":1204,"outputTokens":87,"millis":2140,"traceId":"a3f…"}
```

A production widget would read `message` and `error` and ignore the rest.

### Two things the UI forced the backend to get right

**Retrieval is reported before the model is called.** Reading the retrieved documents off the
streamed response was the obvious approach and it was wrong twice: the passages only appeared
once the first token did, so nothing could be shown while the model was still thinking; and when
the model call failed, no response was ever emitted and the retrieval looked like it had never
happened — which is exactly when someone debugging a bad answer most needs to see it.
[`RetrievalReportingAdvisor`](../src/main/java/dev/merlionos/customerservice/chat/RetrievalReportingAdvisor.java)
sits after `QuestionAnswerAdvisor` and publishes what it just retrieved, before the model call.

**Tool calls needed a way out of the model call.** Spring AI executes tools inside the chat call
on its own scheduler, with no return path to the controller other than the model's eventual
answer, so a tool invocation is invisible until the assistant happens to mention it.
[`TurnEventBus`](../src/main/java/dev/merlionos/customerservice/chat/TurnEventBus.java) keys a sink
by conversation id — which tools already receive through `ToolContext`.

### Honesty in the visualisation

Score bars are normalised within each result set, not drawn from the raw score. e5 compresses
cosine similarity into a narrow high band, so absolute widths made every bar look full and
conveyed nothing; relative widths show ranking, which is the part that is reliable — the same
finding that moved relevance filtering out of the threshold and into the prompt.

The retrieval card shows no duration, because retrieval happens inside `QuestionAnswerAdvisor`,
upstream of anything that could time it honestly. The first version read `0ms`. The real timing
is on the `pg_vector query` span in the trace.

---

[← Back to the README](../README.md)
