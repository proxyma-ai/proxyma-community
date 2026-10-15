package ai.proxyma.core.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every tool a built-in skill tells the assistant to call exists. The skills are prose, so a
 * renamed or removed tool leaves them naming something the model cannot call - and the Self Test
 * skill, whose whole job is to exercise every tool, would then report a working feature as
 * missing. Tool names are read off the {@code @Tool} methods on the classpath; a skill's references
 * are its backticked words that start the way tool names start.
 */
class SkillToolNamesTest {

    private static final Pattern BACKTICKED = Pattern.compile("`([a-z][A-Za-z]+)`");
    private static final Pattern TOOL_SHAPED = Pattern.compile(
            "^(get|list|search|create|update|delete|add|attach|move|register|remove|call|browse|read|run|"
                    + "convert|web|render|save|open|link|log|transition|merge|review|remember|recall|ask|"
                    + "delegate|use|download)([A-Z][A-Za-z]*)?$");
    // Tool-shaped words the skills use as PARAMETER names, not tools.
    private static final Set<String> PARAMETERS = Set.of("addLabels", "removeLabels", "link");

    @Test
    void everyToolASkillNamesExists() throws Exception {
        Set<String> tools = toolNames();
        assertTrue(tools.contains("searchIndexedSources") && tools.size() > 60,
                "the scan should find the whole tool catalog, found " + tools.size());

        Set<String> missing = new TreeSet<>();
        for (String[] skill : seededSkills()) {
            Matcher m = BACKTICKED.matcher(skill[1]);
            while (m.find()) {
                String word = m.group(1);
                if (TOOL_SHAPED.matcher(word).matches() && !PARAMETERS.contains(word) && !tools.contains(word)) {
                    missing.add(skill[0] + ": " + word);
                }
            }
        }
        assertTrue(missing.isEmpty(), "skills name tools that do not exist: " + missing);
    }

    @Test
    void theSelfTestSkillIsSeeded() throws Exception {
        assertTrue(seededSkills().stream().anyMatch(s -> s[0].equals("Self Test")));
    }

    private static Set<String> toolNames() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        Set<String> names = new HashSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("ai.proxyma.core")) {
            Class<?> type;
            try {
                type = Class.forName(bd.getBeanClassName(), false, SkillToolNamesTest.class.getClassLoader());
            } catch (Throwable e) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null) names.add(tool.name().isBlank() ? method.getName() : tool.name());
            }
        }
        return names;
    }

    // {name, detail} for every SeededSkill constant and list entry in DefaultChatSkillSeeder -
    // edition variants included, since each is what some edition actually installs.
    private static List<String[]> seededSkills() throws Exception {
        Class<?> seeder = Class.forName("ai.proxyma.core.chat.service.DefaultChatSkillSeeder");
        List<Object> skills = new ArrayList<>();
        for (Field f : seeder.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object value = f.get(null);
            if (value instanceof List<?> list) {
                for (Object o : list) if (o != null && o.getClass().isRecord()) skills.add(o);
            } else if (value != null && value.getClass().isRecord()
                    && value.getClass().getSimpleName().equals("SeededSkill")) {
                skills.add(value);
            }
        }
        List<String[]> out = new ArrayList<>();
        for (Object skill : skills) {
            Method name = skill.getClass().getDeclaredMethod("name");
            Method detail = skill.getClass().getDeclaredMethod("detail");
            name.setAccessible(true);
            detail.setAccessible(true);
            out.add(new String[]{(String) name.invoke(skill), (String) detail.invoke(skill)});
        }
        return out;
    }
}
