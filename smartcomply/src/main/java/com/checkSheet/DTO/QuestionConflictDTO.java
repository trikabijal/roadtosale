package com.checkSheet.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Returned (in HTTP 409) when activate() finds another ACTIVE intervention
 *  on the same audit cycle that already claims one of this intervention's
 *  in-scope questions. PRD §3.1.5. */
@Data @NoArgsConstructor @AllArgsConstructor
public class QuestionConflictDTO {
    private Long conflictingInterventionId;
    private String conflictingInterventionName;
    private String conflictingPriority;
    private List<Long> overlappingQuestionIds;
}
