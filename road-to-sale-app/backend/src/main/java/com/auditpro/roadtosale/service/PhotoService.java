package com.auditpro.roadtosale.service;

import com.auditpro.roadtosale.domain.Photo;
import com.auditpro.roadtosale.domain.PhotoSlot;
import com.auditpro.roadtosale.domain.Session;
import com.auditpro.roadtosale.dto.PhotoDTO;
import com.auditpro.roadtosale.repo.PhotoRepository;
import com.auditpro.roadtosale.repo.SessionRepository;
import com.auditpro.roadtosale.security.AuthenticatedUser;
import com.auditpro.roadtosale.storage.StorageService;
import com.auditpro.roadtosale.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Trade-in photo upload + list, tenant-scoped to the caller's dealership. */
@Service
public class PhotoService {

    private final PhotoRepository photoRepository;
    private final SessionRepository sessionRepository;
    private final StorageService storageService;

    public PhotoService(PhotoRepository photoRepository,
                        SessionRepository sessionRepository,
                        StorageService storageService) {
        this.photoRepository = photoRepository;
        this.sessionRepository = sessionRepository;
        this.storageService = storageService;
    }

    @Transactional
    public PhotoDTO upload(AuthenticatedUser caller, UUID sessionId, PhotoSlot slot, MultipartFile file) {
        Session session = requireOwnedSession(caller, sessionId);
        if (file == null || file.isEmpty()) {
            throw new ApiException.BadRequest("file is required");
        }
        if (slot == null) {
            throw new ApiException.BadRequest("slot is required");
        }
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        String storageKey;
        try {
            storageKey = storageService.store(
                    session.getId().toString(), file.getOriginalFilename(), contentType, file.getInputStream());
        } catch (IOException e) {
            throw new ApiException.BadRequest("Could not read uploaded file");
        }

        Photo photo = new Photo();
        photo.setSessionId(session.getId());
        photo.setSlot(slot);
        photo.setStoragePath(storageKey);
        photo.setMimeType(contentType);
        Photo saved = photoRepository.save(photo);
        return PhotoDTO.from(saved, storageService.urlFor(saved.getStoragePath()));
    }

    @Transactional(readOnly = true)
    public List<PhotoDTO> list(AuthenticatedUser caller, UUID sessionId) {
        requireOwnedSession(caller, sessionId);
        return photoRepository.findBySessionIdOrderByUploadedAtAsc(sessionId).stream()
                .map(p -> PhotoDTO.from(p, storageService.urlFor(p.getStoragePath())))
                .toList();
    }

    private Session requireOwnedSession(AuthenticatedUser caller, UUID sessionId) {
        return sessionRepository.findByIdAndDealershipId(sessionId, caller.dealershipId())
                .orElseThrow(() -> new ApiException.NotFound("Session not found"));
    }
}
