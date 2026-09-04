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

### The threshold stopped working, and that is the finding

With the English-only model, `similarity-threshold` was a genuine relevance filter sitting in
open space between two well-separated populations. e5 compresses cosine similarity into a
narrow high band, and across 30 queries the two populations nearly touch:

| | n | min | max |
| --- | --- | --- | --- |
| Relevant questions (en + zh) | 20 | **0.8378** | 0.9337 |
| Off-topic questions (en + zh) | 10 | 0.6977 | **0.8318** |

A margin of **0.006** is noise, not signal. Tuning the threshold to 0.835 would fit these 30
queries and break on the 31st.

So relevance filtering moved out of the retriever and into the prompt. The threshold is now a
floor for degenerate input; the system prompt tells the model that reference material is
selected by similarity, that some of it will be unrelated, and to say so rather than stretch an
unrelated passage to fit. Ranking is what the retriever is good at, and it is good at it: 20 of
20.

This is worth stating plainly because the opposite is a common failure — porting a threshold
across an embedding-model change and never noticing it stopped meaning anything.

### The same system, asked in Chinese

![The demo UI in Chinese: a Chinese question retrieves Chinese passages and is answered in Chinese, with the same tool call and token accounting](images/demo-zh.png)

Nothing is switched but the question. The corpus is indexed in both languages, retrieval picks
the Chinese passages, and the answer comes back in Chinese with the same tool call and the same
accounting behind it.

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
