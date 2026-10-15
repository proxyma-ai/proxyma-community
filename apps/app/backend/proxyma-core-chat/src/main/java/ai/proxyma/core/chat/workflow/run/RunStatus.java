package ai.proxyma.core.chat.workflow.run;

/**
 * What a run is doing, or how it ended.
 *
 * <p>HALTED is its own state rather than a flavour of COMPLETED, because "it decided there was
 * nothing to do" and "it did the thing" are the two answers a reader most needs to tell apart - and
 * a Halt node exists precisely so a procedure can say the first one instead of ploughing through
 * seven more steps producing a confident report about the wrong premise.
 *
 * <p>STOPPED is separate from FAILED for the same reason a skipped step is not a failed one: a
 * person pressing Stop is not a defect, and a run list that colours the two alike teaches its
 * reader to ignore red.
 */
public enum RunStatus {
    RUNNING,
    PAUSED,
    AWAITING_APPROVAL,
    COMPLETED,
    HALTED,
    FAILED,
    STOPPED;

    public boolean finished() {
        return this == COMPLETED || this == HALTED || this == FAILED || this == STOPPED;
    }
}
