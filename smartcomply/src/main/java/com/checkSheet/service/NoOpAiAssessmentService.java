package com.checkSheet.service;

import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * No-op fallback for AiAssessmentService.
 *
 * Registered only when AiAssessmentServiceImpl is NOT loaded
 * (i.e. ai.photo-assessment.enabled=false or absent).
 * Returns a clear "disabled" response to any caller rather than
 * failing with an unsatisfied dependency error.
 */
@Service
@ConditionalOnMissingBean(AiAssessmentService.class)
public class NoOpAiAssessmentService implements AiAssessmentService {

    private static final Logger logger = LoggerFactory.getLogger(NoOpAiAssessmentService.class);
    private static final String DISABLED_MSG = "AI photo assessment is disabled (ai.photo-assessment.enabled=false)";

    @Override
    public ResponseDTO<?> assessPhoto(MultipartFile photo, Long userChecksheetId, Long chksQuestionResultId)
            throws CustomException {
        logger.debug(DISABLED_MSG);
        return new ResponseDTO<>(false, DISABLED_MSG);
    }

    @Override
    public ResponseDTO<?> getAssessment(Long userChecksheetId, Long chksQuestionResultId) throws CustomException {
        return new ResponseDTO<>(false, DISABLED_MSG);
    }

    @Override
    public ResponseDTO<?> getAssessmentsByChecksheet(Long userChecksheetId) throws CustomException {
        return new ResponseDTO<>(false, DISABLED_MSG);
    }
}
