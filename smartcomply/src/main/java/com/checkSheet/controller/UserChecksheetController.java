package com.checkSheet.controller;

import java.util.List;

import com.checkSheet.DTO.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.UserChecksheetService;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/userChecksheet")
public class UserChecksheetController {
    @Autowired
    private UserChecksheetService userChecksheetService;

    @PostMapping("getUserChecksheets")
    public ResponseEntity<?> getUserChecksheets(@RequestBody(required = false) ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.getUserChecksheets(checksheetDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    @GetMapping("getDeclinedUserChecksheets")
    public ResponseEntity<?> getDeclinedUserChecksheets() throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.getDeclinedUserChecksheets());
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("createOrUpdate")
    public ResponseEntity<?> createOrUpdateUserChecksheets(@RequestBody List<UserChecksheetDTO> userChecksheetDTOs) throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.createOrUpdateUserChecksheets(userChecksheetDTOs));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    public ResponseEntity<?> createOrUpdateUserChecksheet(@RequestBody UserChecksheetDTO userChecksheetDTO) throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.createOrUpdateUserChecksheet(userChecksheetDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("getUserChksDetail")
    public ResponseEntity<?> getUserChecksheetDetails(@RequestBody UserChecksheetDTO userChecksheetDTO) throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.getUserChecksheetDetails(userChecksheetDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    @PostMapping("createOrUpdateUserChksGnrlFieldVals")
    public ResponseEntity<?> createOrUpdateUserChksGnrlFieldVals(@RequestBody List<ChksGeneralFieldValueDTO> UserChksGnrlFieldVals) throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.createOrUpdateUserChksGnrlFieldVals(UserChksGnrlFieldVals));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    @PostMapping("createOrUpdateUserChksAns")
    public ResponseEntity<?> createOrUpdateUserChksAns(@RequestBody List<UserChecksheetAnswerDTO> userChecksheetAnswers) throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.createOrUpdateUserChksAns(userChecksheetAnswers));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("createUserChksAnsFile")
    public ResponseEntity<?> createUserChksAnsFile(@ModelAttribute UserChecksheetAnswerFileDTO userChecksheetAnswerFileDTO) throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.createUserChksAnsFile(userChecksheetAnswerFileDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("deleteUserChksAnsFile")
    public ResponseEntity<?> deleteUserChksAnsFile(@RequestBody UserChecksheetAnswerFileDTO userChecksheetAnswerFileDTO) throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.deleteUserChksAnsFile(userChecksheetAnswerFileDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("createOrUpdateUserChksMtrxAns")
    public ResponseEntity<?> createOrUpdateUserChksMtrxAns(@RequestBody List<UserChecksheetAnswerDTO> userChecksheetAnswers) throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.createOrUpdateUserChksMtrxAns(userChecksheetAnswers));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    @PostMapping("createOrUpdateUserChksTraceValues")
    public ResponseEntity<?> createOrUpdateUserChksTraceValues(@RequestBody List<UserChecksheetTraceValueDTO> userChecksheetTraceValueDTO) throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.createOrUpdateUserChksTraceValues(userChecksheetTraceValueDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("createOrUpdateUserChksJudgements")
    public ResponseEntity<?> createOrUpdateUserChksJudgements(@RequestBody List<UsrChecksheetAnsJudgementDTO> usrChecksheetAnsJudgementDTOS) throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.createOrUpdateUserChksJudgements(usrChecksheetAnsJudgementDTOS));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("createOrUpdateUserChksJudgementFile")
    public ResponseEntity<?> createOrUpdateUserChksJudgementFile(@ModelAttribute UsrChksheetAnsJudgementFileDTO usrChksheetAnsJudgementFileDTO) throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.createOrUpdateUserChksJudgementFile(usrChksheetAnsJudgementFileDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("deleteUserChksJudgementFile")
    public ResponseEntity<?> deleteUserChksJudgementFile(@RequestBody UsrChksheetAnsJudgementFileDTO usrChksheetAnsJudgementFileDTO) throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.deleteUserChksJudgementFile(usrChksheetAnsJudgementFileDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getRespectedUserChecksheet")
    public ResponseEntity<?> getRespectedUserChecksheet(@RequestBody(required=false) ChecksheetDTO checksheetDTO) throws CustomException {
        try {
              return ResponseEntity.ok(userChecksheetService.getRespectedUserChecksheet(checksheetDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getUserChecksheetWithAnswers")
    public ResponseEntity<?> getUserChecksheetWithAnswers(@RequestBody UserChecksheetDTO userChecksheetDTO) throws CustomException {
        try {
            return ResponseEntity.ok(userChecksheetService.getUserChecksheetWithAnswers(userChecksheetDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("downloadUserChecksheetWithAnswers")
    public ResponseEntity<?> downloadUserChecksheetWithAnswers(@RequestBody UserChecksheetDTO userChecksheetDTO, HttpServletResponse response) {
        try {
//            response.setContentType("application/vnd.ms-excel");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

            response.setHeader("Content-Disposition", "attachment; filename=Inspection.xlsx");
            response.setHeader("Pragma", "public");
            response.setHeader("Cache-Control", "no-store");
            response.addHeader("Cache-Control", "max-age=0");
            userChecksheetService.downloadUserChecksheetWithAnswers(userChecksheetDTO,response);
            return ResponseEntity.ok().build();
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("downloadUserChecksheetWithAnswersPdf")
    public ResponseEntity<?> downloadUserChecksheetWithAnswersPdf(@RequestBody UserChecksheetDTO userChecksheetDTO, HttpServletResponse response) {
        try {
            response.setContentType("application/pdf");
            response.setHeader("Content-Disposition", "attachment; filename=Inspection.pdf");
            response.setHeader("Pragma", "public");
            response.setHeader("Cache-Control", "no-store");
            response.addHeader("Cache-Control", "max-age=0");
            userChecksheetService.downloadUserChecksheetWithAnswersPdf(userChecksheetDTO, response);
            return ResponseEntity.ok().build();
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}