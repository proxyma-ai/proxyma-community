package ai.proxyma.core.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * chat-agent-11: a skill id is a file name in the skills folder and nothing else. Desktop and Home
 * Server have no request firewall, WebFlux decodes %2F inside a path variable, and every caller
 * there is super admin - so {@code /chat/skills/..%2F..%2Fnotes} read, rewrote or deleted any .md
 * file the process could reach. A file with no frontmatter reads as nobody's, so as visible.
 */
class SkillIdJailTest {

    @TempDir
    Path data;

    private static final String[] OUTSIDE = {"../notes", "..\\notes", "../../x/notes", "..", ".hidden",
            "a/b", "C:notes", "notes/../../notes"};

    @Test
    @DisplayName("an id that is not a plain name reads, rewrites and deletes nothing")
    void idsStayInTheFolder() throws Exception {
        Path skills = Files.createDirectories(data.resolve("skills"));
        Path notes = Files.writeString(data.resolve("notes.md"), "private notes");
        ChatSkillService service = new ChatSkillService(skills.toString(), new DefaultSkillRegistry());

        for (String id : OUTSIDE) {
            assertThatThrownBy(() -> service.get(id, null, true)).as(id).isInstanceOf(NoSuchElementException.class);
            assertThatThrownBy(() -> service.update(id, "n", "d", "x", null, true, List.of(), List.of(), List.of(), null))
                    .as(id).isInstanceOf(NoSuchElementException.class);
            assertThatThrownBy(() -> service.delete(id, null, true)).as(id).isInstanceOf(NoSuchElementException.class);
        }
        assertThat(Files.readString(notes)).isEqualTo("private notes");

        // An ordinary skill still works.
        String id = service.create("My skill", "short", "detail", null, List.of(), List.of(), List.of()).id();
        assertThat(service.get(id, null, true).name()).isEqualTo("My skill");
        assertThat(skills.resolve(id + ".md")).exists();
    }
}
