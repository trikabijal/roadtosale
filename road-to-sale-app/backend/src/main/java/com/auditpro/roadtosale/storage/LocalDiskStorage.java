package com.auditpro.roadtosale.storage;

import com.auditpro.roadtosale.config.RoadToSaleProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Writes uploaded files under {@code roadtosale.storage.local-dir}, namespaced by
 * session. Files are served back via the {@code /files/**} resource handler.
 */
@Service
public class LocalDiskStorage implements StorageService {

    private final Path baseDir;

    public LocalDiskStorage(RoadToSaleProperties props) {
        this.baseDir = Paths.get(props.getStorage().getLocalDir()).toAbsolutePath().normalize();
    }

    @Override
    public String store(String sessionId, String originalName, String contentType, InputStream content) {
        String ext = extensionOf(originalName);
        String fileName = UUID.randomUUID() + ext;
        // Storage key is relative; session segment is a UUID string, safe as a path part.
        String storageKey = sessionId + "/" + fileName;
        Path target = baseDir.resolve(storageKey).normalize();
        if (!target.startsWith(baseDir)) {
            throw new IllegalArgumentException("Resolved path escapes storage dir");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file", e);
        }
        return storageKey;
    }

    @Override
    public String urlFor(String storageKey) {
        // Encode each path segment but keep the slashes.
        String encoded = String.join("/",
                java.util.Arrays.stream(storageKey.split("/"))
                        .map(seg -> UriUtils.encodePathSegment(seg, StandardCharsets.UTF_8))
                        .toArray(String[]::new));
        return "/files/" + encoded;
    }

    private String extensionOf(String originalName) {
        if (originalName == null) {
            return "";
        }
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            return "";
        }
        String ext = originalName.substring(dot);
        // Keep only a safe, short alnum extension.
        return ext.matches("\\.[A-Za-z0-9]{1,10}") ? ext.toLowerCase() : "";
    }
}
