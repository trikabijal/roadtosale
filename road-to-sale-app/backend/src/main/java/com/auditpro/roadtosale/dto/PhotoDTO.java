package com.auditpro.roadtosale.dto;

import com.auditpro.roadtosale.domain.Photo;
import com.auditpro.roadtosale.domain.PhotoSlot;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * App-facing photo shape. {@code fileUrl} is the BFF-relative logical path
 * {@code /sessions/{sessionId}/photos/{photoId}/content} (not /files, not /api/v1);
 * the app retrieves the bytes through the BFF.
 */
public record PhotoDTO(
        UUID id,
        UUID sessionId,
        PhotoSlot slot,
        String fileUrl,
        OffsetDateTime uploadedAt) {

    public static PhotoDTO from(Photo p, String fileUrl) {
        return new PhotoDTO(p.getId(), p.getSessionId(), p.getSlot(), fileUrl, p.getUploadedAt());
    }
}
