package ai.proxyma.core.tools.mcpclient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A local MCP server for {@link McpClientTransportsTest}: started as a child process over stdio, by
 * the same transport a user's local server is. No dependencies, so its class path is one directory
 * and its command line stays short on Windows.
 *
 * <p>Two tools: {@code echo} returns its text, {@code environment} the names of every environment
 * variable the process was started with and the value of FAKE_MCP_GREETING - which is how the test
 * sees that the client hands a local server a clean environment.
 */
public final class FakeStdioMcpServer {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|\\d+)");
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TEXT = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)\"");

    private static final Pattern VERSION = Pattern.compile("\"protocolVersion\"\\s*:\\s*\"([^\"]+)\"");

    private FakeStdioMcpServer() {}

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.err.println("fake stdio server ready");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            Matcher id = ID.matcher(line);
            Matcher method = METHOD.matcher(line);
            if (!id.find() || !method.find()) continue;   // a notification
            String result = switch (method.group(1)) {
                // The version the client asked for, as a real server answers with one it supports.
                case "initialize" -> "{\"protocolVersion\":\"" + version(line) + "\",\"capabilities\":{\"tools\":{}},"
                        + "\"serverInfo\":{\"name\":\"fake-stdio\",\"version\":\"1\"},\"instructions\":\"Local test server.\"}";
                case "tools/list" -> "{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":"
                        + "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}},"
                        + "{\"name\":\"environment\",\"description\":\"Env\",\"inputSchema\":{\"type\":\"object\"}}]}";
                case "tools/call" -> call(line);
                default -> "{}";
            };
            out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + ",\"result\":" + result + "}");
        }
    }

    private static String version(String line) {
        Matcher v = VERSION.matcher(line);
        return v.find() ? v.group(1) : "2024-11-05";
    }

    private static String call(String line) {
        Matcher params = Pattern.compile("\"params\"\\s*:\\s*(\\{.*)").matcher(line);
        String p = params.find() ? params.group(1) : "";
        Matcher name = NAME.matcher(p);
        String tool = name.find() ? name.group(1) : "";
        if (tool.equals("echo")) {
            Matcher text = TEXT.matcher(p);
            return content(text.find() ? text.group(1) : "");
        }
        String names = System.getenv().keySet().stream().sorted().collect(Collectors.joining(","));
        String greeting = System.getenv("FAKE_MCP_GREETING");
        return content("greeting=" + greeting + ";names=" + names);
    }

    private static String content(String text) {
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}]}";
    }
}
