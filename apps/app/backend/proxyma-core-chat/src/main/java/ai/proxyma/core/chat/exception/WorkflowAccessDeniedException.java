package ai.proxyma.core.chat.exception;

// Thrown by WorkflowService.update()/delete() when a workflow is visible to the requester but not
// editable by them (not the owner, and not a Super Admin). Mirrors SkillAccessDeniedException in
// this same package exactly, and exists for the same reason: a distinct unchecked type lets
// WorkflowController map it to a 403 without conflating "you can't see this"
// (NoSuchElementException -> 404) with "you can see this but can't change it" (this -> 403).
//
// Deliberately not shared with SkillAccessDeniedException despite the identical shape. The two
// map to the same status from different controllers, and merging them would mean a catch in one
// controller silently widening to cover the other's failures the moment either rule changes.
public class WorkflowAccessDeniedException extends RuntimeException {
    public WorkflowAccessDeniedException(String message) {
        super(message);
    }
}
