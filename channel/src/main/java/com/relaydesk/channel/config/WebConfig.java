package com.relaydesk.channel.config;

import com.relaydesk.channel.web.CurrentUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Web MVC wiring: registers the {@code @CurrentUser} argument resolver and the desk CORS policy. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }

    /** Desk pages are embedded in partner operations portals, so their origins are trusted. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/desk/**")
                .allowedOriginPatterns("*")
                .allowCredentials(true)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
