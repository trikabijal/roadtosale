package com.auditpro.roadtosale.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for all {@code roadtosale.*} configuration keys (see application.yml).
 *
 * <p>Single source of truth for the outcome threshold, JWT settings, and storage dir.
 */
@ConfigurationProperties(prefix = "roadtosale")
public class RoadToSaleProperties {

    private final Outcome outcome = new Outcome();
    private final Auth auth = new Auth();
    private final Storage storage = new Storage();

    public Outcome getOutcome() {
        return outcome;
    }

    public Auth getAuth() {
        return auth;
    }

    public Storage getStorage() {
        return storage;
    }

    /** Outcome derivation settings (PRD §7 #18). */
    public static class Outcome {
        /** A question is "satisfied" when its highest-confidence event is >= this. */
        private double confidenceThreshold = 0.6;

        public double getConfidenceThreshold() {
            return confidenceThreshold;
        }

        public void setConfidenceThreshold(double confidenceThreshold) {
            this.confidenceThreshold = confidenceThreshold;
        }
    }

    /** JWT auth settings (PRD §7 #1-5). */
    public static class Auth {
        private String jwtSecret;
        private long accessTokenTtlSeconds = 3600;
        private long refreshTokenTtlSeconds = 2592000;

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }

        public long getAccessTokenTtlSeconds() {
            return accessTokenTtlSeconds;
        }

        public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
            this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        }

        public long getRefreshTokenTtlSeconds() {
            return refreshTokenTtlSeconds;
        }

        public void setRefreshTokenTtlSeconds(long refreshTokenTtlSeconds) {
            this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
        }
    }

    /** Local-disk photo storage (PRD §4.1; S3 is the later swap). */
    public static class Storage {
        private String localDir = "./data/photos";

        public String getLocalDir() {
            return localDir;
        }

        public void setLocalDir(String localDir) {
            this.localDir = localDir;
        }
    }
}
