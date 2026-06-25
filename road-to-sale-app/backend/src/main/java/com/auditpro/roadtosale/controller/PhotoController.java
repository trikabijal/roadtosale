package com.auditpro.roadtosale.controller;

import com.auditpro.roadtosale.domain.PhotoSlot;
import com.auditpro.roadtosale.dto.PhotoDTO;
import com.auditpro.roadtosale.security.AuthenticatedUser;
import com.auditpro.roadtosale.security.CurrentUser;
import com.auditpro.roadtosale.service.PhotoService;
import com.auditpro.roadtosale.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** Trade-in photo upload (multipart) + list. Scoped to the caller's dealership. */
@RestController
@RequestMapping("/sessions/{id}/photos")
@Tag(name = "photos")
@SecurityRequirement(name = "bearerAuth")
public class PhotoController {

    private final PhotoService photoService;

    public PhotoController(PhotoService photoService) {
        this.photoService = photoService;
    }

    @Operation(summary = "List all photos for a session")
    @GetMapping
    public ApiResponse<List<PhotoDTO>> list(@CurrentUser AuthenticatedUser caller, @PathVariable UUID id) {
        return ApiResponse.ok(photoService.list(caller, id));
    }

    @Operation(summary = "Upload one trade-in photo for a slot (multipart)")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PhotoDTO> upload(@CurrentUser AuthenticatedUser caller,
                                        @PathVariable UUID id,
                                        @RequestParam("slot") PhotoSlot slot,
                                        @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(photoService.upload(caller, id, slot, file));
    }
}
