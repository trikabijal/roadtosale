package com.checkSheet.service;

public interface APIHistoryService {

    void deleteAllByCreatedDateBefore(Integer daysBefore);
}
