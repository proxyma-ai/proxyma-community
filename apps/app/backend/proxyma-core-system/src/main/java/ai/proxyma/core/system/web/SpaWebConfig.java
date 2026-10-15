package ai.proxyma.core.system.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.resource.PathResourceResolver;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

// Serves the bundled React SPA from classpath:/static/ and falls back to index.html for
// client-side routes - e.g. refreshing on /sources - that do not correspond to a real file.
//
// This lives here, in a module both runtimes depend on, rather than in proxyma-desktop, because
// a deployable unit has to serve its own UI. Before, only the desktop jar did; proxyma-server
// shipped an API with no UI and was only a working product because a reverse proxy on our own
// host happened to serve the SPA from a directory next to it. That made the server package
// undeliverable to anyone else - see deploy/INSTALL.md.
//
// @ConditionalOnResource so a jar built without a frontend (mvn -Dskip.frontend.build=true, or
// any module that has no SPA) simply does not register the handler, rather than registering a
// catch-all that resolves every unmatched path to a missing index.html.
@Configuration
@ConditionalOnResource(resources = "classpath:/static/index.html")
public class SpaWebConfig implements WebFluxConfigurer {

    // Prefixes that belong to the backend and must 404 on their own terms rather than silently
    // returning the HTML shell. oauth2/ and login/ are Spring Security's endpoints: they are
    // served by filters that run before handler mapping, so they would not reach this resolver
    // anyway - they are listed to keep the set readable as "everything that is not the SPA".
    private static final List<String> BACKEND_PREFIXES =
            List.of("api/", "mcp", "oauth2/", "login/", "actuator/", ".well-known/");

    // A year, which is the conventional way of writing "forever" in a max-age.
    private static final Duration ASSET_MAX_AGE = Duration.ofDays(365);

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Vite writes every JS and CSS bundle into /assets/ with a content hash in the FILENAME,
        // so a given URL under here can never change meaning: a new build produces new filenames
        // and index.html points at those instead. Caching them forever is therefore safe by
        // construction, and not caching them is expensive by construction.
        //
        // WHY THIS MATTERS MORE THAN IT LOOKS. Without a Cache-Control of our own, Spring
        // Security's default header writer stamps `no-cache, no-store, max-age=0, must-revalidate`
        // on EVERY response, hashed bundles included. A CDN in front of the app obeys that
        // faithfully - Cloudflare reported `cf-cache-status: BYPASS` on every asset request - so
        // every visitor pulled the entire bundle from the origin on every page load. On the
        // proxyma.ai demo, whose origin is a laptop behind a Cloudflare Tunnel, that was the
        // difference between 6ms from the host's own loopback and 3-5 SECONDS over the tunnel,
        // for one 392 KB file.
        //
        // Setting it here is what suppresses Security's version: StaticServerHttpHeadersWriter
        // adds its headers only when NONE of them is already present on the response, so one
        // Cache-Control from this handler takes Pragma and Expires with it.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(ASSET_MAX_AGE).cachePublic().immutable())
                .resourceChain(true);

        // Everything else, index.html above all, must NOT be cached: it is the one file whose URL
        // stays the same while its contents change, and it is what names the hashed bundles above.
        // A cached shell would go on asking for the previous build's assets after a deploy.
        //
        // The BARE "/" never reaches this handler - Spring Boot's own welcome-page mapping answers
        // it first - so that one response carries Security's `no-cache, no-store, max-age=0,
        // must-revalidate` instead of the `no-store` set here. Both mean uncacheable, which is why
        // this is a note and not a bug; verified by curl on a booted jar, path by path.
        //
        // Registered second, so /assets/** wins for anything under it - a missing file there now
        // 404s rather than falling through to the index.html shell below, which is the better
        // answer anyway: a <script> served an HTML document fails with a parse error naming the
        // wrong thing entirely.
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Mono<Resource> getResource(String resourcePath, Resource location) {
                        for (String prefix : BACKEND_PREFIXES) {
                            if (resourcePath.startsWith(prefix)) {
                                return Mono.empty();
                            }
                        }
                        return super.getResource(resourcePath, location)
                                .switchIfEmpty(Mono.fromSupplier(() -> new ClassPathResource("static/index.html")));
                    }
                });
    }
}
