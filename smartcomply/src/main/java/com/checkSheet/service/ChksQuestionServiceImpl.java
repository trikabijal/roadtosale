package com.checkSheet.service;

import com.checkSheet.DAO.ChksGeneralFieldDAO;
import com.checkSheet.DAO.ChksHeaderDAO;
import com.checkSheet.DTO.ChksHeaderDataFileDTO;
import com.checkSheet.DTO.ChksQuestionDTO;
import com.checkSheet.DTO.ChksQuestionFileDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.ChecksheetStatusType;
import com.checkSheet.entity.*;
import com.checkSheet.exception.CustomException;
import com.checkSheet.helper.FileStorageUtil;
import com.checkSheet.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Service
public class ChksQuestionServiceImpl implements ChksQuestionService {
    @Autowired
    private ChksGeneralFieldRepository chksGeneralFieldRepository;

    @Autowired
    private ChecksheetRepository checksheetRepository;

    @Autowired
    private UtilityService utilityService;

    @Autowired
    private ChksGeneralFieldDAO chksGeneralFieldDAO;

    @Autowired
    private ChksHeaderRepository chksHeaderRepository;

    @Autowired
    private ChksHeaderDAO chksHeaderDAO;

    @Autowired
    private ChksQuestionRepository chksQuestionRepository;

    @Autowired
    private ChksQuestionFileRepository chksQuestionFileRepository;

    @Autowired
    private AWSS3Service awsS3Service;

    @Autowired
    private ChksHeaderDataRepository chksHeaderDataRepository;

    @Autowired
    private ChksQuestionResultRepository chksQuestionResultRepository;

    @Autowired
    private ChksQuestionResultMatrixRepository chksQuestionResultMatrixRepository;

    @Autowired
    private ChksQuestionResultOptionRepository chksQuestionResultOptionRepository;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> updateDescription(ChksQuestionDTO chksQuestionDTO) throws CustomException {
        try {
            if (Objects.isNull(chksQuestionDTO) || Objects.isNull(chksQuestionDTO.getId())) {
                throw new CustomException("Please provide id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<ChksQuestion> chksQuestionById = chksQuestionRepository.findById(chksQuestionDTO.getId());
            ChksQuestion chksQuestion = new ChksQuestion();
            if(chksQuestionById.isPresent()) {
                chksQuestion = chksQuestionById.get();
            }
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            Optional<Checksheet> checksheet = checksheetRepository.findById(chksQuestion.getChecksheet().getId());
            if(Objects.isNull(checksheet.get().getPreparerUser()) ||
                    !Objects.equals(checksheet.get().getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You have not access of this checksheet", HttpStatus.FORBIDDEN);
            }
            chksQuestion.setDescription(chksQuestionDTO.getDescription());
            if (currentUser.isPresent()) {
                chksQuestion.setUpdatedBy(currentUser.get());
                chksQuestion.setUpdatedAt(new Date());
            }
            chksQuestion = chksQuestionRepository.save(chksQuestion);

            // Create response map
            Map<String, Object> response = new HashMap<>();
            response.put("description", chksQuestion.getDescription());
            response.put("id", chksQuestion.getId());

            return new ResponseDTO<>(true, "Description updated successfully", response);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error updating description: " + e.getMessage(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> updateName(ChksQuestionDTO chksQuestionDTO) throws CustomException {
        try {
            if (Objects.isNull(chksQuestionDTO) || Objects.isNull(chksQuestionDTO.getId())|| Objects.isNull(chksQuestionDTO.getName())) {
                throw new CustomException("Please provide id, name", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<ChksQuestion> chksQuestionById = chksQuestionRepository.findById(chksQuestionDTO.getId());
            ChksQuestion chksQuestion = new ChksQuestion();
            if(chksQuestionById.isPresent()) {
                chksQuestion = chksQuestionById.get();
            }
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            Optional<Checksheet> checksheet = checksheetRepository.findById(chksQuestion.getChecksheet().getId());
            if(Objects.isNull(checksheet.get().getPreparerUser()) ||
                    !Objects.equals(checksheet.get().getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You have not access of this checksheet", HttpStatus.FORBIDDEN);
            }
            chksQuestion.setName(chksQuestionDTO.getName());
            if (currentUser.isPresent()) {
                chksQuestion.setUpdatedBy(currentUser.get());
                chksQuestion.setUpdatedAt(new Date());
            }
            chksQuestionRepository.save(chksQuestion);
            return new ResponseDTO<>(true, "Description updated successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error updating description: " + e.getMessage(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> uploadFiles(ChksQuestionDTO chksQuestionDTO) throws CustomException {
        try {
            if(Objects.isNull(chksQuestionDTO) || Objects.isNull(chksQuestionDTO.getId()) ||
                    Objects.isNull(chksQuestionDTO.getChksQuestionImages()) ||
                    chksQuestionDTO.getChksQuestionImages().size()<1) {
                throw new CustomException("Please provide id, chksQuestionImages", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            ChksQuestion chksQuestion = chksQuestionRepository.findById(chksQuestionDTO.getId())
                    .orElseThrow(() -> new CustomException("Id not found", HttpStatus.UNPROCESSABLE_ENTITY));
            Checksheet checksheet = chksQuestion.getChecksheet();
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();

            if (Objects.isNull(checksheet.getPreparerUser()) ||
                    !Objects.equals(checksheet.getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You do not have access to this checksheet", HttpStatus.FORBIDDEN);
            }
            List<ChksHeaderDataFileDTO> uploadedFiles = new ArrayList<>();
            for(MultipartFile image : chksQuestionDTO.getChksQuestionImages()) {
                ChksQuestionFile chksQuestionFile = new ChksQuestionFile();
                chksQuestionFile.setChksQuestion(chksQuestion);
                chksQuestionFile.setChecksheet(checksheet);
                chksQuestionFile.setCreatedBy(currentUser.get());
                chksQuestionFileRepository.save(chksQuestionFile);

                String truncatedFilename = utilityService.getTruncatedFileName(image.getOriginalFilename(), 50);
//                String fileName = "ChecksheetQuestionData/" + chksQuestionFile.getId() + "_" + truncatedFilename;
//                awsS3Service.uploadMultipartFile(image, fileName);
                String fileName = FileStorageUtil.storeFile(image,"ChecksheetQuestionData/", chksQuestionFile.getId() + "_" + truncatedFilename);

                chksQuestionFile.setPath(fileName);
                chksQuestionFileRepository.save(chksQuestionFile);

                // Create DTO for response
                ChksHeaderDataFileDTO fileDTO = new ChksHeaderDataFileDTO();
                fileDTO.setId(chksQuestionFile.getId());
                fileDTO.setPath(chksQuestionFile.getPath());
//                fileDTO.setUrl(awsS3Service.getDocs(chksQuestionFile.getPath()).toString());
                fileDTO.setUrl(FileStorageUtil.getFileURL(chksQuestionFile.getPath()));
                fileDTO.setChksHeaderId(chksQuestion.getId());
                uploadedFiles.add(fileDTO);
            }
            Map<String, Object> response = new HashMap<>();
            response.put("files", uploadedFiles);
            return new ResponseDTO<>(true, "File uploaded successfully", response);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong: " + e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> deleteFile(ChksQuestionFileDTO chksHeaderDataFileDTO) throws CustomException {
        try {
            if(Objects.isNull(chksHeaderDataFileDTO) || Objects.isNull(chksHeaderDataFileDTO.getId())) {
                throw new CustomException("Please provide id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            ChksQuestionFile chksQuestionFile = chksQuestionFileRepository.findById(chksHeaderDataFileDTO.getId())
                    .orElseThrow(() -> new CustomException("Id not found", HttpStatus.UNPROCESSABLE_ENTITY));
            Checksheet checksheet = chksQuestionFile.getChecksheet();
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();

            if (Objects.isNull(checksheet.getPreparerUser()) ||
                    !Objects.equals(checksheet.getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You do not have access to this checksheet", HttpStatus.FORBIDDEN);
            }
            if(!Objects.equals(ChecksheetStatusType.NOT_APPROVED, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.INVALIDATED, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.CREATE_TEMPLATE, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.CREATE_CONTENT, checksheet.getStatus())) {
                throw new CustomException("You can not delete file", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            FileStorageUtil.deleteFile(chksQuestionFile.getPath());
            chksQuestionFileRepository.delete(chksQuestionFile);
            return new ResponseDTO<>(true, "File deleted successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong: " + e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> createBulkQuestions(com.checkSheet.DTO.request.ChksQuestionBulkDTO chksQuestionBulkDTO) throws CustomException {
        try {
            if (Objects.isNull(chksQuestionBulkDTO) || 
                Objects.isNull(chksQuestionBulkDTO.getChksHeaderDataId()) || 
                Objects.isNull(chksQuestionBulkDTO.getChecksheetId()) || 
                Objects.isNull(chksQuestionBulkDTO.getQuestions()) || 
                chksQuestionBulkDTO.getQuestions().isEmpty()) {
                throw new CustomException("Please provide chksHeaderDataId, checksheetId, and questions", 
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Long chksHeaderDataId = chksQuestionBulkDTO.getChksHeaderDataId();
            Long checksheetId = chksQuestionBulkDTO.getChecksheetId();
            Integer requestedOrderNo = chksQuestionBulkDTO.getOrderNo();
            
            // Verify checksheet exists
            Optional<Checksheet> checksheetOpt = checksheetRepository.findById(checksheetId);
            if (checksheetOpt.isEmpty()) {
                throw new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Checksheet checksheet = checksheetOpt.get();

            // Verify header data exists
            List<ChksHeaderData> chksHeaderDatabyChksHeaderDataId = chksHeaderDataRepository.findByChksHeaderDataId(chksHeaderDataId);
            if (!Objects.isNull(chksHeaderDatabyChksHeaderDataId) && !chksHeaderDatabyChksHeaderDataId.isEmpty()) {
                throw new CustomException("Question only attached at last level", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            // Verify header data exists
            Optional<ChksHeaderData> headerDataOpt = chksHeaderDataRepository.findById(chksHeaderDataId);
            if (headerDataOpt.isEmpty()) {
                throw new CustomException("Invalid chksHeaderDataId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            ChksHeaderData headerData = headerDataOpt.get();
            
            // Verify headerData belongs to the specified checksheet
            if (!headerData.getChecksheet().getId().equals(checksheetId)) {
                throw new CustomException("Header data doesn't belong to the specified checksheet", 
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            if (!currentUser.isPresent()) {
                throw new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED);
            }
            
            // Check if user has permission to modify this checksheet
            if (Objects.isNull(checksheet.getPreparerUser()) ||
                    !Objects.equals(checksheet.getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You do not have access to this checksheet", HttpStatus.FORBIDDEN);
            }

            ChksHeader header = headerData.getChksHeader();
            
            // Get existing questions for this header data to determine orderNo
            List<ChksQuestion> existingQuestions = chksQuestionRepository.findByChksHeaderData_Id(chksHeaderDataId);
            
            // Sort existing questions by orderNo and then by id
            existingQuestions.sort(Comparator.comparing(ChksQuestion::getOrderNo, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ChksQuestion::getId));
            
            // Determine starting orderNo based on request
            Integer startingOrderNo;
            List<ChksQuestion> questionsToUpdate = new ArrayList<>();
            
            if (requestedOrderNo != null) {
                // If orderNo is provided, use it and update subsequent questions
                // Even if requestedOrderNo is large (like 40), we'll use it directly
                startingOrderNo = requestedOrderNo;
                
                // Find questions that need their orderNo updated
                for (ChksQuestion existingQuestion : existingQuestions) {
                    if (existingQuestion.getOrderNo() != null && existingQuestion.getOrderNo() >= startingOrderNo) {
                        questionsToUpdate.add(existingQuestion);
                    }
                }
                
                // Sort questions to update by orderNo to ensure correct sequence
                questionsToUpdate.sort(Comparator.comparing(ChksQuestion::getOrderNo, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ChksQuestion::getId));
                if(startingOrderNo>existingQuestions.size() +1) {
                    Optional<ChksQuestion> max = existingQuestions.stream().
                            filter(q -> q.getOrderNo() != null).
                            max((o1, o2) -> o1.getOrderNo().compareTo(o2.getOrderNo()));
//                    max.ifPresent(questionsToUpdate::add);
                    if(max.isPresent()) {
                        startingOrderNo = max.get().getOrderNo() + 1;
                    }
                }
                // Log the requested orderNo for debugging
                System.out.println("Using requested orderNo: " + startingOrderNo);
            } else {
                // If no orderNo provided, append to the end
                startingOrderNo = existingQuestions.isEmpty() ? 1 : 
                    existingQuestions.stream()
                        .map(ChksQuestion::getOrderNo)
                        .filter(Objects::nonNull)
                        .max(Integer::compare)
                        .orElse(0) + 1;
            }
            
            // Create new questions with assigned orderNo
            List<ChksQuestion> newQuestions = new ArrayList<>();
            int currentOrderNo = startingOrderNo;

            for (ChksQuestionDTO questionDTO : chksQuestionBulkDTO.getQuestions()) {
                // Validate question data
                if (questionDTO.getName() == null || questionDTO.getName().trim().isEmpty()) {
                    throw new CustomException("Question name is required", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                
                ChksQuestion question = new ChksQuestion();
                question.setName(questionDTO.getName());
                question.setDescription(questionDTO.getDescription());
                question.setChecksheet(checksheet);
                question.setCreatedBy(currentUser.get());
                question.setChksHeader(header);
                question.setChksHeaderData(headerData);
                question.setOrderNo(currentOrderNo);

                // Increment for next question
                currentOrderNo++;
                
                newQuestions.add(question);
            }
            
            // Save all new questions first
            chksQuestionRepository.saveAll(newQuestions);
            
            // Update orderNo for existing questions if needed
            if (!questionsToUpdate.isEmpty()) {
                int updatedOrderNo = currentOrderNo; // Start from after the last new question
                
                for (ChksQuestion questionToUpdate : questionsToUpdate) {
                    questionToUpdate.setOrderNo(updatedOrderNo++);
                    chksQuestionRepository.save(questionToUpdate);
                }
            }
            
            // Update checksheet status if needed
            if (Objects.equals(ChecksheetStatusType.CREATE_TEMPLATE, checksheet.getStatus()) ||
                    Objects.equals(ChecksheetStatusType.INVALIDATED, checksheet.getStatus()) ||
                    Objects.equals(ChecksheetStatusType.NOT_APPROVED, checksheet.getStatus())) {
                checksheet.setStatus(ChecksheetStatusType.CREATE_CONTENT);
                checksheetRepository.save(checksheet);
            }
            
            return new ResponseDTO<>(true, "Questions created successfully");
            
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error creating questions: " + e.getMessage(), 
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> deleteQuestions(ChksQuestionDTO chksQuestionDTO) throws CustomException {
        try {
            // Validate required fields
            if (Objects.isNull(chksQuestionDTO) ||
                Objects.isNull(chksQuestionDTO.getChksHeaderDataId()) ||
                Objects.isNull(chksQuestionDTO.getQuestionIds()) ||
                chksQuestionDTO.getQuestionIds().isEmpty()) {
                throw new CustomException("Please provide chksHeaderDataId and questionIds", 
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Get header data
            Optional<ChksHeaderData> headerDataOpt = chksHeaderDataRepository.findById(
                chksQuestionDTO.getChksHeaderDataId());
            if (headerDataOpt.isEmpty()) {
                throw new CustomException("Invalid chksHeaderDataId", 
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }
            ChksHeaderData headerData = headerDataOpt.get();
            
            // Get checksheet
            Checksheet checksheet = headerData.getChecksheet();
            
            // Get current user
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            if (currentUser.isEmpty()) {
                throw new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED);
            }
            
            // Check if user has permission to modify this checksheet
            if (Objects.isNull(checksheet.getPreparerUser()) ||
                    !Objects.equals(checksheet.getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You do not have access to this checksheet", HttpStatus.FORBIDDEN);
            }
            
            // Get all questions for this header data
            List<ChksQuestion> allQuestions = chksQuestionRepository.findByChksHeaderData_Id(
                chksQuestionDTO.getChksHeaderDataId());
            
            // Validate if at least one question will remain after deletion
            List<Long> questionIdsToDelete = chksQuestionDTO.getQuestionIds();
            if (allQuestions.size() <= questionIdsToDelete.size()) {
                throw new CustomException("At least one question must remain for header data", 
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            // Verify all question IDs are valid and belong to the header data
            List<ChksQuestion> questionsToDelete = chksQuestionRepository.findByIdIn(questionIdsToDelete);
            if (questionsToDelete.size() != questionIdsToDelete.size()) {
                throw new CustomException("One or more invalid question IDs provided", 
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            for (ChksQuestion question : questionsToDelete) {
                if (!question.getChksHeaderData().getId().equals(chksQuestionDTO.getChksHeaderDataId())) {
                    throw new CustomException("Question does not belong to the specified header data", 
                        HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }
            
            // Get orderNo values of questions to be deleted
            Set<Integer> orderNosToDelete = new HashSet<>();
            for (ChksQuestion question : questionsToDelete) {
                if (question.getOrderNo() != null) {
                    orderNosToDelete.add(question.getOrderNo());
                }
            }

            
            // Sort all questions by orderNo for processing
            allQuestions.sort(Comparator.comparing(ChksQuestion::getOrderNo, 
                Comparator.nullsLast(Comparator.naturalOrder())));
            
            // Delete related data for each question
            for (ChksQuestion question : questionsToDelete) {
                // 1. Delete question results matrices
                List<ChksQuestionResult> questionResults = chksQuestionResultRepository.findByChksQuestion_IdOrderById(question.getId());
                for (ChksQuestionResult result : questionResults) {
                    chksQuestionResultMatrixRepository.deleteByChksQuestionResult_Id(result.getId());
                    chksQuestionResultOptionRepository.deleteByChksQuestionResult_Id(result.getId());
                }
                
                // 2. Delete question results
                chksQuestionResultRepository.deleteAll(questionResults);
                
                // 3. Delete question files
                List<ChksQuestionFile> questionFiles = chksQuestionFileRepository.findByChksQuestion_Id(question.getId());
                chksQuestionFileRepository.deleteAll(questionFiles);
                
                // 4. Delete the question
                chksQuestionRepository.delete(question);
            }
            
            // After deleting questions, we need to update the orderNo of remaining questions
            // to ensure they are sequential (1, 2, 3, etc.)
            
            // Get remaining questions that need orderNo updated
            List<ChksQuestion> remainingQuestions = chksQuestionRepository.findByChecksheet_IdAndChecksheetChksHeaderDataOrderById(
                checksheet.getId(),
                chksQuestionDTO.getChksHeaderDataId());
            
            // If we have remaining questions, update their orderNo values
            if (!remainingQuestions.isEmpty()) {
                
                // Sort remaining questions by orderNo
                remainingQuestions.sort(Comparator.comparing(ChksQuestion::getOrderNo, 
                    Comparator.nullsLast(Comparator.naturalOrder())));
                
                // Update orderNo for remaining questions
                int newOrderNo = 1;
                for (ChksQuestion remainingQuestion : remainingQuestions) {
                    // Skip questions with null orderNo
//                    if (remainingQuestion.getOrderNo() == null) {
//                        continue;
//                    }
                    
                    // Update orderNo if it's different
                    if (remainingQuestion.getOrderNo() == null || remainingQuestion.getOrderNo() != newOrderNo) {
                        remainingQuestion.setOrderNo(newOrderNo);
                        chksQuestionRepository.save(remainingQuestion);
                    }
                    
                    newOrderNo++;
                }
            }
            
            return new ResponseDTO<>(true, "Questions deleted successfully");
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error deleting questions: " + e.getMessage(), 
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
