package dev.merlionos.customerservice.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Turns a process died in the middle of are marked {@code unknown}: at startup, because
 * this may be that process restarting, and every minute, because the process that died may
 * never restart. The threshold is the turn lease, which no live turn outlives. Safe to run
 * from every replica at once: the update is a single statement over rows older than the
 * lease, and two replicas marking the same row both write the same thing.
 */
@Component
class TurnRecordSweeper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TurnRecordSweeper.class);

    private final TurnRecorder recorder;
    private final ChatProperties properties;

    TurnRecordSweeper(TurnRecorder recorder, ChatProperties properties) {
        this.recorder = recorder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        sweep();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void sweep() {
        int marked = recorder.sweep(properties.turnLease());
        if (marked > 0) {
            log.warn("Marked {} turn(s) that never finished as unknown; a process died holding them", marked);
        }
    }
}
