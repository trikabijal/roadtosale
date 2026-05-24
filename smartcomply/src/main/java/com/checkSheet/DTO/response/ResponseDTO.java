package com.checkSheet.DTO.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.util.Date;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseDTO<T> {
    private Boolean status = true;
    private String message;
    private T data;

    //For pagination
    private Integer currentPage;
    private Integer totalPages;
    private Integer pageSize;
    private Long totalRecords;
    private Date lastUpdatedTime = new Date();
    private HttpStatus httpStatus;
    public ResponseDTO(T data) {
        super();
        this.data = data;
    }
    public ResponseDTO(String msg, HttpStatus httpStatus) {
        super();
        this.message = msg;
        this.httpStatus = httpStatus;
    }
    public ResponseDTO(String msg, T data) {
        super();
        this.message = msg;
        this.data = data;
    }
    public ResponseDTO(Boolean status, String msg) {
        super();
        this.status = status;
        this.message = msg;
    }

    public ResponseDTO(Boolean status, String msg, T data) {
        super();
        this.status = status;
        this.message = msg;
        this.data = data;
    }

    public ResponseDTO(String msg, T data, long totalRecords) {
        super();
        this.message = msg;
        this.data = data;
        this.totalRecords = totalRecords;
    }

    public ResponseDTO(String msg, T data, long totalRecords, Integer totalPages, Integer currentPage, Integer pageSize) {
        super();
        this.message = msg;
        this.data = data;
        this.totalRecords = totalRecords;
        this.totalPages = totalPages;
        this.currentPage = currentPage;
        this.pageSize = pageSize;
    }
}
