package com.checkSheet.service;

import com.checkSheet.DTO.InterventionCreateDTO;
import com.checkSheet.DTO.InterventionDTO;
import com.checkSheet.DTO.InterventionUpdateDTO;
import com.checkSheet.DTO.QuestionConflictDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;

import java.util.List;

/** Public contract for managing Interventions (PRD "Improvement Campaigns").
 *  Lifecycle: DRAFT → ACTIVE → CLOSED. Drafts are mutable. ACTIVE campaigns
 *  are append-only on questions and target set is locked. */
public interface InterventionService {

    /** Create a Draft intervention. Targeting fields are kept on the DTO
     *  but the target set is only materialised at activation. */
    ResponseDTO<InterventionDTO> createDraft(InterventionCreateDTO dto) throws CustomException;

    /** Update a Draft. Throws 422 if status != DRAFT. */
    ResponseDTO<InterventionDTO> update(Long id, InterventionUpdateDTO dto) throws CustomException;

    /** Replace the question set on a Draft. Throws if not DRAFT. */
    ResponseDTO<InterventionDTO> setQuestions(Long id, List<Long> questionIds) throws CustomException;

    /** Append questions to an ACTIVE intervention (allowed by PRD §3.1.4). */
    ResponseDTO<InterventionDTO> addQuestions(Long id, List<Long> questionIds) throws CustomException;

    /** Activate a Draft: materialise target set into intervention_assignment_targets,
     *  flip status to ACTIVE. Returns 409 conflict payload if any of the
     *  selected questions are already claimed by another ACTIVE intervention
     *  on the same audit cycle. */
    ResponseDTO<InterventionDTO> activate(Long id) throws CustomException;

    /** Dry-run for the activate-time conflict check (PRD §3.1.5). Returns
     *  the list of conflicting active interventions, if any. */
    List<QuestionConflictDTO> previewActivationConflicts(Long id) throws CustomException;

    /** Close an active intervention. */
    ResponseDTO<InterventionDTO> close(Long id) throws CustomException;

    /** Soft-delete a Draft (only). */
    ResponseDTO<Void> deleteDraft(Long id) throws CustomException;

    /** All non-deleted interventions, with stats. */
    List<InterventionDTO> list() throws CustomException;

    /** Detail with questionIds + materialised target audit_assignment ids. */
    InterventionDTO findById(Long id) throws CustomException;
}
