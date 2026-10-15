package ai.proxyma.core.tools;

import org.springframework.ai.tool.execution.ToolCallResultConverter;

import java.lang.reflect.Type;

// Passthrough tool-result converter: returns the tool's String result verbatim instead of
// JSON-serializing it. The default converter (DefaultToolCallResultConverter) wraps every result
// through Jackson, which quotes the string and escapes newlines to "\n" - fine for structured
// results, but for the Read/Grep tools (whose whole output is human-readable multi-line text) that
// double-escapes content that is already text and collapses it to one hard-to-read line. Used via
// @Tool(resultConverter = RawTextResultConverter.class). Must have a public no-arg constructor:
// Spring AI instantiates the converter class by reflection, not as a Spring bean.
public class RawTextResultConverter implements ToolCallResultConverter {

    @Override
    public String convert(Object result, Type returnType) {
        return result == null ? "" : result.toString();
    }
}
