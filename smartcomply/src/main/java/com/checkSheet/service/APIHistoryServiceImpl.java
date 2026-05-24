package com.checkSheet.service;

import com.checkSheet.DAO.APIHistoryDAO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class APIHistoryServiceImpl implements APIHistoryService {
    @Autowired
    private APIHistoryDAO apiHistoryDAO;

    @Value("${apiHistory.deleteDaysBefore:15}")
    private int apiHistoryDeleteDaysBefore = 15;

    @Override
    public void deleteAllByCreatedDateBefore(Integer daysBefore) {
        LocalDate currentDate = LocalDate.from(LocalDateTime.now());
        if (Objects.equals(daysBefore, null)) {
            daysBefore = apiHistoryDeleteDaysBefore;
        }
        currentDate = currentDate.minusDays(daysBefore);
        apiHistoryDAO.deleteAllByCreatedDateBefore(currentDate.toString());
    }


}
