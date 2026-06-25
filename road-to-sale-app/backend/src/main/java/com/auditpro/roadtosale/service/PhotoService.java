package com.auditpro.roadtosale.service;

import com.auditpro.roadtosale.domain.Photo;
import com.auditpro.roadtosale.domain.PhotoSlot;
import com.auditpro.roadtosale.domain.Session;
import com.auditpro.roadtosale.dto.PhotoDTO;
import com.auditpro.roadtosale.repo.PhotoRepository;
import com.auditpro.roadtosale.security.AuthenticatedUser;
import com.auditpro.roadtosale.storage.StorageService;
import com.auditpro.roadtosale.web.ApiException;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/** Trade-in photo upload + list + byte serving, tenant-scoped to the caller's dealership. */
@Service
public class PhotoService {

    /** Allowed image MIME types (B3). The client header is NOT trusted — we sniff. */
    private static final String JPEG = "image/jpeg";
    private static final String PNG = "image/png";
    private static final String WEBP = "image/webp";

    private final PhotoRepository photoRepository;
    private final StorageService storageService;
    private final SessionAccess sessionAccess;

    public PhotoService(PhotoRepository photoRepository,
                        StorageService storageService,
                        SessionAccess sessionAccess) {
        this.photoRepository = photoRepository;
        this.storageService = storageService;
        this.sessionAccess = sessionAccess;
    }

    @Transactional
    public PhotoDTO upload(AuthenticatedUser caller, UUID sessionId, PhotoSlot slot, MultipartFile file) {
        Session session = sessionAccess.requireOwnedSession(caller, sessionId);
        if (file == null || file.isEmpty()) {
            throw new ApiException.BadRequest("file is required");
        }
        if (slot == null) {
            throw new ApiException.BadRequest("slot is required");
        }

        byte[] head = readHead(file);
        // B3: validate by sniffing magic bytes — do not trust file.getContentType().
        String sniffedType = sniffImageType(head);
        if (sniffedType == null) {
            throw new ApiException.BadRequest("Unsupported file type: only JPEG, PNG and WebP images are allowed");
        }

        String storageKey;
        try {
            storageKey = storageService.store(
                    session.getId().toString(), file.getOriginalFilename(), sniffedType, file.getInputStream());
        } catch (IOException e) {
            throw new ApiException.BadRequest("Could not read uploaded file");
        }

        Photo photo = new Photo();
        photo.setSessionId(session.getId());
        photo.setSlot(slot);
        photo.setStoragePath(storageKey);
        // Store the sniffed/validated type, never the client-supplied header.
        photo.setMimeType(sniffedType);
        Photo saved = photoRepository.save(photo);
        return PhotoDTO.from(saved, contentPath(saved));
    }

    @Transactional(readOnly = true)
    public List<PhotoDTO> list(AuthenticatedUser caller, UUID sessionId) {
        sessionAccess.requireOwnedSession(caller, sessionId);
        return photoRepository.findBySessionIdOrderByUploadedAtAsc(sessionId).stream()
                .map(p -> PhotoDTO.from(p, contentPath(p)))
                .toList();
    }

    /**
     * Load one photo's bytes for the authed, tenant-scoped content endpoint (B4).
     * The session is tenant-checked first (cross-tenant -> 404), then the photo
     * must belong to that session (-> 404 otherwise).
     */
    @Transactional(readOnly = true)
    public PhotoContent content(AuthenticatedUser caller, UUID sessionId, UUID photoId) {
        sessionAccess.requireOwnedSession(caller, sessionId);
        Photo photo = photoRepository.findByIdAndSessionId(photoId, sessionId)
                .orElseThrow(() -> new ApiException.NotFound("Photo not found"));
        Resource resource = storageService.load(photo.getStoragePath());
        return new PhotoContent(resource, photo.getMimeType());
    }

    /**
     * BFF-relative logical path for retrieving the bytes. The app reaches it
     * through the BFF, so this is NOT prefixed with {@code /api/v1} or {@code /files}.
     */
    private String contentPath(Photo photo) {
        return "/sessions/" + photo.getSessionId() + "/photos/" + photo.getId() + "/content";
    }

    /** Read the first bytes for magic-byte sniffing without consuming the upload stream. */
    private byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(16);
        } catch (IOException e) {
            throw new ApiException.BadRequest("Could not read uploaded file");
        }
    }

    /**
     * Return the canonical MIME type if the bytes are a supported image, else null.
     * JPEG: FF D8 FF; PNG: 89 50 4E 47 0D 0A 1A 0A; WebP: "RIFF"...."WEBP".
     */
    private String sniffImageType(byte[] b) {
        if (b == null) {
            return null;
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        if (b.length >= 8
                && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A) {
            return PNG;
        }
        if (b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return WEBP;
        }
        return null;
    }

    /** Resolved photo bytes + the validated content-type to serve them with. */
    public record PhotoContent(Resource resource, String contentType) {
    }
}
