package com.auditpro.roadtosale.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Runs the idempotent seed on startup under the {@code dev} profile only. */
@Component
@Profile("dev")
public class DevSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeedRunner.class);

    private final SeedService seedService;

    public DevSeedRunner(SeedService seedService) {
        this.seedService = seedService;
    }

    @Override
    public void run(String... args) {
        seedService.seed();
        // Never log the seed password (N1) — credentials are documented in SeedService.
        log.info("Dev seed applied: dealerships [{}, {}], users [rep1, rep2]",
                SeedService.FREMONT, SeedService.OAKLAND);
    }
}
