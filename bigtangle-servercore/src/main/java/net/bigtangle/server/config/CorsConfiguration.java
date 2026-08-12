/*******************************************************************************
 *  Copyright   2026  Inasset GmbH.
 *
 *******************************************************************************/
package net.bigtangle.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Enables cross-origin requests (CORS) so browser clients — e.g. the web build
 * of the mobile app served from another origin — can call the L0/L1 JSON-RPC
 * endpoints. Used by test/dev infra; allows all origins.
 */
@Configuration
public class CorsConfiguration implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
