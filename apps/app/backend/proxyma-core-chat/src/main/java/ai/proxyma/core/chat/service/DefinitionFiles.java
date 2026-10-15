package ai.proxyma.core.chat.service;

import java.nio.file.Path;
import java.util.NoSuchElementException;

/**
 * The file a skill or a workflow is kept in, by its id - and never a file anywhere else.
 *
 * <p>The id arrives as a path variable. Spring Security's firewall refuses an encoded separator or a
 * {@code ..} in a URL, but only where Spring Security runs: Desktop and Home Server have none, and
 * WebFlux decodes {@code %2F} inside a path variable. So {@code /chat/skills/..%2F..%2F<path>} read,
 * rewrote or deleted whatever {@code .md} file the process could reach - on Home Server, from
 * anywhere on the network. WorkflowService checked its ids and ChatSkillService, its near-copy,
 * did not (chat-agent-11); both use this one check now.
 */
final class DefinitionFiles {

    private DefinitionFiles() {
    }

    /**
     * {@code dir}/{@code id}.md, when that is a file directly in {@code dir}.
     *
     * @throws NoSuchElementException otherwise - "no such skill", which is what such an id names
     */
    static Path jailed(Path dir, String id, String what) {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\")
                || id.contains("..") || id.startsWith(".") || id.indexOf(':') >= 0 || id.indexOf('\0') >= 0) {
            throw new NoSuchElementException("No such " + what + ": " + id);
        }
        Path home = dir.toAbsolutePath().normalize();
        Path resolved = home.resolve(id + ".md").normalize();
        if (!home.equals(resolved.getParent())) {
            throw new NoSuchElementException("No such " + what + ": " + id);
        }
        return resolved;
    }
}
