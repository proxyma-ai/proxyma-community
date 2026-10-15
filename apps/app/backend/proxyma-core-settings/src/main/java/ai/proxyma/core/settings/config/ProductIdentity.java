package ai.proxyma.core.settings.config;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

// Proxyma's own product identity. Deliberately NOT @ConfigurationProperties: this used to be a
// deploy-time-configurable tenant skin so a downstream repo could rebrand the whole application
// from env vars, which meant any deployment could claim to be some other product. There is one
// product now, and its name is compiled in.
//
// Lives in proxyma-core-settings so proxyma-core-chat - which depends on settings but not on
// system - can read productName when building the system prompt without a new module edge.
@Component
public class ProductIdentity {

    public static final String PRODUCT_NAME = "Proxyma";
    private static final String FAVICON_URL = "/favicon.svg";

    public String getProductName() {
        return PRODUCT_NAME;
    }

    public String getFaviconUrl() {
        return FAVICON_URL;
    }

    public Map<String, Object> toResponseMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("productName", PRODUCT_NAME);
        map.put("faviconUrl", FAVICON_URL);
        return map;
    }
}
