# Tool calling


The model can call two tools. Both are mock implementations: the point of Phase 1 is the
calling contract, not an order system.

| Tool | What it does |
| --- | --- |
| `lookup_order_status` | Reads one order by number. Case- and whitespace-tolerant, because customers paste order numbers out of emails. |
| `create_support_ticket` | Raises a ticket for a human agent, attributed to the conversation it came from. |

**Tool descriptions are prompt, not documentation.** They are the entire basis on which the
model decides whether to call a tool instead of answering from retrieved FAQ text, so they say
what each tool is *not* for as well as what it is for. `ToolDefinitionTest` asserts on the
generated JSON schema — names, descriptions, and which parameters are required — because a
rename or a dropped description changes model behaviour without changing anything else a test
would notice.

### Three decisions worth calling out

**A missing order is a value, not an exception.** Spring AI's default behaviour on a thrown
tool exception is to feed the exception's *message* back to the model as the tool result. Throw
on a not-found order and you have put an internal error string in front of a customer, and
given the model nothing to reason about. `lookup_order_status` returns `found: false` with a
plain explanation, so the assistant can ask the customer to check the number.

**Anything that does still throw is scrubbed.** A `ToolExecutionExceptionProcessor` bean
replaces the exception message with a fixed instruction and logs the real one. Otherwise a
connection string or an internal id in some future exception becomes something the assistant
can repeat to a customer.

**Ticket creation is idempotent per conversation.** A model can call the same tool twice in one
turn, and a retried request replays the conversation. Without a guard, one frustrated customer
becomes three tickets in the human agents' queue. Asking twice returns the existing ticket
flagged `alreadyExisted`, so the assistant says "I've already raised that" rather than inventing
a second reference number.

### Conversation identity

`create_support_ticket` takes a Spring AI `ToolContext` parameter — excluded from the JSON
schema, so the model never sees it — through which the service passes the conversation id. That
is what links a ticket back to the conversation that produced it.

It also creates an implicit contract with teeth: Spring AI rejects a call to a
`ToolContext`-taking tool when the context is empty, *before* the tool body runs. A code path
that reaches the model without setting it breaks ticket creation, and breaks it only once a
conversation has escalated far enough for the model to try. `ChatServiceToolContextTest` checks
both entry points instead of waiting for that.

---

[← Back to the README](../README.md)
