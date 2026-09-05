package dev.merlionos.customerservice.rag.api;

/** What a process does about the bundled corpus when it becomes ready. See {@code app.rag.import-mode}. */
public enum ImportMode {
    /** Import if this corpus version is not yet recorded, then serve. The default. */
    STARTUP,
    /** Import if needed, then exit: a Kubernetes Job or a run-once Compose service. */
    ONCE,
    /** Never import; serve whatever the database holds. Serving replicas of the knowledge role. */
    OFF
}
