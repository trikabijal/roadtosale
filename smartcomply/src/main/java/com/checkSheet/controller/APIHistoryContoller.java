package com.checkSheet.controller;

import com.checkSheet.exception.CustomException;
import com.checkSheet.service.APIHistoryService;
import com.checkSheet.service.ChecksheetApprovalService;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@Validated
@RestController
@ResponseBody
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/apiHistory")
public class APIHistoryContoller {
    @Autowired
    private APIHistoryService apiHistoryService;

    @Scheduled(cron = "0 0 4 * * *")
    public void deleteAllByCreatedDateBefore() throws CustomException, IOException, JSONException {
        apiHistoryService.deleteAllByCreatedDateBefore(null);
    }
}
