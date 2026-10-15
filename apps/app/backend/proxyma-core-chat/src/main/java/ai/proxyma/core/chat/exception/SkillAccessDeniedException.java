package ai.proxyma.core.chat.exception;

// Thrown by ChatSkillService.update()/delete() when a skill is visible to the requester but not
// editable by them (not the owner, and not a Super Admin) - see ChatSkillService's class javadoc
// for the mutation rule. Mirrors ConversationAccessDeniedException's precedent in this same
// package: a distinct unchecked type so ChatSkillController can map it to a 403 instead of a
// generic error, without conflating "you can't see this" (NoSuchElementException -> 404) with
// "you can see this but can't change it" (this -> 403).
public class SkillAccessDeniedException extends RuntimeException {
    public SkillAccessDeniedException(String message) {
        super(message);
    }
}
