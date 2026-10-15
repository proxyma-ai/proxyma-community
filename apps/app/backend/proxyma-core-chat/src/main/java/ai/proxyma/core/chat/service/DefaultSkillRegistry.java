package ai.proxyma.core.chat.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// What every seeded ("official") skill ORIGINALLY said, and who seeds it. Populated at startup by
// each seeder as it runs - DefaultChatSkillSeeder and the product modules' seeders (the key-account
// skill), all of them PRODUCT skills.
//
// It exists for two things the skill files on disk cannot answer once an admin has edited them:
//
//  1. "Restore default" (see ChatSkillService.defaultFor / the Skills UI). Seeding is
//     create-if-absent, so a seeder never overwrites an admin's edits - which also means an admin
//     who edits a skill into a corner has no way back. The original text has to be readable at
//     runtime for that button to exist at all, and it only lives in the seeders' source.
//  2. Whether a skill may be DELETED. A product skill is part of what the agent is expected to be
//     able to do (searching, reading files, Jira, ...) and gating tools by name means deleting one
//     silently makes its tools unreachable - so those are editable but not deletable.
//
// Nothing is persisted here and nothing is keyed by id: this is derived state, rebuilt on every
// boot from whatever seeders the running deployment actually has.
//
// NAME IS THE WHOLE IDENTITY. A skill is a built-in exactly while its name matches one registered
// here; a skill whose name matches nothing is somebody's own, and is fully deletable no matter
// what its file says. That is what makes renaming a built-in a coherent act rather than a broken
// state: the renamed copy becomes the admin's own skill (deletable, no "Restore default"), and the
// next startup re-seeds the built-in under its own name. There is deliberately no alias/old-name
// table - a skill that no longer goes by a default's name is no longer that default.
@Component
public class DefaultSkillRegistry {

    public enum Origin {
        // Seeded by the product itself. Editable, never deletable.
        PRODUCT
    }

    // The seeded originals - what "Restore default" restores to.
    public record DefaultSkill(String name, String shortDescription, String detail, Origin origin) {}

    private final Map<String, DefaultSkill> byName = new ConcurrentHashMap<>();

    public void register(String name, String shortDescription, String detail, Origin origin) {
        if (name == null || name.isBlank()) return;
        byName.put(key(name), new DefaultSkill(name, shortDescription == null ? "" : shortDescription,
                detail == null ? "" : detail, origin));
    }

    public Optional<DefaultSkill> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(key(name)));
    }

    // Whether a skill going by this name is one of the product's own built-ins - the only case
    // that is not deletable. Everything else (a renamed built-in, a user's own, an official file
    // no seeder claims any more) is the admin's to remove.
    public boolean isProduct(String name) {
        return find(name).map(d -> d.origin() == Origin.PRODUCT).orElse(false);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).trim();
    }
}
