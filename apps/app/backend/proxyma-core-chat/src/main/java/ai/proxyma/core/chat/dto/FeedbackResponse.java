package ai.proxyma.core.chat.dto;

public record FeedbackResponse(
        Long id,
        String mood,
        String comment,
        boolean anonymous,
        String source,
        // Whether the report reached Proxyma. Everything submitted is stored here first, so a 200
        // alone says nothing about delivery - and the dialog thanked people for reports the
        // telemetry service had refused.
        Delivery delivery) {

    public enum Delivery {
        /** The telemetry service accepted it. */
        SENT,
        /** It was meant to leave and did not: refused, or the service could not be reached. */
        NOT_SENT,
        /** It was never meant to leave: no telemetry address, a visitor's report on a server, or a
         *  rating whose sharing is off. */
        KEPT_HERE
    }
}
