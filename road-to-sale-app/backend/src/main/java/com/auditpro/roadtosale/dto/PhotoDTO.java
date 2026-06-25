package com.auditpro.roadtosale.dto;

import com.auditpro.roadtosale.domain.Photo;
import com.auditpro.roadtosale.domain.PhotoSlot;

import java.time.OffsetDateTime;
import java.util.UUID;

/** App-facing photo shape with a retrievable {@code fileUrl}. */
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
