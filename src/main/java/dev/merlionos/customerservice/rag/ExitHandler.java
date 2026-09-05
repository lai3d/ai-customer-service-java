package dev.merlionos.customerservice.rag;

/**
 * How a run-once process ends. With no bean of this type the importer calls
 * {@code System.exit}; a test that starts a real context in {@code once} mode registers one,
 * because {@code System.exit} in a test JVM ends the test run.
 */
@FunctionalInterface
public interface ExitHandler {

    void exit(int code);
}
