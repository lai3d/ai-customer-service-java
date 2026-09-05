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
        this(null);
    }

    /**
     * @param connectorName when several servers share this JVM -- the services-topology
     *                      benchmark runs knowledge and ticket next to chat -- only count the
     *                      workers of the one with this connector. Tomcat names its platform
     *                      workers {@code <connector>-exec-N}, and the connector is
     *                      {@code http-nio-<port>} for a fixed port and {@code http-nio-auto-N}
     *                      for {@code server.port=0}: a filter built from the bound port matched
     *                      nothing, and reported zero workers under a thousand requests. The
     *                      virtual-thread handlers ({@code tomcat-handler-N}) carry no connector
     *                      name and are not platform threads, so they never appear here anyway
     */
    ServerThreadSampler(String connectorName) {
        String portPrefix = connectorName == null ? null : connectorName + "-";
        this.sampler = Thread.ofPlatform().daemon().name("bench-sampler").start(() -> {
            while (running) {
                Set<Thread> platformThreads = Thread.getAllStackTraces().keySet();
                int workers = (int) platformThreads.stream()
                        .map(Thread::getName)
                        .filter(name -> portPrefix == null
                                ? WORKER_PREFIXES.stream().anyMatch(name::startsWith)
                                : name.startsWith(portPrefix) || name.startsWith("tomcat-handler-"))
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
