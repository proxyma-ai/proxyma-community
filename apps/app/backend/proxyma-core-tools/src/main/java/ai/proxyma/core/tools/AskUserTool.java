package ai.proxyma.core.tools;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The 'askUser' tool: the assistant stops and asks the user a question with a few choices, the way
 * a coding assistant in an editor does - since 2026-09-29, at the maintainer's request.
 *
 * <p>THIS METHOD IS NEVER WHAT ANSWERS A CHAT. ChatService intercepts the call before any tool
 * runs, publishes the questions to the page and pauses the turn until the user picks (the same
 * blocking pause a write approval uses); the choices come back as this call's result. The bean
 * exists for the schema the model sees. Reached any other way - an MCP client, a sub-agent, a
 * workflow node - there is nobody to ask, so it says so; those paths also leave it out of their
 * tool lists (CHAT_ONLY).
 *
 * <p>SEVERAL PICKS ARE A FLAG ON THE QUESTION, and the descriptions below are what makes a model
 * set it. The flag and the page's checkboxes were there from the first day and no question ever
 * used them: the tool said "multiple-choice", the prompt said "2-4 options", and nothing said when
 * a question takes more than one. Since 2026-10-02 both say so, and such an answer comes back as a
 * list (ChatService.resolveQuestion).
 */
@Component
public class AskUserTool {

    public static final String NAME = ai.proxyma.core.chat.question.ChatOnlyTools.ASK_USER;

    /** Tools only a chat with a person on the other end can offer. */
    public static final Set<String> CHAT_ONLY = ai.proxyma.core.chat.question.ChatOnlyTools.ALL;

    public record Option(
            @ToolParam(description = "A short choice, 1-5 words") String label,
            @ToolParam(required = false, description = "What choosing it means, one line") String description) {}

    public record Question(
            @ToolParam(description = "The full question, ending with '?'") String question,
            @ToolParam(required = false, description = "A 1-2 word label shown above it") String header,
            @ToolParam(description = "2-4 choices. The user can always type their own answer instead") List<Option> options,
            @ToolParam(required = false, description = "true when the choices are not exclusive and the user "
                    + "may tick several of them - which sources, which sections, which of these to include. "
                    + "Leave it out when exactly one applies") Boolean multiSelect) {}

    @Tool(name = NAME, description = "Ask the user 1-4 multiple-choice questions and wait for the answers. "
            + "Use it when the choice is genuinely theirs - an ambiguous request, several reasonable ways to do "
            + "something, a preference - not for what a search or tool could find out, and not to confirm a "
            + "write (that has its own approval). Put a recommended option first and say so in its label. "
            + "Each question takes ONE choice unless you set its multiSelect: do that whenever more than one "
            + "choice can apply at once, instead of asking the same thing several times or adding an "
            + "'All of the above' choice.")
    @ToolResult({
            @ToolResultField(name = "answers", type = ToolFieldType.OBJECT_ARRAY, always = false,
                    description = "One entry per question: {question, answer}. The answer is the chosen label or "
                            + "the user's own words - and for a multiSelect question a LIST of them, one entry "
                            + "per choice ticked.")
    })
    public Map<String, Object> askUser(
            @ToolParam(description = "The questions, asked together") List<Question> questions) {
        return Map.of("success", false, "error",
                "askUser only works inside a chat with a person in it - there is nobody to ask here. "
                        + "Decide with what you have and say what you assumed.");
    }
}
