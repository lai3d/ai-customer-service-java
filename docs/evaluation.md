# Evaluation: the golden set and the deflection rate

Business plan step 3. A customer pays for two numbers: how often the assistant answered
correctly, and how often it settled the conversation without a person. Until now the
repository measured retrieval (which passage was found, [retrieval.md](retrieval.md)) and
nothing about the answer; and it counted tickets, but not conversations. This is the record
of what was built for both, what the first run measured, and what the numbers do not mean.

## The golden set

A golden case is a question and what a correct answer must satisfy, per tenant, in
`golden_case`:

| Field | Rule |
| --- | --- |
| `expectedEntryIds` | every one must be among the passages retrieved for the question |
| `mustContain` | every phrase must appear in the answer |
| `anyOf` | at least one must appear |
| `mustNotContain` | none may appear |
| `expectTool` | a tool by name (`lookup_order_status`, `create_support_ticket`) must have run |
| `expectRefusal` | the question is outside the knowledge; the case must then say which phrases mark a refusal (`anyOf`) or which facts it must not claim (`mustNotContain`) |

Matching is by string after normalisation (`Scoring.normalise`): case-folded, whitespace
collapsed, full-width digits narrowed, en and em dashes made hyphens, the space between a
digit and a CJK character dropped, so "3–5" and "3-5" agree and "30 分钟" and "30分钟"
agree. **The rubric checks facts, not prose.** A phrase is the fact a wrong answer would get
wrong: "30 days", "prepaid", "PayPal", "备货中". Whether the answer is well put, polite or
short is a judgement this does not make; a model-graded score is the next step if a pilot
asks for it, and it will cost a second model call per case.

The default tenant's set is bundled (`src/main/resources/golden/golden.json`, 43 cases) and
seeded once into an empty table, the way the bundled corpus is adopted: one paraphrase of
each FAQ entry in each language, two questions with two intents, the two tools, and two
questions the knowledge does not cover. A tenant's own set comes from its pilot's real
questions, through the admin (`/admin/api/evaluation/cases`), and that is the set that
matters: the bundled one proves the harness, not the product.

## A run

`POST /admin/api/evaluation/runs` (admins, `?tenant=`) asks every enabled case through the
real chat path -- the advisor chain, retrieval against the tenant's active knowledge, the
tools, the model -- one fresh conversation per case, and scores each answer. It runs off the
request thread and is polled at `/runs/{id}`, like a publication or an import; one run per
tenant at a time. Every case is a paid model call, and the run records the tokens, so an
evaluation has a price on it. The conversations it creates are marked `evaluation` in
`conversation.kind` and count nowhere as customers.

The latest finished run's ratios are gauges per tenant (`evaluation_pass_ratio`,
`evaluation_retrieval_hit_ratio`, `evaluation_answer_pass_ratio`, `evaluation_tool_pass_ratio`,
`evaluation_cases`) on the Quality row of the [dashboard](observability.md), next to cost,
loaded at startup so a restart shows the last number rather than zero. That is the point:
change the model, the prompt, top-k or the knowledge, run, and read the line move.

The bundled set can also be run from the command line, against the real provider:

```bash
./mvnw test -Dexcluded.test.groups= -Dtest=GoldenSetEvaluation      # -Devaluation.only=30,37 for a subset
```

with the provider's key in the environment as the Spring property the test profile would
otherwise pin (`SPRING_AI_ANTHROPIC_API_KEY`; `ANTHROPIC_API_KEY` alone feeds the placeholder
that the test profile overrides). It writes `target/evaluation-report.md`: the table below
and every answer.

## What the first run measured

2026-09-07, the bundled set against `claude-opus-5`, `top-k` 8, the bundled corpus:

| Run | Cases | Passed | Retrieval | Answers | Tools | Tokens in / out |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 43 | 41 | 43 | 41 | 43 | 91,123 / 7,278 |
| 2, rubric fixed | 43 | 43 | 43 | 43 | 43 | 93,075 / 6,983 |

Retrieval found every expected entry for every case, including both entries of the two
multi-intent questions; the tools ran when they should, in both languages; both questions
outside the knowledge were declined. The two failures:

- **A rubric fault.** Case 37 ("change the delivery address; also, how long will delivery
  take?") answered "3–5 business days" with an en dash and the phrase was "3 to 5". The
  normalisation now folds dashes and the case accepts either wording. A rubric that fails a
  correct answer is the harness's bug, and every failure has to be read before it is believed.
- **An empty answer.** Case 30 ("密码忘了，怎么找回？") came back with no text and no error:
  the stream ended without a token or a failure event. Rerun alone three times it answered
  correctly each time (about 1,950 tokens in, 115 out, 3.5 to 4.7 s). Not reproduced, not
  explained, and worth knowing about: a customer would have seen an empty bubble. The run
  now records tokens and time per case so the next occurrence can be told from a failed call.

The second run, with the dash folded into the rubric, passed every case; the empty answer
did not recur. The cost of a run is a number too: about 100,000 tokens for 43 cases, the price of the
context re-sent with every turn (system prompt, eight passages, the tool definitions).

## Deflection

Deflection is what happened with real customers: over the last seven days, per tenant, the
share of customer conversations with at least one turn that ended without a ticket for a
person -- a ticket of ours in `support_ticket`, or one raised in the tenant's panel, which
leaves no row here but a `create_support_ticket` call with outcome `created` in
`turn_tool_call`. `QualityMetrics` samples it every minute from the tables that already exist
(`conversation`, `conversation_turn`, `support_ticket`, `answer_feedback`) into
`chat_deflection_rate`, with the complement `chat_escalation_rate`, the share a member of
staff flagged as wrong or incomplete `chat_flagged_rate`, and the denominator
`chat_window_conversations`. `GET /admin/api/evaluation/deflection?days=` returns the same
number with its definition, so the admin and the dashboard cannot disagree.

What it is not: satisfaction. A customer who asked once and left without a ticket is
deflected, whether the answer helped or not; a customer who got a good answer and then
raised a ticket about something else is escalated. The flagged rate is the nearest thing to
quality in it, and only as good as the staff's habit of flagging. A rate over a handful of
conversations means nothing, which is why the denominator is on the dashboard beside it.
The pilot's own number will be whatever these say over its first weeks, and the golden set
built from its real questions is what turns "the assistant answered" into "the assistant
answered correctly".

## Not here yet, deliberately

- A model-graded score (an LLM judge) for answer quality beyond facts.
- Per-run comparison in the admin (run A against run B, case by case); the rows are there.
- The golden set as a file a tenant uploads; today it is entered case by case or seeded.
- Customer-side signals (a thumbs up on the widget); the widget renders answers only.
