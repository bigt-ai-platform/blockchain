/*******************************************************************************
 *  Copyright   2026  Inasset GmbH.
 *
 *******************************************************************************/
package net.bigtangle.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Optional cross-origin (CORS) support for browser clients — e.g. the web
 * build of the mobile app served from another origin.
 *
 * <p><b>Disabled by default.</b> The JSON-RPC endpoints are unauthenticated
 * for reads and mostly rate-unlimited; an open {@code Access-Control-Allow-Origin: *}
 * would let any website drive calls against a node from a user's browser.
 * Enable explicitly with a comma-separated list of trusted origins:
 *
 * <pre>server.corsAllowedOrigins=https://app.bigtangle.org,https://test.bigtangle.org</pre>
 */
@Configuration
public class CorsConfiguration implements WebMvcConfigurer {

    @Value("${server.corsAllowedOrigins:}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return;
        }
        String[] origins = allowedOrigins.split(",");
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
