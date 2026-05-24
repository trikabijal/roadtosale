package com.checkSheet.service;

import java.util.List;

import com.checkSheet.DTO.*;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import jakarta.servlet.http.HttpServletResponse;

public interface UserChecksheetService {
    ResponseDTO<?> getUserChecksheets(ChecksheetDTO checksheetDTO) throws CustomException;

    ResponseDTO<UserChecksheetDTO> createOrUpdateUserChecksheet(UserChecksheetDTO userChecksheetDTO) throws CustomException;
    ResponseDTO<?> createOrUpdateUserChecksheets(List<UserChecksheetDTO> userChecksheetDTOs) throws CustomException;

    ResponseDTO<?> getUserChecksheetDetails(UserChecksheetDTO userChecksheetDTO) throws CustomException;

    ResponseDTO<?> createOrUpdateUserChksAns(List<UserChecksheetAnswerDTO> userChecksheetAnswers) throws CustomException;

    ResponseDTO<?> createUserChksAnsFile(UserChecksheetAnswerFileDTO userChecksheetAnswerFileDTO) throws CustomException;
    ResponseDTO<?> deleteUserChksAnsFile(UserChecksheetAnswerFileDTO userChecksheetAnswerFileDTO) throws CustomException;

    ResponseDTO<?> createOrUpdateUserChksMtrxAns(List<UserChecksheetAnswerDTO> userChecksheetAnswers) throws CustomException;

    ResponseDTO<?> createOrUpdateUserChksJudgements(List<UsrChecksheetAnsJudgementDTO> usrChecksheetAnsJudgementDTOS) throws CustomException;

    ResponseDTO<?> getDeclinedUserChecksheets() throws CustomException;

    ResponseDTO<?> getRespectedUserChecksheet(ChecksheetDTO checksheetDTO) throws CustomException;

    ResponseDTO<?> createOrUpdateUserChksGnrlFieldVals(List<ChksGeneralFieldValueDTO> userChksGnrlFieldVals) throws CustomException;
    ResponseDTO<?> createOrUpdateUserChksTraceValues(List<UserChecksheetTraceValueDTO> userChecksheetTraceValueDTO) throws CustomException;

    ResponseDTO<?> createOrUpdateUserChksJudgementFile(UsrChksheetAnsJudgementFileDTO usrChksheetAnsJudgementFileDTO) throws CustomException;

    ResponseDTO<?>  deleteUserChksJudgementFile(UsrChksheetAnsJudgementFileDTO usrChksheetAnsJudgementFileDTO) throws CustomException;

    ResponseDTO<?> getUserChecksheetWithAnswers(UserChecksheetDTO userChecksheetDTO) throws CustomException;

    void downloadUserChecksheetWithAnswers(UserChecksheetDTO userChecksheetDTO, HttpServletResponse response) throws CustomException;
    void downloadUserChecksheetWithAnswersPdf(UserChecksheetDTO userChecksheetDTO, HttpServletResponse response) throws CustomException;

}
