package com.auditpro.roadtosale.storage;

import java.io.InputStream;

/**
 * Storage abstraction for uploaded files. Local disk now; S3 is the likely later
 * swap behind this same interface (PRD §4.1).
 */
public interface StorageService {

    /**
     * Persist a file and return an opaque storage key (relative path) used to
     * locate it later and to build its public URL.
     *
     * @param sessionId    the owning session (used to namespace the stored file)
     * @param originalName the client filename (for extension only; not trusted as a path)
     * @param contentType  MIME type
     * @param content      the bytes
     * @return storage key, e.g. {@code <sessionId>/<uuid>.jpg}
     */
    String store(String sessionId, String originalName, String contentType, InputStream content);

    /** Build a retrievable URL for a stored key (served under {@code /files/**}). */
    String urlFor(String storageKey);
}
