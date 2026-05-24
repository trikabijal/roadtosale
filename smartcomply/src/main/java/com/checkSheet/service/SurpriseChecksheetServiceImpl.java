package com.checkSheet.service;

import com.checkSheet.DTO.AttachmentFileDTO;
import com.checkSheet.DTO.SurprizeChecksheetDTO;
import com.checkSheet.DTO.SurprizeChecksheetFieldDTO;
import com.checkSheet.DTO.UserDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.EmailTemplate;
import com.checkSheet.entity.Department;
import com.checkSheet.entity.SurpriseChecksheet;
import com.checkSheet.entity.SurpriseChecksheetField;
import com.checkSheet.entity.User;
import com.checkSheet.exception.CustomException;
import com.checkSheet.helper.FileStorageUtil;
import com.checkSheet.repository.DepartmentRepository;
import com.checkSheet.repository.SurpriseChecksheetRepository;
import com.checkSheet.repository.SurprizeChecksheetFieldRepository;
import com.checkSheet.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class SurpriseChecksheetServiceImpl implements SurpriseChecksheetService {
    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UtilityService utilityService;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private SurpriseChecksheetRepository surpriseChecksheetRepository;

    @Autowired
    private SurprizeChecksheetFieldRepository surprizeChecksheetFieldRepository;
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createSurpriseChecksheets(List<SurprizeChecksheetDTO> surprizeChecksheets) throws CustomException {
        try{
            Optional<SurpriseChecksheet> surpriseChecksheetOptional;
            SurpriseChecksheet surpriseChecksheet;
            User currentLoggedInUser = utilityService.getCurrentLoggedInUser().get();
            for(SurprizeChecksheetDTO surprizeChecksheet:surprizeChecksheets){
                if(!Objects.equals(surprizeChecksheet.getName(),null) && surprizeChecksheet.getName().isBlank()){
                    throw new CustomException("Please provide surprise checksheet name", HttpStatus.UNPROCESSABLE_ENTITY);
                } else if (!Objects.equals(surprizeChecksheet.getId(),null)) {
                    surpriseChecksheetOptional = surpriseChecksheetRepository.findById(surprizeChecksheet.getId());
                    if(surpriseChecksheetOptional.isPresent()){
                        surpriseChecksheet = surpriseChecksheetOptional.get();
                        surpriseChecksheet.setUpdatedBy(currentLoggedInUser);
                    }else{
                        throw new CustomException("Please provide valid surprise checksheet ID", HttpStatus.UNPROCESSABLE_ENTITY);
                    }
                }else{
                    surpriseChecksheet = new SurpriseChecksheet();
                    surpriseChecksheet.setCreatedBy(currentLoggedInUser);
                }
                surpriseChecksheet.setName(surprizeChecksheet.getName());
                surpriseChecksheetRepository.save(surpriseChecksheet);
                surpriseChecksheetRepository.flush();
                surprizeChecksheet.setId(surpriseChecksheet.getId());
            }
            return new ResponseDTO<>(true, "Surprise Checksheets are saved successfully",surprizeChecksheets);
        } catch ( Exception e ) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createSurpriseChecksheetFields(SurprizeChecksheetFieldDTO surprizeChecksheetField) throws CustomException {
        try {
            if(Objects.equals(surprizeChecksheetField.getSurpriseChecksheetId(),null)){
                throw new CustomException("Please provide surprise checksheet ID.", HttpStatus.UNPROCESSABLE_ENTITY);
            }else if(Objects.equals(surprizeChecksheetField.getDepartmentId(),null)){
                throw new CustomException("Please provide department ID.", HttpStatus.UNPROCESSABLE_ENTITY);
            }else if (Objects.equals(surprizeChecksheetField.getResponsibleUserUsername(),null) || surprizeChecksheetField.getResponsibleUserUsername().trim().isBlank()){
                throw new CustomException("Please provide responsible user's Username.", HttpStatus.UNPROCESSABLE_ENTITY);
            }else if(surprizeChecksheetField.getFiles().isEmpty()){
                throw new CustomException("Please provide image(s).", HttpStatus.UNPROCESSABLE_ENTITY);
            }else if(Objects.equals(surprizeChecksheetField.getConcern(),null)|| surprizeChecksheetField.getConcern().isBlank()){
                throw new CustomException("Please provide concern.", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            User currentLoggedInUser = utilityService.getCurrentLoggedInUser().orElseThrow(() -> new CustomException("User is not Authorized!", HttpStatus.UNPROCESSABLE_ENTITY));
            SurpriseChecksheet surpriseChecksheet = surpriseChecksheetRepository.findById(surprizeChecksheetField.getSurpriseChecksheetId()).orElseThrow(() -> new CustomException("Please, provide valid surprise checksheet ID", HttpStatus.UNPROCESSABLE_ENTITY));
            SurpriseChecksheetField surpriseChecksheetField;
            Department dept = departmentRepository.findById(surprizeChecksheetField.getDepartmentId()).orElseThrow(() -> new CustomException("Please, provide valid department id", HttpStatus.UNPROCESSABLE_ENTITY));
            if(Objects.equals(surprizeChecksheetField.getId(),null)){
                surpriseChecksheetField = new SurpriseChecksheetField();
                surpriseChecksheetField.setSurpriseChecksheet(surpriseChecksheet);
                surpriseChecksheetField.setDepartment(dept);
                surpriseChecksheetField.setCreatedBy(currentLoggedInUser);
            }else{
                surpriseChecksheetField = surprizeChecksheetFieldRepository.findById(surprizeChecksheetField.getId()).orElseThrow(() -> new CustomException("Please, provide valid surprise checksheet Field ID", HttpStatus.UNPROCESSABLE_ENTITY));
                surpriseChecksheetField.setUpdatedBy(currentLoggedInUser);
            }
            UserDTO userDTO = new UserDTO();
            userDTO.setUsername(surprizeChecksheetField.getResponsibleUserUsername());
            userDTO = (UserDTO)userService.validateAndSaveUsername(userDTO).getData();
            User responsibleUser = userRepository.findByUsernameIgnoreCase(userDTO.getUsername()).orElseThrow(() -> new CustomException("Please, provide valid Username",HttpStatus.UNPROCESSABLE_ENTITY));
            surpriseChecksheetField.setResponsibleUser(responsibleUser);

            surpriseChecksheetField.setRemarks(surprizeChecksheetField.getRemarks());
            surpriseChecksheetField.setCreationDate(surprizeChecksheetField.getCreationDate());
            surpriseChecksheetField.setConcern(surprizeChecksheetField.getConcern().trim());
            surprizeChecksheetFieldRepository.save(surpriseChecksheetField);
            surprizeChecksheetFieldRepository.flush();
            List<String> files = new ArrayList<>();
            int count = 1;
            List<AttachmentFileDTO> imageList = new ArrayList<>();
            for (MultipartFile doc : surprizeChecksheetField.getFiles()) {
                String truncatedFilename = utilityService.getTruncatedFileName(doc.getOriginalFilename(), 50);
                String fileName = FileStorageUtil.storeFile(doc,"SurprizeChecksheetField/", surpriseChecksheetField.getId() + "_" + count + "_" + truncatedFilename);
                files.add(fileName);
                count++;

                AttachmentFileDTO attachmentFileDTO = new AttachmentFileDTO();
                attachmentFileDTO.setImageName(doc.getOriginalFilename());
                attachmentFileDTO.setImage(doc.getBytes());
                imageList.add(attachmentFileDTO);
            }
            surpriseChecksheetField.setFilePaths(files);
            surprizeChecksheetFieldRepository.save(surpriseChecksheetField);
            surprizeChecksheetField.setId(surpriseChecksheetField.getId());
            utilityService.sendSurpriseChksEmail(List.of(responsibleUser.getId()), EmailTemplate.CREATE_SURPRISE_CHKS_FIELD, dept, dept, surpriseChecksheetField, List.of(currentLoggedInUser.getId()), imageList);
            surprizeChecksheetField.setFiles(null);
            return new ResponseDTO<>(true, "Surprise Checksheet Field is saved successfully",surprizeChecksheetField);
        } catch ( Exception e ) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

}
