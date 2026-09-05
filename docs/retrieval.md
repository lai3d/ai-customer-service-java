# Retrieval


The FAQ corpus lives in [`src/main/resources/faq/faq.json`](../src/main/resources/faq/faq.json) —
18 entries across returns, shipping, payment, account, and support, each written in English and
Chinese. Every language becomes its own document, so 36 in total. **It is sample data.** Replace
it before this answers anything real.

Ingestion runs at startup and *replaces* what it wrote last time rather than appending.
Duplicates do not merely waste space: they crowd out distinct passages in the top-k window, so
the model sees one answer four times instead of four different ones.

No text splitter, deliberately. An FAQ entry is already the unit a customer's question should
match, and splitting one would separate a question from its answer. Long-form policy documents
would need one.

### Choosing an embedding model, by measurement

Three models were tried. The first two were rejected on data, and both rejections are more
interesting than the final choice.

**`all-MiniLM-L6-v2`** — the original. Clean separation on English: correct answers scored 0.34
to 0.63 against paraphrased questions, unrelated questions peaked at 0.11. It is also
English-only, so a Chinese corpus was never going to work.

**`paraphrase-multilingual-MiniLM-L12-v2`** — rejected. Chinese retrieval mostly worked, but a
colloquial damage report (*包裹到的时候是坏的*) scored **0.21** against its own answer while an
unrelated question scored **0.14**. No threshold exists that keeps one and rejects the other,
and the failure lands on exactly the customer you least want to fail. The cause is the model
class: `paraphrase-*` models are trained for *symmetric* similarity — is sentence A like
sentence B — while retrieval is *asymmetric*: does this short colloquial query match this long
written passage. It also regressed English, demoting `returns-window` to third place on a
question the previous model got right.

**`multilingual-e5-small`** — chosen. The e5 family is retrieval-trained. Same 384 dimensions,
so the pgvector column did not change. **20 of 20 paraphrased questions, ten English and ten
Chinese, now retrieve the correct entry first**, and the damage report went from 0.21 to 0.89.

e5 requires asymmetric input markers — `query: ` before a search query, `passage: ` before an
indexed document. These are part of the model contract, not decoration, and applying them to
only one side is worse than applying neither.
[`PrefixingEmbeddingModel`](../src/main/java/dev/merlionos/customerservice/rag/PrefixingEmbeddingModel.java)
wraps the embedding model, because the vector store already separates the two cases for us: it
embeds through `embed(List<Document>, …)` when writing and `embed(String)` when searching.
Nothing above that class knows the convention exists.

### The threshold does not work, and the first measurement of that was too kind

With the English-only model, `similarity-threshold` was a genuine relevance filter sitting in
open space between two well-separated populations. e5 compresses cosine similarity into a narrow
high band. The first measurement here — 20 relevant questions against 5 off-topic ones — read:

| | n | min | max |
| --- | --- | --- | --- |
| Relevant | 20 | **0.8378** | 0.9337 |
| Off-topic | 5 | 0.6977 | **0.8318** |

and concluded that a margin of 0.006 was too thin to tune against. That conclusion was right and
the number was optimistic, because five off-topic questions is not a sample. Widening it — and
adding degenerate input, which had not been tested at all — inverts the result:

| | n | strongest |
| --- | --- | --- |
| Relevant, weakest | 8 | 0.8378 &nbsp;·&nbsp; *"my parcel showed up broken"* |
| **Off-topic, strongest** | **15** | **0.8543** &nbsp;·&nbsp; *"你们公司多少人"* |
| **Degenerate, strongest** | **12** | **0.8417** &nbsp;·&nbsp; *"。。。"* |

The populations do not nearly touch. They **overlap**, by −0.0165. A threshold high enough to
reject *"你们公司多少人"* also rejects real questions; one low enough to keep real questions
accepts three full stops. `0.5` was neither: it filtered nothing measured while implying a
mechanism that did not exist, so the configured value is now **0**.

Two things about how this was found are worth more than the number.

It came from someone else running the same measurement with different samples — the Go
implementation, working from this corpus and this model. The finding reproduced here exactly,
including a stronger off-topic case than they had.

And a test in this repository asserted the opposite and **passed**, because it compared the
weakest relevant score against four hand-picked off-topic questions. A test that encodes a false
claim is worse than no test: it reads as evidence. It now pins the overlap instead, which is
what is actually true and what stops the threshold being reintroduced.

The decision — relevance judgement belongs in the system prompt, which is told that retrieved
material is similarity-selected and that some of it will be unrelated — is unchanged and better
supported than before.

### Multi-intent questions, and what fixed them

Retrieval was measured again after the system had been used against the live API, on questions
of the kind customers actually send — an order number, a complaint, and an ask, in one message.
Short paraphrases score 20 of 20; these do not:

> 我的订单 ORD-10045 退货退款一直没到账，我要找人工客服处理

`returns-refund-timing`, which answers it, came back at rank 13. The assistant duly said it had
no information on something the corpus covers.

The diagnosis is not that retrieval is broken. The entry scored 0.8503 against a top hit of
0.8820 — **twelve passages inside a 0.03 band**, which is the compressed e5 score distribution
again, seen from the other side: when everything is a near-tie, which one ranks first is close
to noise.

**Query expansion was tried first and rejected.** Spring AI ships `MultiQueryExpander`, which
asks the model to split a question into sub-queries and retrieves for each. Two problems, both
measured. It costs about 3.5 seconds per turn, on every turn. And as shipped it does not work
with this model: `expand()` requires the response to split into *exactly* `numberOfQueries`
lines and silently returns the original query otherwise, which happened on 10 of 10 cases with
the default prompt and 9 of 10 with a prompt that spelled the format out. A component that fails
open, logging one line at WARN, is worse than no component. `QueryExpansionComparisonTest` keeps
the evidence; it is tagged `benchmark` because it calls the live API.

**Raising `topK` was measured instead**, and the numbers made the decision:

| topK | recall (14 cases) | context tokens per turn |
| --- | --- | --- |
| 4 | 12/14 | 223 |
| **8** | **13/14** | **452** |
| 13 | 14/14 | 717 |

`topK: 8` buys one of the two failures for about 230 tokens — roughly 13% on top of a typical
1700-token request. Going to 13 buys the other for 29%, and means putting a third of a
thirty-six-document corpus into every prompt. That case stays unfixed and written down instead
of being paid for by every conversation.

Worth saying plainly: at eighteen entries, retrieval is barely earning its keep. A corpus this
size could sit in the system prompt. The design matters for a corpus that cannot, and the
measurements here are what would carry over.

### Re-imports leave dead entries in the HNSW index

The .NET implementation of this system found that re-ingesting the corpus enough times
without a vacuum made an HNSW index scan return fewer rows than its `LIMIT`: an HNSW scan
collects `hnsw.ef_search` candidates from the graph and only then drops the ones whose heap
tuples are dead, so once the index is mostly dead entries the candidates are too. Reproduced
there in psql on pgvector 0.8.6 with autovacuum off, thirty delete-and-reinsert transactions
of the same 36 rows, index scan returning zero rows against a table holding 36; with
autovacuum on it was a race the suite lost about one run in four.

The same experiment here, on the same pgvector version (`HnswDeadEntriesTest`, autovacuum
off on the table, the scan forced through the index because a 36-row table would otherwise
be read sequentially and hide it):

| write pattern | reloads | live rows | dead tuples | index size | forced index scan returns |
| --- | --- | --- | --- | --- | --- |
| this importer: upsert, then retire the old version | 30 | 36 | 725 | 200 kB | 8 of 8 |
| delete everything, reinsert (the report's pattern) | 30 | 36 | 864 | 224 kB | 8 of 8 |
| delete everything, reinsert | 60 | 36 | 2016 | 528 kB | **6 of 8** |
| either, after `VACUUM` | | 36 | 0 | unchanged | 8 of 8 |

At thirty reloads the starvation did not reproduce and the bloat did; at sixty it is there,
as degradation: six passages where the request said eight, with nothing in the response to
say two are missing. The zero in the original report turned out to be a degenerate case --
its stub embeddings were all identical, so every graph point sat at distance zero and the
candidates were one point's dead copies; re-run there with 36 distinct vectors it became
7 of 8 after sixty reloads. This corpus is closer to the degenerate case than random vectors
are, because each entry's two languages are near-duplicates, which is a fair reading of why
it loses two rather than one.

What changed: the importer now runs `VACUUM vector_store` after each import, outside the
transaction because Postgres refuses it inside one, which is cheap for a corpus this size
and takes the daemon's timing out of the question. `HnswDeadEntriesTest` pins that sixty
imports through the importer leave no dead tuples and the top-k whole, and keeps the defect
itself under observation -- sixty delete-and-reinserts return fewer than eight through the
index, a vacuum restores them -- so a pgvector release that fixes it is noticed here, and the
guard reconsidered, rather than assumed.

### Cross-lingual retrieval

Because both languages are indexed, a Chinese question matches a Chinese passage; same-language
matches score high enough that all eighteen Chinese passages outrank every English one. To
verify that cross-lingual retrieval works *at all* — which is what matters for an entry nobody
has translated yet — the test isolates the English half with a metadata filter and asks in
Chinese. Four for four.

`FaqRetrievalIntegrationTest` runs all of the above on every build, against real pgvector and
the real ONNX model, with no API key. A retrieval regression is a red build, not vaguer answers
in production.

---

[← Back to the README](../README.md)
