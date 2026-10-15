package ai.proxyma.core.chat.workflow.run;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pause, resume and stop for one run.
 *
 * <p>THE HONEST PART, WHICH THE UI HAS TO REPEAT. Both take effect BETWEEN nodes. No provider
 * offers a way to suspend a call in flight, so a pause requested while an LLM node is generating
 * lands when that node returns - which is why {@link #pauseRequested()} exists separately from
 * {@link #paused()}, and why the run panel says "Pausing after the current step" rather than
 * showing a paused run that is still spending money. Stop behaves the same way, and matches what
 * abort already does in chat: {@code react()} answers an abort by returning, not by throwing.
 *
 * <p>A PAUSED RUN IS IN MEMORY. It is a thread parked on {@link #awaitResume()}, so a restart ends
 * it - which is exactly what {@code WorkflowRunReconciler} already does to a RUNNING row it finds
 * at startup. Durable pause is resume-from-node, which needs the run scope rebuilt from the
 * persisted log, and is deliberately not attempted here.
 */
public final class RunControl {

    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Object monitor = new Object();

    /** Ask the run to pause once the node in flight finishes. */
    public void pause() {
        pauseRequested.set(true);
    }

    public void resume() {
        pauseRequested.set(false);
        synchronized (monitor) {
            paused.set(false);
            monitor.notifyAll();
        }
    }

    /** Ends the run at the next boundary. Also wakes a paused run so Stop works while paused -
     *  without this a paused run could only be ended by restarting the process. */
    public void stop() {
        stopped.set(true);
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    public boolean stopped() { return stopped.get(); }

    /** True between the request and the boundary where it takes effect. What the UI's
     *  "pausing after the current step" is bound to. */
    public boolean pauseRequested() { return pauseRequested.get() && !paused.get(); }

    public boolean paused() { return paused.get(); }

    /**
     * Parks the run thread if a pause is pending. Returns true if the run should carry on, false if
     * it was stopped while parked.
     */
    boolean awaitResume() {
        if (!pauseRequested.get() || stopped.get()) return !stopped.get();
        synchronized (monitor) {
            paused.set(true);
            while (pauseRequested.get() && !stopped.get()) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    // A restart interrupting the run is a stop, not a resume: carrying on with half
                    // a JVM shutting down underneath would leave the run row lying about what
                    // happened.
                    Thread.currentThread().interrupt();
                    stopped.set(true);
                }
            }
            paused.set(false);
        }
        return !stopped.get();
    }
}
