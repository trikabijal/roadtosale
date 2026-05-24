package com.checkSheet.service;

import com.checkSheet.DTO.*;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import jakarta.servlet.http.HttpServletResponse;

public interface DashboardService {
    ResponseDTO<?> getRespectedChecksheet(DashboardDTO dashboardDTO) throws CustomException;
    ResponseDTO<?> getRespectedQuestions(DashboardDTO dashboardDTO) throws CustomException;
    ResponseDTO<?> getChecksheetSummaryData(DashboardDTO dashboardDTO) throws CustomException;
    ResponseDTO<?> getPlanVsActualData(DashboardDTO dashboardDTO) throws CustomException;
    ResponseDTO<?> getRespectedChecksheetHeaderData(DashboardDTO dashboardDTO) throws CustomException;
    ResponseDTO<?> getRespectedChecksheetQuestionsData(DashboardDTO dashboardDTO) throws CustomException;
    ResponseDTO<?> getTrendChartData(TrendChartDTO trendChartDTO) throws CustomException;

    void downloadChecksheetSummaryData(DashboardDTO dashboardDTO, HttpServletResponse response) throws CustomException;

    void downloadTrendChartData(TrendChartDTO trendChartDTO, HttpServletResponse response) throws CustomException;

    void downloadPlanVsActualData(DashboardDTO dashboardDTO, HttpServletResponse response) throws CustomException;

    // New methods for dashboard widgets
    ResponseDTO<?> getCompletionFunnelData(CompletionFunnelDTO completionFunnelDTO) throws CustomException;
    ResponseDTO<?> getComplianceHeatmapData(ComplianceHeatmapDTO complianceHeatmapDTO) throws CustomException;
    ResponseDTO<?> getRecentSubmissions(RecentSubmissionDTO recentSubmissionDTO) throws CustomException;
    ResponseDTO<?> getTopNonConformingQuestions(NonConformingQuestionDTO nonConformingQuestionDTO) throws CustomException;
}
