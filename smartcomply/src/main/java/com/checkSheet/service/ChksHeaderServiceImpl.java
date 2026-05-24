package com.checkSheet.service;

import com.checkSheet.DAO.ChksGeneralFieldDAO;
import com.checkSheet.DAO.ChksHeaderDAO;
import com.checkSheet.DTO.ChksGeneralFieldDTO;
import com.checkSheet.DTO.ChksHdrSummaryReportLevelDTO;
import com.checkSheet.DTO.ChksHeaderDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.ChecksheetStatusType;
import com.checkSheet.entity.Checksheet;
import com.checkSheet.entity.ChksHeader;
import com.checkSheet.entity.User;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.ChecksheetRepository;
import com.checkSheet.repository.ChksGeneralFieldRepository;
import com.checkSheet.repository.ChksHeaderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChksHeaderServiceImpl implements ChksHeaderService {
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


    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> createChksHeader(ChksHeaderDTO chksHeaderDTO) throws CustomException {
        try {
            if((Objects.isNull(chksHeaderDTO) ||
                    Objects.isNull(chksHeaderDTO.getName()) || chksHeaderDTO.getName().trim().isEmpty() ||
                    Objects.isNull(chksHeaderDTO.getChecksheetId()) || chksHeaderDTO.getChecksheetId().toString().isEmpty() ||
                    Objects.isNull(chksHeaderDTO.getIsResultColumn())
                && Objects.isNull(chksHeaderDTO.getId()))
            ) {
                throw new CustomException("Please provide name, checksheetId, isResultColumn", HttpStatus.UNPROCESSABLE_ENTITY);
            } else if(Objects.isNull(chksHeaderDTO.getId()) &&
                    (Objects.isNull(chksHeaderDTO.getName()) || chksHeaderDTO.getName().trim().isEmpty())) {
                throw new CustomException("Please provide name", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<Checksheet> checksheet = checksheetRepository.findById(chksHeaderDTO.getChecksheetId());
            if (!checksheet.isPresent()) {
                throw new CustomException("Please provide valid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<User> currentLoggedInUser = utilityService.getCurrentLoggedInUser();
            if(Objects.isNull(checksheet.get().getPreparerUser()) || !Objects.equals(checksheet.get().getPreparerUser().getId(), currentLoggedInUser.get().getId())) {
                throw new CustomException("You have not access of this checksheet", HttpStatus.FORBIDDEN);
            }
            if(Objects.equals(ChecksheetStatusType.NEW, checksheet.get().getStatus()) ||
                    Objects.equals(ChecksheetStatusType.INVALIDATED, checksheet.get().getStatus())) {
                checksheet.get().setStatus(ChecksheetStatusType.CREATE_TEMPLATE);
                checksheetRepository.save(checksheet.get());
            }
            if(!Objects.equals(checksheet.get().getStatus(), ChecksheetStatusType.CREATE_TEMPLATE)) {
                throw new CustomException("You can not add new general field", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            ChksHeader chksHeader = new ChksHeader();
            if(!Objects.isNull(chksHeaderDTO.getId())) {
                Optional<ChksHeader> oldChksHeader = chksHeaderRepository.findById(chksHeaderDTO.getId());
                if(!oldChksHeader.isPresent()) {
                    throw new CustomException("Please provide valid id", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                chksHeader = oldChksHeader.get();
                chksHeader.setUpdatedBy(currentLoggedInUser.get());
                chksHeader.setUpdatedAt(new Date());
            } else {
                chksHeader.setCreatedBy(currentLoggedInUser.get());
            }
            List<Long> isExistName = chksHeaderDAO.getChksHeaderByName(chksHeader.getId(), chksHeaderDTO.getChecksheetId(), chksHeaderDTO.getName());
            if(!Objects.isNull(isExistName) && !isExistName.isEmpty()) {
                throw new CustomException("Name already exist", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            chksHeader.setName(chksHeaderDTO.getName());
            chksHeader.setIsTraceable(chksHeaderDTO.getIsTraceable());
            if(!Objects.isNull(chksHeaderDTO.getId())) {
                return new ResponseDTO<>(true, "Checksheet header edited successfully");
            }
            checksheetRepository.save(checksheet.get());

            if(!chksHeaderDTO.getIsResultColumn()) {
                Long chksHeaderParentIdByCheckSheetID = chksHeaderDAO.getChksHeaderParentIdByCheckSheetID(chksHeaderDTO.getId(), chksHeaderDTO.getChecksheetId(), false);
                if(!Objects.equals(chksHeaderParentIdByCheckSheetID, -1L) && Objects.equals(null, chksHeaderDTO.getChksHeaderId())
                && Objects.isNull(chksHeaderDTO.getId())) {
                    throw new CustomException("Please provide valid chksHeaderId", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                if(!Objects.equals(chksHeaderParentIdByCheckSheetID, -1L) &&
                        !Objects.equals(chksHeaderParentIdByCheckSheetID, chksHeaderDTO.getChksHeaderId())
                    && Objects.isNull(chksHeaderDTO.getId())) {
                    throw new CustomException("Please provide valid chksHeaderId", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                if (!Objects.isNull(chksHeaderDTO.getChksHeaderId())) {
                    Optional<ChksHeader> chksHeaderParent = chksHeaderRepository.findById(chksHeaderDTO.getChksHeaderId());
                    if (chksHeaderParent.isPresent()) {
                        chksHeader.setChksHeader(chksHeaderParent.get());
                    }
                }
            }
            chksHeader.setChecksheet(checksheet.get());
            chksHeader.setIsResultColumn(chksHeaderDTO.getIsResultColumn());
            chksHeaderRepository.save(chksHeader);
            return new ResponseDTO<>(true, "Checksheet header created successfully");
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getChksHeaderData(ChksHeaderDTO chksHeaderDTO) throws CustomException {
        try {
            if(Objects.isNull(chksHeaderDTO) ||
                    Objects.isNull(chksHeaderDTO.getChecksheetId()) || chksHeaderDTO.getChecksheetId().toString().isEmpty()) {
                throw new CustomException("Please provide checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<Checksheet> checksheet = checksheetRepository.findById(chksHeaderDTO.getChecksheetId());
            Map<String, Object> response = new HashMap<>();
            List<ChksHeaderDTO> chksHeaderResultFalse = chksHeaderDAO.getChksHeaderByChecksheetId(chksHeaderDTO.getChecksheetId(), false);
            List<ChksHeaderDTO> chksHeaderResultTrue = chksHeaderDAO.getChksHeaderByChecksheetId(chksHeaderDTO.getChecksheetId(), true);
            if(!chksHeaderResultFalse.isEmpty()) {
                if(chksHeaderResultFalse.size() == 1) {
                    response.put("childChksHeaderResultFalse", chksHeaderResultFalse.get(0).getId());
                } else {
                    List<Long> allChksHeaderResultFalseIds = chksHeaderResultFalse.stream()
                            .map(ChksHeaderDTO::getId)
                            .collect(Collectors.toList());
                    Long childChksHeaderResultFalse =
                            chksHeaderDAO.getChksHeaderByChksHeaderId(chksHeaderDTO.getChecksheetId(), allChksHeaderResultFalseIds, false);
                    response.put("childChksHeaderResultFalse", childChksHeaderResultFalse);
                }
            }
            response.put("headerResultFalse", chksHeaderResultFalse);
            response.put("headerResultTrue", chksHeaderResultTrue);
            response.put("checksheetStatus", checksheet.get().getStatus());
            return new ResponseDTO<>(true, "Data fetched successfully", response);
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> deleteChksHeaderData(ChksHeaderDTO chksHeaderDTO) throws CustomException {
        try {
            if(Objects.isNull(chksHeaderDTO) || Objects.isNull(chksHeaderDTO.getId())) {
                throw new CustomException("Please provide id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            ChksHeader chksHeader = chksHeaderRepository.findById(chksHeaderDTO.getId())
                    .orElseThrow(() -> new CustomException("Please provide valid id", HttpStatus.UNPROCESSABLE_ENTITY));
            if(!Objects.isNull(chksHeader.getIsResultColumn()) && !chksHeader.getIsResultColumn()) {
                Optional<ChksHeader> parentChksHeaderId = chksHeaderRepository.findByChksHeader_Id(chksHeaderDTO.getId());
                if(parentChksHeaderId.isPresent()) {
                    throw new CustomException("You can not delete parent column", HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }
            Checksheet checksheet = chksHeader.getChecksheet();
            if(!Objects.equals(ChecksheetStatusType.NOT_APPROVED, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.INVALIDATED, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.CREATE_TEMPLATE, checksheet.getStatus())) {
                throw new CustomException("You can not delete column", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            chksHeaderRepository.delete(chksHeader);
            return new ResponseDTO<>(true, "Checksheet header deleted successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createChksHdrSummaryReportLevel(ChksHdrSummaryReportLevelDTO chksHdrSummaryReportLevelDTO) throws CustomException {
        try{
            if(chksHdrSummaryReportLevelDTO.getChecksheetId() == null){
                throw new CustomException("Please provide Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
            }else if(chksHdrSummaryReportLevelDTO.getLevelOneHeaderIds().isEmpty()) {
                throw new CustomException("Please provide Summary Report Level One Headers", HttpStatus.UNPROCESSABLE_ENTITY);
            }else if(!chksHdrSummaryReportLevelDTO.getLevelTwoHeaderIds().isEmpty() && !chksHdrSummaryReportLevelDTO.getLevelOneHeaderIds().stream().noneMatch(chksHdrSummaryReportLevelDTO.getLevelTwoHeaderIds()::contains)) {
                throw new CustomException("Please Level 2 must not have Level 1 Headers", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<Checksheet> chkSheetOpt = checksheetRepository.findById(chksHdrSummaryReportLevelDTO.getChecksheetId());
            if(!chkSheetOpt.isPresent()){
                throw new CustomException("Please provide valid checksheet User ID", HttpStatus.UNPROCESSABLE_ENTITY);
            }else{
                Checksheet chkSheet = chkSheetOpt.get();
                User currentUser = utilityService.getCurrentLoggedInUser().get();
                if(chkSheet.getPreparerUser().getId() != currentUser.getId()){
                    throw new CustomException("You can not update Summary Report Level!!", HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }
            List<ChksHeader> chksHeaders = chksHeaderRepository.findByChecksheet_IdAndIsResultColumnFalseOrderById(chksHdrSummaryReportLevelDTO.getChecksheetId());
            chksHeaders.forEach(chksHdr -> {
                chksHdr.setSummaryReportLevel(null);
            });
            chksHeaderRepository.saveAll(chksHeaders);
            User usr = utilityService.getCurrentLoggedInUser().get();
            saveSummaryReportLevels(chksHdrSummaryReportLevelDTO.getLevelOneHeaderIds(),(byte)1,usr,chksHeaders);
            if(!chksHdrSummaryReportLevelDTO.getLevelTwoHeaderIds().isEmpty()) {
                saveSummaryReportLevels(chksHdrSummaryReportLevelDTO.getLevelTwoHeaderIds(), (byte) 2, usr,chksHeaders);
            }
            return new ResponseDTO<>(true, "Checksheet header created successfully");
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getChksHeaderSummaryReportLevel(ChksHeaderDTO chksHeaderDTO) throws CustomException {
        try{
            if(chksHeaderDTO.getChecksheetId() == null){
                throw new CustomException("Please provide Checksheet Id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<Checksheet> checksheet = checksheetRepository.findById(chksHeaderDTO.getChecksheetId());
            if (!checksheet.isPresent()) {
                throw new CustomException("Please provide valid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            List<ChksHeader>  levelOneChksHdrs = chksHeaderRepository.findByChecksheetIdAndSummaryReportLevel(chksHeaderDTO.getChecksheetId(),(byte)1);
            List<Long> levelOneHeaderIds = levelOneChksHdrs.stream().map(levelOneChksHdr -> levelOneChksHdr.getId()).collect(Collectors.toList());
            List<ChksHeader>  levelTwoChksHdrs = chksHeaderRepository.findByChecksheetIdAndSummaryReportLevel(chksHeaderDTO.getChecksheetId(),(byte)2);
            List<Long> levelTwoHeaderIds = levelTwoChksHdrs.stream().map(levelTwoChksHdr -> levelTwoChksHdr.getId()).collect(Collectors.toList());
            ChksHdrSummaryReportLevelDTO chksHdrSummaryReportLevelDTO = new ChksHdrSummaryReportLevelDTO();
            chksHdrSummaryReportLevelDTO.setLevelOneHeaderIds(levelOneHeaderIds);
            chksHdrSummaryReportLevelDTO.setLevelTwoHeaderIds(levelTwoHeaderIds);
            chksHdrSummaryReportLevelDTO.setChecksheetId(chksHeaderDTO.getChecksheetId());
            return new ResponseDTO<>(true, "Data fetched successfully", chksHdrSummaryReportLevelDTO);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private void saveSummaryReportLevels(List<Long> headerIds, Byte level, User usr,List<ChksHeader> chksHeaders) throws CustomException {
//        List<ChksHeader> chksHeaders = chksHeaderRepository.findByChecksheetIdAndIdIn(chksId, headerIds);
        List<ChksHeader> lvlChksHdrs = chksHeaders.stream().filter(chksHdr -> headerIds.contains(chksHdr.getId())).collect(Collectors.toList());
        if (lvlChksHdrs.size() == headerIds.size()) {
            lvlChksHdrs.forEach(chksHdr -> {
                chksHdr.setSummaryReportLevel(level);
                chksHdr.setUpdatedBy(usr);
            });
            chksHeaderRepository.saveAll(lvlChksHdrs);
        } else {
            throw new CustomException("Please provide valid Header Ids", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
