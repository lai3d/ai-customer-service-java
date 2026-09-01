package dev.merlionos.customerservice.benchmark;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fires N requests at once and reports how long they took.
 *
 * <p>The driver itself runs on virtual threads regardless of what the server is configured
 * with. A driver that needed a platform thread per in-flight request would run out of threads
 * before the server did and quietly measure itself.
 */
final class LoadDriver {

    record Result(int requests, int successes, int failures, long wallMillis,
                  long p50, long p95, long p99, long max) {

        double throughputPerSecond() {
            return wallMillis == 0 ? 0 : successes * 1000.0 / wallMillis;
        }
    }

    static Result run(int port, int concurrency, String message) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(concurrency));
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(concurrency);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        // A fresh conversation per request: sharing one would serialise on the
                        // chat memory rows and measure Postgres locking instead of threading.
                        String body = """
                                {"conversationId":"%s","message":"%s"}"""
                                .formatted(UUID.randomUUID(), message);

                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + port + "/api/v1/chat"))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofMinutes(5))
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();

                        long began = System.nanoTime();
                        HttpResponse<String> response =
                                client.send(request, HttpResponse.BodyHandlers.ofString());
                        long elapsed = (System.nanoTime() - began) / 1_000_000;

                        latencies.add(elapsed);
                        if (response.statusCode() == 200) {
                            successes.incrementAndGet();
                        }
                        else {
                            failures.incrementAndGet();
                        }
                    }
                    catch (Exception e) {
                        failures.incrementAndGet();
                    }
                    finally {
                        finished.countDown();
                    }
                });
            }

            long began = System.nanoTime();
            startGate.countDown();
            finished.await();
            long wallMillis = (System.nanoTime() - began) / 1_000_000;

            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            return new Result(concurrency, successes.get(), failures.get(), wallMillis,
                    percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99),
                    sorted.isEmpty() ? 0 : sorted.getLast());
        }
    }

    private static long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
    }

    private LoadDriver() {
    }
}
