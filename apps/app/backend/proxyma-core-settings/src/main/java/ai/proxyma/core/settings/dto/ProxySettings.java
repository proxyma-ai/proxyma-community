package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProxySettings {

    public static final String PREFIX = "proxy";

    public enum Mode { none, system, custom }

    @JsonProperty("mode")
    private String mode = Mode.none.name();

    @JsonProperty("http-proxy")
    private String httpProxy;

    @JsonProperty("https-proxy")
    private String httpsProxy;

    @JsonProperty("no-proxy")
    private String noProxy;

    /**
     * Extra CA certificate(s) to trust, PEM-encoded, concatenated if more than one.
     *
     * Exists because a TLS-inspecting corporate proxy terminates every outbound connection and
     * re-signs it with its OWN certificate authority - one the public truststore has never heard
     * of, so every LLM call, license activation and connector sync fails handshake. Pasting that
     * proxy's CA here makes those connections verify properly against it.
     *
     * It replaces what this deployment did before, which was to disable certificate validation
     * outright for ALL outbound HTTPS - see RestClientConfig. That did make the corporate-proxy
     * case work, and it also meant anyone on-path could present any certificate and be believed.
     *
     * Empty (the default) means the system truststore alone, with full validation. This is
     * ADDITIVE: the public roots are always kept, so setting a CA to reach one internal host does
     * not stop the public internet from verifying.
     */
    @JsonProperty("ca-certificate")
    private String caCertificate;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getHttpProxy() { return httpProxy; }
    public void setHttpProxy(String httpProxy) { this.httpProxy = httpProxy; }

    public String getHttpsProxy() { return httpsProxy; }
    public void setHttpsProxy(String httpsProxy) { this.httpsProxy = httpsProxy; }

    public String getNoProxy() { return noProxy; }
    public void setNoProxy(String noProxy) { this.noProxy = noProxy; }

    public String getCaCertificate() { return caCertificate; }
    public void setCaCertificate(String caCertificate) { this.caCertificate = caCertificate; }

    @JsonIgnore
    public Mode getResolvedMode() {
        try {
            return Mode.valueOf(mode);
        } catch (Exception e) {
            return Mode.none;
        }
    }
}
