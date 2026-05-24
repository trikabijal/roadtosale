package com.checkSheet.service;

import com.checkSheet.DAO.ChksGeneralFieldDAO;
import com.checkSheet.DTO.ChksGeneralFieldDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.ChecksheetStatusType;
import com.checkSheet.entity.Checksheet;
import com.checkSheet.entity.ChksGeneralField;
import com.checkSheet.entity.User;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.ChecksheetRepository;
import com.checkSheet.repository.ChksGeneralFieldRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class ChksGeneralFieldServiceImpl implements ChksGeneralFieldService {
    @Autowired
    private ChksGeneralFieldRepository chksGeneralFieldRepository;

    @Autowired
    private ChecksheetRepository checksheetRepository;

    @Autowired
    private UtilityService utilityService;

    @Autowired
    private ChksGeneralFieldDAO chksGeneralFieldDAO;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createChksGeneralField(ChksGeneralFieldDTO chksGeneralFieldDTO) throws CustomException {
        try {
            if(Objects.isNull(chksGeneralFieldDTO) ||
                Objects.isNull(chksGeneralFieldDTO.getName()) || chksGeneralFieldDTO.getName().trim().isEmpty() ||
                Objects.isNull(chksGeneralFieldDTO.getChecksheetId()) || chksGeneralFieldDTO.getChecksheetId().toString().isEmpty()) {
                throw new CustomException("Please provide name, checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            ChksGeneralField chksGeneralField = new ChksGeneralField();
            Optional<User> currentLoggedInUser = utilityService.getCurrentLoggedInUser();
            if(!Objects.isNull(chksGeneralFieldDTO.getId())) {
                Optional<ChksGeneralField> oldChksGeneralField = chksGeneralFieldRepository.findById(chksGeneralFieldDTO.getId());
                if(!oldChksGeneralField.isPresent()) {
                    throw new CustomException("Please provide valid id", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                chksGeneralField = oldChksGeneralField.get();
                chksGeneralField.setUpdatedBy(currentLoggedInUser.get());
                chksGeneralField.setUpdatedAt(new Date());
            } else {
                chksGeneralField.setCreatedBy(currentLoggedInUser.get());
            }
            List<Long> isExistName = chksGeneralFieldDAO.getChksGeneralFieldByName(chksGeneralField.getId(), chksGeneralFieldDTO.getChecksheetId(), chksGeneralFieldDTO.getName());
            if(!Objects.isNull(isExistName) && !isExistName.isEmpty()) {
                throw new CustomException("Name already exist", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<Checksheet> checksheet = checksheetRepository.findById(chksGeneralFieldDTO.getChecksheetId());
            if (!checksheet.isPresent()) {
                throw new CustomException("Please provide valid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if(Objects.isNull(checksheet.get().getPreparerUser()) ||
                    !Objects.equals(checksheet.get().getPreparerUser().getId(), currentLoggedInUser.get().getId())) {
                throw new CustomException("You have not access of this checksheet", HttpStatus.FORBIDDEN);
            }
            if(Objects.equals(ChecksheetStatusType.NEW, checksheet.get().getStatus()) || Objects.equals(ChecksheetStatusType.NOT_APPROVED, checksheet.get().getStatus()) ||
                    Objects.equals(ChecksheetStatusType.INVALIDATED, checksheet.get().getStatus())) {
                checksheet.get().setStatus(ChecksheetStatusType.CREATE_TEMPLATE);
                checksheetRepository.save(checksheet.get());
            }
            if(!Objects.equals(checksheet.get().getStatus(), ChecksheetStatusType.CREATE_TEMPLATE)) {
                throw new CustomException("You can not add new general field", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            chksGeneralField.setName(chksGeneralFieldDTO.getName());
            chksGeneralField.setChecksheet(checksheet.get());
            chksGeneralFieldRepository.save(chksGeneralField);

            if(!Objects.isNull(chksGeneralFieldDTO.getId())) {
                return new ResponseDTO(true, "Checksheet edited successfully");
            }
            return new ResponseDTO(true, "Checksheet created successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getChksGeneralFieldData(ChksGeneralFieldDTO chksGeneralFieldDTO) throws CustomException {
        try {
            if(Objects.isNull(chksGeneralFieldDTO) || Objects.isNull(chksGeneralFieldDTO.getChecksheetId())) {
                throw new CustomException("Please provide checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            List<ChksGeneralFieldDTO> chksGeneralFieldByChecksheetId = chksGeneralFieldDAO.getChksGeneralFieldByChecksheetId(chksGeneralFieldDTO.getChecksheetId());
            return new ResponseDTO<>(true, "Data fetched successfully", chksGeneralFieldByChecksheetId);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> deleteChksGeneralFieldData(ChksGeneralFieldDTO chksGeneralFieldDTO) throws CustomException {
        try {
            if(Objects.isNull(chksGeneralFieldDTO) || Objects.isNull(chksGeneralFieldDTO.getId())) {
                throw new CustomException("Please provide id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            ChksGeneralField chksGeneralField = chksGeneralFieldRepository.findById(chksGeneralFieldDTO.getId())
                .orElseThrow(() -> new CustomException("Please provide valid id", HttpStatus.UNPROCESSABLE_ENTITY));
            Checksheet checksheet = chksGeneralField.getChecksheet();
            if(!Objects.equals(ChecksheetStatusType.NOT_APPROVED, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.INVALIDATED, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.CREATE_TEMPLATE, checksheet.getStatus())) {
                throw new CustomException("You can not delete general column", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            chksGeneralFieldRepository.delete(chksGeneralField);
            return new ResponseDTO<>(true, "Checksheet general field deleted successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
