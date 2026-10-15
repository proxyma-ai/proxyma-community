package ai.proxyma.desktop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

// Desktop is single-user with no auth (see pom.xml description). spring-boot-starter-security
// only reaches this module transitively (proxyma-core-settings, for BCryptPasswordEncoder), but
// its mere presence makes Spring Boot auto-configure a default HTTP Basic login with a randomly
// generated password unless an explicit SecurityWebFilterChain backs that auto-configuration off.
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange.anyExchange().permitAll())
                .build();
    }
}
