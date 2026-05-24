package com.checkSheet.controller;

import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.AuditeeTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/auditeeType")
public class AuditeeTypeController {

    @Autowired
    private AuditeeTypeService auditeeTypeService;

    @GetMapping("/getAuditeeTypes")
    public ResponseEntity<?> getAuditeeTypes() {
        try {
            return ResponseEntity.ok(auditeeTypeService.getAuditeeTypes());
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
