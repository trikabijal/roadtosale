package com.checkSheet.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Loads tenant feature flags from
 * {@code ${tenant.config.path}/<tenant.id>/config/features.yaml}, falling
 * back to {@code _base/config/features.yaml} when the tenant doesn't ship
 * its own. Both files are validated against {@code tenants/_schema.json}.
 *
 * <p>Two flags today (PRD §5):
 * <ul>
 *   <li>{@code actionPlanRequired} — when true, an approved audit with a
 *       P1 non-compliant item auto-creates a Mandatory Plan even if no
 *       campaign covers it. When false, plans only exist via campaigns.</li>
 *   <li>{@code scoreDisplayMode} — {@code CHAIN} shows the original +
 *       each re-audit snapshot side by side; {@code CURRENT_ONLY} shows
 *       only the most-recent score.</li>
 * </ul>
 *
 * <p>Adding a flag is a three-step change: bump
 * {@link #DEFAULT_*} below, add a getter, document it in both
 * {@code _base/features.yaml} and (if non-default) any tenant override.
 */
@Slf4j
@Component
@Getter
public class TenantFeatureFlags {

    public enum ScoreDisplayMode { CHAIN, CURRENT_ONLY }

    /** Conservative defaults used when neither tenant nor base file is parseable
     *  — paranoid only, since base is required to exist by the schema validator. */
    private static final boolean          DEFAULT_ACTION_PLAN_REQUIRED = false;
    private static final ScoreDisplayMode DEFAULT_SCORE_DISPLAY_MODE   = ScoreDisplayMode.CHAIN;

    private static final Set<String> ALLOWED_KEYS = Set.of("actionPlanRequired", "scoreDisplayMode");

    @Value("${tenant.id:kia}")
    private String tenantId;

    @Value("${tenant.config.path:./tenants}")
    private String tenantConfigPath;

    private boolean actionPlanRequired = DEFAULT_ACTION_PLAN_REQUIRED;
    private ScoreDisplayMode scoreDisplayMode = DEFAULT_SCORE_DISPLAY_MODE;

    @PostConstruct
    public void load() {
        Path baseFile   = Path.of(tenantConfigPath, "_base", "config", "features.yaml");
        Path tenantFile = Path.of(tenantConfigPath, tenantId, "config", "features.yaml");

        // Base must exist — the schema declares it requiredInBase=true.
        if (!Files.exists(baseFile)) {
            throw new IllegalStateException(
                "Tenant _base is incomplete: missing " + baseFile +
                ". Every overridable file declared in tenants/_schema.json must exist in _base."
            );
        }

        Map<String, Object> base = parse(baseFile);
        validateKeys(base, baseFile);
        applyMap(base);

        // Tenant override layered on top — only the keys present override.
        if (Files.exists(tenantFile)) {
            Map<String, Object> tenant = parse(tenantFile);
            validateKeys(tenant, tenantFile);
            applyMap(tenant);
        } else {
            log.info("No tenant features.yaml at {} — falling back to _base values for tenant '{}'.",
                tenantFile, tenantId);
        }

        log.info("Loaded tenant feature flags for '{}': actionPlanRequired={}, scoreDisplayMode={}",
            tenantId, actionPlanRequired, scoreDisplayMode);
    }

    private Map<String, Object> parse(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> parsed = new Yaml().load(in);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse " + file + ": " + e.getMessage(), e);
        }
    }

    private void validateKeys(Map<String, Object> map, Path file) {
        for (String key : map.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalStateException(
                    file + " contains unknown feature flag '" + key + "'. " +
                    "Allowed keys: " + ALLOWED_KEYS
                );
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyMap(Map<String, Object> map) {
        if (map.containsKey("actionPlanRequired")) {
            Object v = map.get("actionPlanRequired");
            if (!(v instanceof Boolean)) {
                throw new IllegalStateException(
                    "actionPlanRequired must be true|false, got: " + v + " (" + (v == null ? "null" : v.getClass().getSimpleName()) + ")"
                );
            }
            this.actionPlanRequired = (Boolean) v;
        }
        if (map.containsKey("scoreDisplayMode")) {
            Object v = map.get("scoreDisplayMode");
            if (!(v instanceof String s)) {
                throw new IllegalStateException("scoreDisplayMode must be a string");
            }
            try {
                this.scoreDisplayMode = ScoreDisplayMode.valueOf(s);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                    "scoreDisplayMode must be one of " + Set.of(ScoreDisplayMode.values()) + ", got: " + s
                );
            }
        }
    }
}
