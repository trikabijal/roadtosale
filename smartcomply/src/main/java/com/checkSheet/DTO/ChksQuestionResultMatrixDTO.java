package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;


@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChksQuestionResultMatrixDTO {
    private Long id;
    private Long checksheetId;
    private Long chksHeaderId;
    private Long chksQuestionId;
    private Long chksQuestionResultId;
    private String chksMatrixRowHdr;
    private String chksMatrixColHdr;
    private String data;
    private String comment;
    private Long rowId;
    private Long columnId;
    private Date createdAt;
    private Date updatedAt;
    private String userAnswer;
    private Short judgement;
    private Integer orderNo;

    public void setChksMatrixRowHdr(String chksMatrixRowHdr) {
        this.chksMatrixRowHdr = chksMatrixRowHdr != null ? chksMatrixRowHdr.trim() : null;
    }

    public void setChksMatrixColHdr(String chksMatrixColHdr) {
        this.chksMatrixColHdr = chksMatrixColHdr != null ? chksMatrixColHdr.trim() : null;
    }

    public void setDate(String data) {
        this.data = data != null ? data.trim() : null;
    }

    public void setComment(String comment) {
        this.comment = comment != null ? comment.trim() : null;
    }

    //getMatrixData
    public ChksQuestionResultMatrixDTO(Long id, String chksMatrixRowHdr, String chksMatrixColHdr, String data, String comment, Long rowId, Long columnId) {
        this.id = id;
        this.chksMatrixRowHdr = chksMatrixRowHdr;
        this.chksMatrixColHdr = chksMatrixColHdr;
        this.data = data;
        this.comment = comment;
        this.rowId = rowId;
        this.columnId = columnId;
    }

    public ChksQuestionResultMatrixDTO(Long id, String chksMatrixRowHdr, String chksMatrixColHdr, String data, String comment, Long rowId, Long columnId,Long chksQuestionResultId) {
        this.id = id;
        this.chksMatrixRowHdr = chksMatrixRowHdr;
        this.chksMatrixColHdr = chksMatrixColHdr;
        this.data = data;
        this.comment = comment;
        this.rowId = rowId;
        this.columnId = columnId;
        this.chksQuestionResultId = chksQuestionResultId;
    }
}
