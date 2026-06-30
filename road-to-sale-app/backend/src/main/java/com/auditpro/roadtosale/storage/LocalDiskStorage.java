package com.auditpro.roadtosale.storage;

import com.auditpro.roadtosale.config.RoadToSaleProperties;
import com.auditpro.roadtosale.web.ApiException;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Writes uploaded files under {@code roadtosale.storage.local-dir}, namespaced by
 * session. Bytes are read back only through the authed, tenant-scoped photo
 * content endpoint (never a public static handler).
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
    public Resource load(String storageKey) {
        Path target = baseDir.resolve(storageKey).normalize();
        // Defence in depth: a stored key must never escape the storage root.
        if (!target.startsWith(baseDir)) {
            throw new IllegalArgumentException("Resolved path escapes storage dir");
        }
        if (!Files.isReadable(target)) {
            throw new ApiException.NotFound("Photo not found");
        }
        return new PathResource(target);
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
