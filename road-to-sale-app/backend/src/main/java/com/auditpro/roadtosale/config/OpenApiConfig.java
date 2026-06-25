package com.auditpro.roadtosale.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the Bearer JWT scheme so Swagger UI can authorize protected routes. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI roadToSaleOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Road to Sale — Core API")
                        .description("Internal Core API (source of truth). Consumed by the BFF.")
                        .version("1.0.0"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
