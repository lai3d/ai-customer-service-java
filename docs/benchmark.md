# Virtual threads, measured


The brief specifies `spring.threads.virtual.enabled=true` and no WebFlux, reasoning that an LLM
call is a long blocking wait. That was a claim with no evidence behind it, so here is the
measurement: the same real endpoint, the same load, the setting flipped.

```
./mvnw test -Dexcluded.test.groups= -Dtest='VirtualThreadBenchmark*'
```

**1000 concurrent requests, 1000 ms stubbed model delay** — Apple M5 Max (18 cores), JDK 21.0.12:

| threads | wall | req/s | p50 | p95 | p99 | Tomcat platform threads | all platform threads |
| --- | --- | --- | --- | --- | --- | --- | --- |
| platform | 6254 ms | 160 | 4037 ms | 6118 ms | 6174 ms | **202** | 246 |
| virtual | 2000 ms | 500 | 1616 ms | 1955 ms | 1986 ms | **2** | 52 |

**Re-measured on 2026-09-05, after ADR 001 phase 2** moved the ticket cap, the token budget and
a one-turn-per-conversation lease into Postgres. Same machine, same load, same stub; the numbers
above are kept as measured at the commit before that change, and these are the ones the current
code produces:

| threads | wall | req/s | p50 | p95 | p99 | Tomcat platform threads | all platform threads |
| --- | --- | --- | --- | --- | --- | --- | --- |
| platform | 6448 ms | 155 | 4114 ms | 6249 ms | 6309 ms | **202** | 248 |
| virtual | 2220 ms | 450 | 1767 ms | 2157 ms | 2197 ms | **2** | 53 |

About 10% on the virtual run and within noise on the platform one. Every request now takes a
lease row and releases it, and records its spend with one `INSERT ... ON CONFLICT` -- three
statements on a pool of 20 that a thousand concurrent requests already contended for. The
thread columns did not move, which is the result the benchmark exists to defend. The 2.9x
ratio stands.

### The same load, with the roles in separate processes

`ServicesTopologyBenchmark` runs the identical load against a `chat` process whose retrieval
goes over HTTP to a `knowledge` process (which embeds the query and searches pgvector) instead
of in-process, with a `ticket` process alongside. Same machine, same day, the three roles in
one JVM on loopback as in `TopologyParityTest`:

```
./mvnw test -Dexcluded.test.groups= -Dtest='ServicesTopologyBenchmark*'
```

| threads | wall | req/s | p50 | p95 | p99 | chat Tomcat platform threads | JVM platform threads\* |
| --- | --- | --- | --- | --- | --- | --- | --- |
| platform | 6119 ms | 163 | 3790 ms | 5946 ms | 6024 ms | **202** | 333 |
| virtual | 2232 ms | 448 | 1819 ms | 2195 ms | 2224 ms | **2** | 161 |

\* the JVM also hosts the knowledge and ticket servers and the load driver, so this column is
not a chat pod's number; the per-role memory in [k8s/README.md](../k8s/README.md#what-running-the-split-found)
is the measurement to use for that.

Against the single-process rows above -- 450 req/s and 1767 ms p50 on the virtual run -- the
seam costs about half a percent of throughput and 50 ms at the median, on loopback. The
work is the same work; it moved across a socket without getting more expensive, and the chat
process's thread count is the same two platform threads. What a real network adds is latency
and a failure mode, and the failure mode is what `TopologyParityTest` and
`scripts/verify-services.sh` cover: with knowledge gone a turn fails rather than answering
ungrounded, with ticket gone the tool returns a value the model can act on.

The worker column was zero on the first two runs. Tomcat names a `server.port=0` connector's
threads `http-nio-auto-N-exec-M`, and a filter built from the bound port -- and then one built
from the connector's own `getName()`, which turns out to be `"http-nio-auto-3-50426"` with
quotes and the port appended -- matched nothing. A platform-thread run reporting zero workers
under a thousand requests is the kind of number that should have been impossible, and it was
the reason to look rather than to report it.

Three times the throughput, and the median customer waits 1.6 seconds instead of 4.0 for an
operation that takes 1.0. But the thread column is the real result: with virtual threads the
server holds a thousand in-flight requests on **two** platform threads — Tomcat's acceptor and
poller. With platform threads it pins 200, hits the pool ceiling, and queues the remaining 800
into four more waves.

The model is stubbed with a fixed delay; an LLM call is mostly waiting, and a real one would add
cost, network variance and rate limits to a measurement about thread scheduling. Everything else
is the production path — validation, chat memory in Postgres, query embedding on the CPU, a
pgvector search, tool definitions, metrics and spans. That is why the virtual run takes 2.0
seconds rather than the 1.0 the arithmetic suggests: the retrieval work is real work.

### Where the extra second goes — a guess, then a measurement

The obvious suspect was the connection pool: 20 connections against a thousand concurrent
requests. Raising it to 100 was worth about 7% (2503 ms → 2338 ms on a matched pair of runs), so
that guess was mostly wrong. What remains is dominated by the per-request work itself, the
CPU-bound query embedding in particular. It was not isolated further.

The interesting part is that virtual threads did not make the work cheaper — they moved the
bottleneck off thread scheduling and onto the work the service actually does, which is where a
bottleneck belongs.

### Two measurement mistakes, both worth knowing about

**Whole-JVM peak thread count was useless.** It made the virtual run look *worse* — 263 threads
against 245. The load driver shares the JVM and its own carrier threads land in the same total,
and under JDK 21 a virtual thread blocking inside `synchronized` pins its carrier and the
scheduler compensates by adding more. Counting Tomcat's request-handling platform threads
specifically is what produced the 202-versus-2 result above.

**Spring's test context cache kept both servers alive.** With two contexts in the cache, the idle
one's 200-thread pool was counted against whichever run happened to go second — which is why
both rows once read 202. `@DirtiesContext` closes each server before the next starts.

### A third mistake, found by running the same benchmark in another language

The stub delay is a **constant** 1000 ms, and that flatters both runtimes. Every request arrives
at once and finishes at once, so nothing ever queues behind a slow neighbour and the thread
counts describe the worst case of perfectly simultaneous arrivals rather than anything traffic
does. The [Go implementation](https://github.com/lai3d/ai-customer-service-go) re-ran its own
rows with the same 1000 ms *mean* drawn from `300 ms + Exp(700 ms)`: p50 improved and its OS
thread count fell by roughly a quarter.

So the thread numbers here measure how concentrated the arrivals are at least as much as how
many there are. That does not change the platform-versus-virtual ratio — both rows were measured
under the same arrival pattern — but it does change what the counts are evidence *for*: they
argue for bounding concurrency, not for sizing a pool to a measured peak that no real traffic
will reproduce. The Java rows have not been re-run with a variable delay; that is a known gap
rather than a claim.

The benchmark is committed and reproducible but tagged `benchmark` and excluded from the normal
build: it measures a machine rather than asserting a behaviour, and the numbers above are from
one laptop with the load generator sharing its JVM. Run-to-run variance is a few hundred
milliseconds. Treat the ratio and the thread counts as the findings, not the absolute timings.

---

[← Back to the README](../README.md)
