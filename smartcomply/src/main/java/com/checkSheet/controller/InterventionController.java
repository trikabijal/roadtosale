package com.checkSheet.controller;

import com.checkSheet.DTO.InterventionCreateDTO;
import com.checkSheet.DTO.InterventionUpdateDTO;
import com.checkSheet.DTO.QuestionConflictDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.InterventionService;
import com.checkSheet.service.InterventionServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/intervention")
public class InterventionController {

    @Autowired private InterventionService interventionService;
    /** Used for the activate-with-targeting overload (concrete type — the
     *  interface intentionally doesn't expose the targeting hand-off). */
    @Autowired private InterventionServiceImpl interventionServiceImpl;

    @PostMapping("/createDraft")
    public ResponseEntity<?> createDraft(@RequestBody InterventionCreateDTO dto) {
        try {
            return ResponseEntity.ok(interventionService.createDraft(dto));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody InterventionUpdateDTO dto) {
        try {
            return ResponseEntity.ok(interventionService.update(id, dto));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/{id}/setQuestions")
    public ResponseEntity<?> setQuestions(@PathVariable Long id, @RequestBody List<Long> questionIds) {
        try {
            return ResponseEntity.ok(interventionService.setQuestions(id, questionIds));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/{id}/addQuestions")
    public ResponseEntity<?> addQuestions(@PathVariable Long id, @RequestBody List<Long> questionIds) {
        try {
            return ResponseEntity.ok(interventionService.addQuestions(id, questionIds));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /** Activate a draft. Body is the targeting payload (ALL / BY_REGION / MANUAL).
     *  Optional: if absent, defaults to ALL. */
    @PostMapping("/{id}/activate")
    public ResponseEntity<?> activate(@PathVariable Long id, @RequestBody(required = false) InterventionCreateDTO targeting) {
        try {
            // Up-front conflict check so the client gets a structured 409 body
            // (CustomException doesn't carry a payload).
            List<QuestionConflictDTO> conflicts = interventionService.previewActivationConflicts(id);
            if (!conflicts.isEmpty()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ResponseDTO<>(false, "Activation conflicts", conflicts));
            }
            return ResponseEntity.ok(interventionServiceImpl.activateWithTargeting(id, targeting));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/{id}/activationConflicts")
    public ResponseEntity<?> previewActivationConflicts(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(new ResponseDTO<>("ok", interventionService.previewActivationConflicts(id)));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<?> close(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(interventionService.close(id));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDraft(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(interventionService.deleteDraft(id));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<?> list() {
        try {
            return ResponseEntity.ok(new ResponseDTO<>("Interventions", interventionService.list()));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(new ResponseDTO<>("Intervention", interventionService.findById(id)));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /** Questions in the given audit that are already claimed by any
     *  ACTIVE or DRAFT intervention. Drives the "New Intervention" UI's
     *  hide-already-claimed-questions behaviour. */
    @GetMapping("/audit/{auditId}/claimedQuestions")
    public ResponseEntity<?> claimedQuestions(@PathVariable Long auditId) {
        try {
            return ResponseEntity.ok(new ResponseDTO<>("ok", interventionServiceImpl.claimedQuestions(auditId)));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
