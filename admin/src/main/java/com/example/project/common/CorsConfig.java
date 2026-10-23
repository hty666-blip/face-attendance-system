package com.example.project.common;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.*;
import org.springframework.web.filter.CorsFilter;
@Configuration
public class CorsConfig {
    @Value("${app.cors.allowed-origins:http://localhost:7000,http://127.0.0.1:7000}")
    private String origins;
    @Bean public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        for (String origin : origins.split(",")) config.addAllowedOrigin(origin.trim());
        config.addAllowedHeader("Authorization"); config.addAllowedHeader("Content-Type");
        config.setAllowedMethods(java.util.Arrays.asList("GET","POST","PUT","DELETE","OPTIONS"));
        config.addExposedHeader("Content-Disposition"); config.setMaxAge(1800L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**",config);
        return new CorsFilter(source);
    }
}
