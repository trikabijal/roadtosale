package com.checkSheet.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.util.HashSet;
import java.util.Set;

@Component
public class EndpointRegistry {
    private final Set<String> registeredEndpoints = new HashSet<>();
    
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    
    @Autowired
    private ApiEndpointConfig apiEndpointConfig;

    @PostConstruct
    public void init() {
        // Add all controller endpoints
        handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
            if (mapping.getPatternsCondition() != null) {
                mapping.getPatternsCondition().getPatterns()
                    .forEach(registeredEndpoints::add);
            }
        });

        
        // Add role-based endpoints
        registeredEndpoints.addAll(apiEndpointConfig.endpointRoleMap().keySet());
        
        // Add whitelisted URLs
        registeredEndpoints.addAll(java.util.Arrays.asList(SecurityConfiguration.WHITE_LIST_URL));
    }

    public boolean isEndpointExists(String requestURI) {
        // Direct match
        if (registeredEndpoints.contains(requestURI)) {
            return true;
        }

        // Pattern match for dynamic endpoints
        return registeredEndpoints.stream().anyMatch(pattern ->
            matchesPattern(pattern, requestURI)
        );
    }

    private boolean matchesPattern(String pattern, String uri) {
        if (!pattern.contains("{") && !pattern.contains("*")) {
            return pattern.equals(uri);
        }
        
        String regex = pattern
            .replaceAll("\\{[^}]+\\}", "[^/]+")
            .replace("**", ".*")
            .replace("*", "[^/]*")
            .replace("/", "\\/");
            
        return uri.matches(regex);
    }
} 