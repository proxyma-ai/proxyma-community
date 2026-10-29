package ai.proxyma.core.tools.office;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Home Server and Enterprise Server: the sandbox container (apps/app/deploy/sandbox), reached over a
 * compose network with no route out. One request per run - the code and the input files in, the
 * outputs back - so the container needs no volume and never sees the app's data.
 *
 * <p>The bearer token is a second lock, not the first: only the app is on that network. It exists so
 * a misconfigured network that did expose the port would still not be an open code runner.
 */
public class ContainerPythonSandbox implements PythonSandbox {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI runUri;
    private final URI shellUri;
    private final String token;
    // NO PROXY, ever: the container is on the compose network, which a corporate proxy cannot reach.
    // Left to the JVM default, the client followed http.proxyHost - set whenever Settings > Network
    // has a proxy - and sent the code, every attachment and the token to the proxy, and the run failed.
    private final HttpClient http = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public ContainerPythonSandbox(String baseUrl, String token) {
        this.runUri = URI.create(baseUrl.replaceAll("/+$", "") + "/run");
        this.shellUri = URI.create(baseUrl.replaceAll("/+$", "") + "/shell");
        this.token = token == null ? "" : token;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Result run(String code, Map<String, byte[]> inputs, Duration timeout) throws IOException {
        return post(runUri, "code", code, inputs, timeout);
    }

    /** runCommand on a server: the container's /shell, as its /run - no network, no volume, as nobody. */
    @Override
    public Result shell(String command, Map<String, byte[]> inputs, Duration timeout) throws IOException {
        return post(shellUri, "command", command, inputs, timeout);
    }

    private Result post(URI uri, String field, String text, Map<String, byte[]> inputs, Duration timeout)
            throws IOException {
        ObjectNode body = JSON.createObjectNode();
        body.put(field, text);
        body.put("timeoutSeconds", timeout.toSeconds());
        ObjectNode files = body.putObject("files");
        inputs.forEach((name, bytes) -> files.put(name, Base64.getEncoder().encodeToString(bytes)));

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout.plusSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the sandbox");
        } catch (java.net.ConnectException e) {
            // The container is not there - the `office` compose profile is off, or it failed to build.
            throw new IOException("The Office Files sandbox is not running on this server. An administrator "
                    + "enables it with the `office` compose profile; tell the user, do not retry.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("The sandbox answered " + response.statusCode());
        }
        JsonNode r = JSON.readTree(response.body());
        Map<String, byte[]> outputs = new LinkedHashMap<>();
        r.path("files").properties().forEach(e -> outputs.put(e.getKey(), Base64.getDecoder().decode(e.getValue().asText())));
        return new Result(r.path("exitCode").asInt(-1),
                Limits.tail(r.path("stdout").asText("")),
                Limits.tail(r.path("stderr").asText("")),
                r.path("timedOut").asBoolean(false),
                outputs);
    }
}
