package com.checkSheet.controller;

import com.checkSheet.DTO.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.DashboardService;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private DashboardService dashboardService;

    @PostMapping("/getRespectedChecksheet")
    public ResponseEntity<?> getRespectedChecksheet(@RequestBody DashboardDTO dashboardDTO) {
        try {
            return ResponseEntity.ok(dashboardService.getRespectedChecksheet(dashboardDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getRespectedQuestions")
    public ResponseEntity<?> getRespectedQuestions(@RequestBody DashboardDTO dashboardDTO) {
        try {
            return ResponseEntity.ok(dashboardService.getRespectedQuestions(dashboardDTO));
        } catch (CustomException e) {
            return ResponseEntity
                .status(e.getHttpStatus())
                .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getChecksheetSummaryData")
    public ResponseEntity<?> getChecksheetSummaryData(@RequestBody DashboardDTO dashboardDTO) {
        try {
            return ResponseEntity.ok(dashboardService.getChecksheetSummaryData(dashboardDTO));
        } catch (CustomException e) {
            return ResponseEntity
                .status(e.getHttpStatus())
                .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("downloadChecksheetSummaryData")
    public ResponseEntity<?> downloadChecksheetSummaryData(@RequestBody DashboardDTO dashboardDTO, HttpServletResponse response) {
        try {
            response.setContentType("application/vnd.ms-excel");
            response.setHeader("Content-Disposition", "attachment; filename=ChecksheetSummaryData.xlsx");
            response.setHeader("Pragma", "public");
            response.setHeader("Cache-Control", "no-store");
            response.addHeader("Cache-Control", "max-age=0");
            dashboardService.downloadChecksheetSummaryData(dashboardDTO,response);
            return ResponseEntity.ok().build();
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }



    @PostMapping("/getPlanVsActualData")
    public ResponseEntity<?> getPlanVsActualData(@RequestBody DashboardDTO dashboardDTO) {
        try {
            return ResponseEntity.ok(dashboardService.getPlanVsActualData(dashboardDTO));
        } catch (CustomException e) {
            return ResponseEntity
                .status(e.getHttpStatus())
                .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getRespectedChecksheetHeaderData")
    public ResponseEntity<?> getRespectedChecksheetHeaderData(@RequestBody DashboardDTO dashboardDTO) {
        try {
            return ResponseEntity.ok(dashboardService.getRespectedChecksheetHeaderData(dashboardDTO));
        } catch (CustomException e) {
            return ResponseEntity
                .status(e.getHttpStatus())
                .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getRespectedChecksheetQuestionsData")
    public ResponseEntity<?> getRespectedChecksheetQuestionsData(@RequestBody DashboardDTO dashboardDTO) {
        try {
            return ResponseEntity.ok(dashboardService.getRespectedChecksheetQuestionsData(dashboardDTO));
        } catch (CustomException e) {
            return ResponseEntity
                .status(e.getHttpStatus())
                .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/trendChart")
    public ResponseEntity<?> getTrendChartData(@RequestBody TrendChartDTO trendChartDTO) {
        try {
            return ResponseEntity.ok(dashboardService.getTrendChartData(trendChartDTO));
        } catch (CustomException e) {
            return ResponseEntity
                .status(e.getHttpStatus())
                .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("downloadTrendChartData")
    public ResponseEntity<?> downloadTrendChartData(@RequestBody TrendChartDTO trendChartDTO, HttpServletResponse response) {
        try {
            response.setContentType("application/vnd.ms-excel");
            response.setHeader("Content-Disposition", "attachment; filename=TrendChartData.xlsx");
            response.setHeader("Pragma", "public");
            response.setHeader("Cache-Control", "no-store");
            response.addHeader("Cache-Control", "max-age=0");
            dashboardService.downloadTrendChartData(trendChartDTO,response);
            return ResponseEntity.ok().build();
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("downloadPlanVsActualData")
    public ResponseEntity<?> downloadPlanVsActualData(@RequestBody DashboardDTO dashboardDTO, HttpServletResponse response) {
        try {
            response.setContentType("application/vnd.ms-excel");
            response.setHeader("Content-Disposition", "attachment; filename=PlanVsActualData.xlsx");
            response.setHeader("Pragma", "public");
            response.setHeader("Cache-Control", "no-store");
            response.addHeader("Cache-Control", "max-age=0");
            dashboardService.downloadPlanVsActualData(dashboardDTO,response);
            return ResponseEntity.ok().build();
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getCompletionFunnelData")
    public ResponseEntity<?> getCompletionFunnelData(@RequestBody CompletionFunnelDTO completionFunnelDTO) {
        try {
            return ResponseEntity.ok(dashboardService.getCompletionFunnelData(completionFunnelDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getComplianceHeatmapData")
    public ResponseEntity<?> getComplianceHeatmapData(@RequestBody ComplianceHeatmapDTO complianceHeatmapDTO) {
        try {
            return ResponseEntity.ok(dashboardService.getComplianceHeatmapData(complianceHeatmapDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getRecentSubmissions")
    public ResponseEntity<?> getRecentSubmissions(@RequestBody RecentSubmissionDTO recentSubmissionDTO) {
        try {
            return ResponseEntity.ok(dashboardService.getRecentSubmissions(recentSubmissionDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getTopNonConformingQuestions")
    public ResponseEntity<?> getTopNonConformingQuestions(@RequestBody NonConformingQuestionDTO nonConformingQuestionDTO) {
        try {
            return ResponseEntity.ok(dashboardService.getTopNonConformingQuestions(nonConformingQuestionDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
} 