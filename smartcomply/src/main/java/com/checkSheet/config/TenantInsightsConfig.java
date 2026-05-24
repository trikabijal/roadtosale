package com.checkSheet.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads tenant-specific BI insight definitions from
 * <code>${tenant.config.path}/_base/config/insights.yaml</code> and merges
 * the chosen tenant's overrides on top, layered base + diff style.
 *
 * <p>The configuration model:
 * <ul>
 *   <li>{@code tenants/_schema.json} declares which files are overridable
 *       per tenant. Currently only {@code config/insights.yaml}.</li>
 *   <li>{@code tenants/_base/config/insights.yaml} is the COMPLETE generic
 *       AuditPro identity — for the backend that means an empty
 *       {@code correlations:} list (no tenant-specific insights).</li>
 *   <li>{@code tenants/&lt;id&gt;/config/insights.yaml} contains the
 *       tenant's correlation pairs. They are appended to the base list.</li>
 * </ul>
 *
 * <p>Validation:
 * <ul>
 *   <li>Base must exist and parse (else app fails to start — base is the
 *       contract).</li>
 *   <li>Each correlation entry (in either base or tenant) must declare
 *       {@code id}, {@code message}, and a non-null {@code left}/{@code right}
 *       with at least an {@code elementPattern}. Missing fields fail startup
 *       with a clear pointer at the offending file + correlation id.</li>
 *   <li>Tenant folder is checked strictly against the schema — any file not
 *       declared in {@code _schema.json} fails startup.</li>
 * </ul>
 *
 * <p>If the chosen tenant doesn't ship its own {@code insights.yaml}, the
 * base is used as-is. If no tenant is configured ({@code tenant.id} unset
 * or set to {@code _base}), only the base list is used.
 */
@Slf4j
@Component
public class TenantInsightsConfig {

    @Value("${tenant.id:kia}")
    private String tenantId;

    @Value("${tenant.config.path:./tenants}")
    private String tenantConfigPath;

    private List<Correlation> correlations = Collections.emptyList();

    @PostConstruct
    public void load() {
        Path baseDir   = Path.of(tenantConfigPath, "_base");
        Path tenantDir = Path.of(tenantConfigPath, tenantId);

        // Schema lookup — declared overridable file paths (relative to tenant dir).
        Set<String> declaredPaths = loadSchemaDeclaredPaths();

        // Phase 1 — base must exist and validate.
        Path baseInsights = baseDir.resolve("config/insights.yaml");
        if (!Files.exists(baseInsights)) {
            throw new IllegalStateException(
                "Tenant _base is incomplete: missing " + baseInsights +
                " (every overridable file declared in tenants/_schema.json must exist in _base).");
        }
        List<Correlation> merged = new ArrayList<>(parseAndValidate(baseInsights, "_base"));

        // Phase 2 — tenant overlay (optional except for "_base" / "default" / unset).
        boolean applyTenant = tenantId != null
                && !tenantId.isBlank()
                && !"_base".equals(tenantId)
                && !"default".equals(tenantId);
        if (applyTenant) {
            if (!Files.exists(tenantDir)) {
                throw new IllegalStateException(
                    "Tenant '" + tenantId + "' configured but tenants/" + tenantId + " does not exist.");
            }
            // Strict: every file under tenantDir must be declared in the schema.
            try (var stream = Files.walk(tenantDir)) {
                List<String> undeclared = stream
                    .filter(Files::isRegularFile)
                    .map(p -> tenantDir.relativize(p).toString().replace('\\', '/'))
                    .filter(rel -> !declaredPaths.contains(rel))
                    .collect(Collectors.toList());
                if (!undeclared.isEmpty()) {
                    throw new IllegalStateException(
                        "Tenant '" + tenantId + "' contains files not declared in tenants/_schema.json:\n   " +
                        String.join("\n   ", undeclared) +
                        "\nEither remove them or add to the schema.");
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Failed to scan tenants/" + tenantId, e);
            }

            Path tenantInsights = tenantDir.resolve("config/insights.yaml");
            if (Files.exists(tenantInsights)) {
                merged.addAll(parseAndValidate(tenantInsights, tenantId));
            } else {
                log.info("Tenant '{}' has no config/insights.yaml — using base only.", tenantId);
            }
        }

        this.correlations = List.copyOf(merged);
        log.info("Loaded {} BI insight correlation(s) for tenant '{}'", correlations.size(), tenantId);
    }

    public List<Correlation> getCorrelations() { return correlations; }

    public String getTenantId() { return tenantId; }

    @SuppressWarnings("unchecked")
    private Set<String> loadSchemaDeclaredPaths() {
        Path schema = Path.of(tenantConfigPath, "_schema.json");
        if (!Files.exists(schema)) {
            log.warn("Tenant schema not found at {} — strict-mode checks skipped.", schema);
            return new HashSet<>();
        }
        try {
            Map<String, Object> root = new ObjectMapper().readValue(schema.toFile(), Map.class);
            Set<String> paths = new HashSet<>();
            for (String key : new String[] {"overridable", "tenantOnly"}) {
                Object o = root.get(key);
                if (!(o instanceof List<?> list)) continue;
                list.stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (String) ((Map<?, ?>) m).get("path"))
                    .filter(java.util.Objects::nonNull)
                    .forEach(paths::add);
            }
            return paths;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse " + schema, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Correlation> parseAndValidate(Path file, String label) {
        try (InputStream in = new FileInputStream(file.toFile())) {
            Map<String, Object> root = new Yaml().load(in);
            if (root == null) return Collections.emptyList();
            Object raw = root.get("correlations");
            if (raw == null) return Collections.emptyList();
            if (!(raw instanceof List<?> list)) {
                throw new IllegalStateException(file + ": 'correlations' must be a list.");
            }
            List<Correlation> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (!(item instanceof Map<?, ?> m)) {
                    throw new IllegalStateException(
                        file + ": correlations[" + i + "] is not an object.");
                }
                Correlation c = new Correlation();
                c.id      = strOrNull(m.get("id"));
                c.message = strOrNull(m.get("message"));
                c.left    = parseSide(m.get("left"));
                c.right   = parseSide(m.get("right"));

                String prefix = file + " (tenant=" + label + "), correlations[" + i + "]";
                if (c.id == null || c.id.isBlank()) {
                    throw new IllegalStateException(prefix + ": 'id' is required.");
                }
                if (c.message == null || c.message.isBlank()) {
                    throw new IllegalStateException(prefix + " '" + c.id + "': 'message' is required.");
                }
                if (c.left == null || c.left.elementPattern == null) {
                    throw new IllegalStateException(prefix + " '" + c.id + "': 'left.elementPattern' is required.");
                }
                if (c.right == null || c.right.elementPattern == null) {
                    throw new IllegalStateException(prefix + " '" + c.id + "': 'right.elementPattern' is required.");
                }
                out.add(c);
            }
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + file, e);
        }
    }

    private static Side parseSide(Object o) {
        if (!(o instanceof Map<?, ?> m)) return null;
        Side s = new Side();
        s.elementPattern  = orWildcard(m.get("elementPattern"), false);
        s.questionPattern = orWildcard(m.get("questionPattern"), true);
        return s;
    }

    private static String strOrNull(Object o) {
        return o == null ? null : o.toString();
    }

    /** {@code defaultToWildcard=true} returns {@code "%"} for missing
     *  values (a side that doesn't filter on question name). With
     *  {@code false} we return {@code null} so the caller can detect
     *  "missing required field" and fail fast. */
    private static String orWildcard(Object o, boolean defaultToWildcard) {
        String s = o == null ? "" : o.toString().trim();
        if (s.isEmpty()) return defaultToWildcard ? "%" : null;
        return s;
    }

    @Data
    public static class Correlation {
        public String id;
        public Side left;
        public Side right;
        /** Message template — supports {pct} placeholder for the computed
         *  co-occurrence percentage. */
        public String message;
    }

    @Data
    public static class Side {
        public String elementPattern;
        public String questionPattern;
    }
}
