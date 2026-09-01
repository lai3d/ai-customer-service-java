package dev.merlionos.customerservice.benchmark;

import java.util.Set;

/**
 * Counts the platform threads Tomcat is using to hold requests, sampled while load is running.
 *
 * <p>Whole-JVM peak thread count is the wrong measure here: the load driver shares this JVM and
 * its own carrier threads land in the same total, and under JDK 21 a virtual thread that blocks
 * inside {@code synchronized} pins its carrier and the scheduler compensates by adding more.
 * Both inflate the number in the virtual run and hide the effect being measured.
 *
 * <p>{@link Thread#getAllStackTraces()} returns platform threads only, which is what makes this
 * work: in the platform run the request-handling pool shows up and grows toward its ceiling; in
 * the virtual run the requests are held by virtual threads and the pool is simply not there.
 */
final class ServerThreadSampler implements AutoCloseable {

    private static final Set<String> WORKER_PREFIXES = Set.of("http-nio-", "tomcat-handler-");

    private final Thread sampler;
    private volatile boolean running = true;
    private volatile int peakWorkers;
    private volatile int peakPlatformThreads;

    ServerThreadSampler() {
        this.sampler = Thread.ofPlatform().daemon().name("bench-sampler").start(() -> {
            while (running) {
                Set<Thread> platformThreads = Thread.getAllStackTraces().keySet();
                int workers = (int) platformThreads.stream()
                        .map(Thread::getName)
                        .filter(name -> WORKER_PREFIXES.stream().anyMatch(name::startsWith))
                        .count();
                peakWorkers = Math.max(peakWorkers, workers);
                peakPlatformThreads = Math.max(peakPlatformThreads, platformThreads.size());
                try {
                    Thread.sleep(20);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    int peakWorkers() {
        return this.peakWorkers;
    }

    int peakPlatformThreads() {
        return this.peakPlatformThreads;
    }

    @Override
    public void close() throws InterruptedException {
        this.running = false;
        this.sampler.join(1000);
    }
}
