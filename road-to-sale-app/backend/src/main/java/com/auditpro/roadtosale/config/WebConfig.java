package com.auditpro.roadtosale.config;

import com.auditpro.roadtosale.security.CurrentUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Registers the {@code @CurrentUser} resolver and serves uploaded photos from the
 * local storage dir under {@code /files/**} (a stable, retrievable URL for the app).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final RoadToSaleProperties props;

    public WebConfig(CurrentUserArgumentResolver currentUserArgumentResolver, RoadToSaleProperties props) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
        this.props = props;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(props.getStorage().getLocalDir()).toAbsolutePath().normalize();
        // Trailing slash is required for directory resource locations.
        String location = "file:" + dir + "/";
        registry.addResourceHandler("/files/**")
                .addResourceLocations(location);
    }
}
