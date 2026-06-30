package com.auditpro.roadtosale;

import com.auditpro.roadtosale.config.RoadToSaleProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Road to Sale Core API — entry point.
 *
 * <p>Source of truth for the multi-tenant Road to Sale domain (dealerships,
 * users, sessions, session events, photos). The BFF is its only client.
 *
 * <p>Phase 1 scaffold only: controllers/services/repositories land in Phase 2.
 */
@SpringBootApplication
@EnableConfigurationProperties(RoadToSaleProperties.class)
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
