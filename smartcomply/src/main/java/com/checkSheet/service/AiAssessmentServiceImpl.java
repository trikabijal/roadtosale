package com.checkSheet.service;

import com.checkSheet.DTO.AiAssessmentDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.entity.*;
import com.checkSheet.exception.CustomException;
import com.checkSheet.helper.FileStorageUtil;
import com.trika.llm.LlmClient;
import com.trika.llm.LlmException;
import com.trika.llm.LlmResponse;
import com.checkSheet.repository.AiAssessmentRepository;
import com.checkSheet.repository.ChksQuestionResultOptionRepository;
import com.checkSheet.repository.ChksQuestionResultRepository;
import com.checkSheet.repository.UserChecksheetRepository;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Service
@ConditionalOnProperty(name = "ai.photo-assessment.enabled", havingValue = "true")
public class AiAssessmentServiceImpl implements AiAssessmentService {

    private static final Logger logger = LoggerFactory.getLogger(AiAssessmentServiceImpl.class);
    private static final String PHOTO_STORAGE_PATH = "ai-assessments/";

    @org.springframework.beans.factory.annotation.Value("${ai.assessment.store-raw-response:false}")
    private boolean storeRawResponse;
    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );

    private static final String SYSTEM_PROMPT = """
            You are an automotive dealership facility auditor assessing checkpoints from photos.

            STEP 1 — PHOTO RELEVANCE:
            Does this photo show the element being assessed?
            If not, respond: {"j":"INVALID","e":"Photo does not show [element]","c":0.0}

            STEP 2 — ASSESS ONLY WHAT IS ASKED:
            You will receive a specific checkpoint (e.g., "Damage" or "Cleanliness" for a specific element).
            Assess ONLY that checkpoint. Nothing else.
            - If the checkpoint is "Damage", assess only physical damage. Ignore cleanliness, branding, age.
            - If the checkpoint is "Cleanliness", assess only dirt/dust/stains. Ignore damage, branding, age.
            - If the checkpoint is "Visibility", assess only whether the element is visible. Ignore its condition.
            Do NOT fail a checkpoint for issues that belong to a different checkpoint.

            WHAT IS NORMAL (not a defect):
            - Dealer names on facades (e.g., "Rick Case Kia", "Shelly Motors") — normal business signage.
            - Old logo styles or previous brand colors — brand compliance issue, not damage or dirt.
            - Faded/sun-bleached color — age, not dirt. Only flag as NOT_OK for "Cleanliness" if actual dirt/stains/dust are visible.
            - Construction vehicles or equipment nearby — assess the element itself, not its surroundings.

            JUDGEMENT:
            - OK: The element meets the OK criteria for this specific checkpoint.
            - NOT_OK: You can point to a specific defect relevant to this checkpoint.
            - If it looks acceptable, judge OK even if you cannot inspect every detail.

            CONFIDENCE:
            - 0.9-1.0: Clear, unambiguous.
            - 0.7-0.8: Likely but minor uncertainty.
            - 0.5-0.6: Genuinely uncertain — recommend human re-inspection.

            OUTPUT:
            - OK with high confidence: {"j":"OK","c":0.9}
            - NOT_OK or low confidence: {"j":"NOT_OK","e":"specific issue max 10 words","c":0.6}
            - Photo irrelevant: {"j":"INVALID","e":"does not show [element]","c":0.0}""";

    @Autowired
    private AiAssessmentRepository aiAssessmentRepository;

    @Autowired
    private UserChecksheetRepository userChecksheetRepository;

    @Autowired
    private ChksQuestionResultRepository chksQuestionResultRepository;

    @Autowired
    private ChksQuestionResultOptionRepository chksQuestionResultOptionRepository;

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private UtilityService utilityService;

    @Override
    @Transactional
    public ResponseDTO<?> assessPhoto(MultipartFile photo, Long userChecksheetId, Long chksQuestionResultId) throws CustomException {
        // 1. Validate current user
        User currentUser = utilityService.getCurrentLoggedInUser()
                .orElseThrow(() -> new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED));

        // 2. Validate inputs
        if (photo == null || photo.isEmpty()) {
            throw new CustomException("Photo is required", HttpStatus.BAD_REQUEST);
        }

        String contentType = photo.getContentType();
        if (contentType == null || !SUPPORTED_MEDIA_TYPES.contains(contentType)) {
            throw new CustomException("Unsupported image format. Supported: JPEG, PNG, GIF, WebP", HttpStatus.BAD_REQUEST);
        }

        // 3. Load checkpoint data and verify ownership
        Inspection userChecksheet = verifyChecksheetAccess(userChecksheetId, currentUser);

        ChksQuestionResult questionResult = chksQuestionResultRepository.findById(chksQuestionResultId)
                .orElseThrow(() -> new CustomException("Checkpoint not found", HttpStatus.NOT_FOUND));

        // Verify question result belongs to this checksheet
        if (!questionResult.getChecksheet().getId().equals(userChecksheet.getChecksheet().getId())) {
            throw new CustomException("Checkpoint does not belong to this checksheet", HttpStatus.BAD_REQUEST);
        }

        // 4. Load question and options for prompt construction
        ChksQuestion question = questionResult.getChksQuestion();
        List<ChksQuestionResultOption> options = chksQuestionResultOptionRepository
                .findByChksQuestionResult_Id(chksQuestionResultId);

        // 5. Build user prompt (compact — system prompt is separate and cached)
        String userPrompt = buildUserPrompt(question, options);

        // 6. Encode image to base64
        String base64Image;
        try {
            base64Image = Base64.getEncoder().encodeToString(photo.getBytes());
        } catch (IOException e) {
            throw new CustomException("Failed to read uploaded photo", e);
        }

        // 7. Call AI provider (before storing photo — no orphan files on failure)
        LlmResponse providerResponse;
        try {
            providerResponse = llmClient.assessImage(
                    base64Image, contentType, SYSTEM_PROMPT, userPrompt);
        } catch (LlmException e) {
            throw new CustomException(e.getMessage(), e, HttpStatus.valueOf(e.getHttpStatus()));
        }

        // 8. Parse business-level result from raw AI text
        ParsedAssessment parsed = parseAiResponse(providerResponse.getTextContent());

        // 9. Store photo only after successful LLM call
        String originalName = photo.getOriginalFilename();
        String safeName = originalName != null ? originalName.replaceAll("[^a-zA-Z0-9._-]", "_") : "photo";
        String fileName = UUID.randomUUID() + "_" + safeName;
        String photoPath = FileStorageUtil.storeFile(photo, PHOTO_STORAGE_PATH, fileName);

        // 10. Persist assessment
        AiAssessment assessment = new AiAssessment();
        assessment.setInspection(userChecksheet);
        assessment.setChksQuestionResult(questionResult);
        assessment.setPhotoPath(photoPath);
        assessment.setSuggestedJudgement(parsed.judgement);
        assessment.setExplanation(parsed.explanation);
        assessment.setConfidence(parsed.confidence);
        assessment.setAiModel(providerResponse.getModelName());
        assessment.setAiProvider(providerResponse.getProvider().getValue());
        assessment.setInputTokens(providerResponse.getInputTokens());
        assessment.setOutputTokens(providerResponse.getOutputTokens());
        assessment.setLatencyMs(providerResponse.getLatencyMs());
        if (storeRawResponse) {
            assessment.setPromptSent(userPrompt);
            assessment.setRawResponse(providerResponse.getRawApiResponse());
        }
        assessment.setAssessedAt(new Date());
        assessment.setCreatedBy(currentUser);

        assessment = aiAssessmentRepository.save(assessment);

        // 11. Build response DTO
        AiAssessmentDTO dto = toDTO(assessment);

        return new ResponseDTO<>("AI assessment completed", dto);
    }

    @Override
    public ResponseDTO<?> getAssessment(Long userChecksheetId, Long chksQuestionResultId) throws CustomException {
        User currentUser = utilityService.getCurrentLoggedInUser()
                .orElseThrow(() -> new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED));

        verifyChecksheetAccess(userChecksheetId, currentUser);

        Optional<AiAssessment> assessment = aiAssessmentRepository
                .findFirstByInspectionIdAndChksQuestionResultIdAndDeletedAtIsNullOrderByAssessedAtDesc(
                        userChecksheetId, chksQuestionResultId);

        if (assessment.isEmpty()) {
            return new ResponseDTO<>("No AI assessment found for this checkpoint", HttpStatus.NOT_FOUND);
        }

        return new ResponseDTO<>("AI assessment retrieved", toDTO(assessment.get()));
    }

    @Override
    public ResponseDTO<?> getAssessmentsByChecksheet(Long userChecksheetId) throws CustomException {
        User currentUser = utilityService.getCurrentLoggedInUser()
                .orElseThrow(() -> new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED));

        verifyChecksheetAccess(userChecksheetId, currentUser);

        List<AiAssessment> assessments = aiAssessmentRepository
                .findByInspectionIdAndDeletedAtIsNullOrderByAssessedAtDesc(userChecksheetId);

        List<AiAssessmentDTO> dtos = assessments.stream().map(this::toDTO).toList();

        return new ResponseDTO<>("AI assessments retrieved", dtos, dtos.size());
    }

    String buildUserPrompt(ChksQuestion question, List<ChksQuestionResultOption> options) {
        StringBuilder sb = new StringBuilder();
        sb.append(question.getName()).append("\n");

        for (ChksQuestionResultOption option : options) {
            String key = option.getJudgement().trim().toUpperCase().replace(" ", "_");
            sb.append(key).append(": ").append(option.getOption()).append("\n");
        }

        return sb.toString();
    }

    ParsedAssessment parseAiResponse(String textContent) throws CustomException {
        try {
            String jsonText = extractJson(textContent);
            JSONObject result = new JSONObject(jsonText);

            String judgement = result.has("j") ? result.getString("j") : result.getString("judgement");
            judgement = judgement.toUpperCase().replace(" ", "_");
            if (!judgement.equals("OK") && !judgement.equals("NOT_OK") && !judgement.equals("INVALID")) {
                judgement = "NOT_OK";
            }

            String explanation = result.has("e") ? result.optString("e", "") : result.optString("explanation", "");
            double confidence = result.has("c") ? result.optDouble("c", 0.5) : result.optDouble("confidence", 0.5);
            confidence = Math.max(0.0, Math.min(1.0, confidence));

            return new ParsedAssessment(judgement, explanation, confidence);
        } catch (Exception e) {
            logger.error("Failed to parse AI response: {}", textContent, e);
            throw new CustomException("Failed to parse AI assessment response", e);
        }
    }

    String extractJson(String text) {
        if (text.contains("```json")) {
            int start = text.indexOf("```json") + 7;
            int end = text.indexOf("```", start);
            if (end > start) {
                return text.substring(start, end).trim();
            }
        }
        if (text.contains("```")) {
            int start = text.indexOf("```") + 3;
            int end = text.indexOf("```", start);
            if (end > start) {
                return text.substring(start, end).trim();
            }
        }
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1);
        }
        return text;
    }

    private AiAssessmentDTO toDTO(AiAssessment assessment) {
        String photoUrl = FileStorageUtil.getFileURL(assessment.getPhotoPath());
        return new AiAssessmentDTO(
                assessment.getId(),
                assessment.getInspection().getId(),
                assessment.getChksQuestionResult().getId(),
                assessment.getSuggestedJudgement(),
                assessment.getExplanation(),
                assessment.getConfidence(),
                photoUrl,
                assessment.getAssessedAt()
        );
    }

    private Inspection verifyChecksheetAccess(Long userChecksheetId, User currentUser) throws CustomException {
        Inspection userChecksheet = userChecksheetRepository.findById(userChecksheetId)
                .orElseThrow(() -> new CustomException("User checksheet not found", HttpStatus.NOT_FOUND));

        if (userChecksheet.getOperatorUser() == null ||
                !userChecksheet.getOperatorUser().getId().equals(currentUser.getId())) {
            throw new CustomException("You do not have access to this checksheet", HttpStatus.FORBIDDEN);
        }
        return userChecksheet;
    }

    record ParsedAssessment(String judgement, String explanation, double confidence) {}
}
