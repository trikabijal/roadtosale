package com.auditpro.roadtosale.config;

import com.auditpro.roadtosale.security.CurrentUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Registers the {@code @CurrentUser} resolver.
 *
 * <p>Photo bytes are NOT served as public static resources (that was an IDOR —
 * any path under the storage dir was world-readable). They are served only via
 * the authed, tenant-scoped {@code GET /api/v1/sessions/{id}/photos/{photoId}/content}
 * endpoint on {@link com.auditpro.roadtosale.controller.PhotoController}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    public WebConfig(CurrentUserArgumentResolver currentUserArgumentResolver) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }
}
