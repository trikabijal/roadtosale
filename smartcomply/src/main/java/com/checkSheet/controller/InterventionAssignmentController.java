package com.checkSheet.controller;

import com.checkSheet.DTO.NonCompliantClosureDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.InterventionAssignmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/intervention-assignment")
public class InterventionAssignmentController {

    @Autowired private InterventionAssignmentService service;

    @GetMapping("/myPlans")
    public ResponseEntity<?> myPlans() {
        try {
            return ResponseEntity.ok(new ResponseDTO<>("My plans", service.myPlans()));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/byIntervention/{interventionId}")
    public ResponseEntity<?> byIntervention(@PathVariable Long interventionId) {
        try {
            return ResponseEntity.ok(new ResponseDTO<>("Plans", service.findByIntervention(interventionId)));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/byDealer/{auditeeId}")
    public ResponseEntity<?> byDealer(@PathVariable Long auditeeId) {
        try {
            return ResponseEntity.ok(new ResponseDTO<>("Plans", service.findByDealer(auditeeId)));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/byLocation/{auditeeLocationId}")
    public ResponseEntity<?> byLocation(@PathVariable Long auditeeLocationId) {
        try {
            return ResponseEntity.ok(new ResponseDTO<>("Plans", service.findByLocation(auditeeLocationId)));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(new ResponseDTO<>("Plan", service.findById(id)));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<?> acknowledge(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.acknowledge(id));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/{id}/closeNonCompliant")
    public ResponseEntity<?> closeNonCompliant(@PathVariable Long id, @RequestBody NonCompliantClosureDTO body) {
        try {
            return ResponseEntity.ok(service.closeAsNonCompliant(id, body));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
