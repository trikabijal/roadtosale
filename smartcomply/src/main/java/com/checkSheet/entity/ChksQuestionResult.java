package com.checkSheet.entity;

import com.checkSheet.DTO.ChksQuestionResultDTO;
import com.checkSheet.DTO.RoleDTO;
import com.checkSheet.constant.ChksQuestionResultObjectiveType;
import com.checkSheet.constant.ChksQuestionResultType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;
import java.util.List;

@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getResultData",
                classes = @ConstructorResult(
                        targetClass = ChksQuestionResultDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "checksheet_id", type = Long.class),
                                @ColumnResult(name = "chks_header_id", type = Long.class),
                                @ColumnResult(name = "chks_question_id", type = Long.class),
                                @ColumnResult(name = "answer_type", type = ChksQuestionResultType.class),
                                @ColumnResult(name = "objective_type", type = ChksQuestionResultObjectiveType.class),
                                @ColumnResult(name = "upper_limit", type = Double.class),
                                @ColumnResult(name = "lower_limit", type = Double.class),
                                @ColumnResult(name = "unit", type = String.class),
                                @ColumnResult(name = "matrix_name", type = String.class),
                                @ColumnResult(name = "matrix_row_header_names", type = String.class),
                                @ColumnResult(name = "matrix_column_header_names", type = String.class),
                                @ColumnResult(name = "no_of_results", type = Long.class),
                                @ColumnResult(name = "no_of_rows", type = Long.class),
                                @ColumnResult(name = "no_of_columns", type = Long.class),
                                @ColumnResult(name = "chks_matrix_row_name", type = String.class),
                                @ColumnResult(name = "chks_matrix_col_name", type = String.class),
                                @ColumnResult(name = "is_optional", type = Boolean.class),

                        }
                )
        ),
})
@Getter
@Setter
@Entity
@Table(
    name = "chks_question_results",
    uniqueConstraints={
        @UniqueConstraint(name = "uk_chks_question_results_chks_header_id_chks_question_id", columnNames ={"chks_header_id","chks_question_id"})
    }
)
public class ChksQuestionResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chks_header_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_results_chks_header_id_chks_headers_id"))
    private ChksHeader chksHeader;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chks_question_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_results_chks_question_id_chks_questions_id"))
    private ChksQuestion chksQuestion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checksheet_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_results_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

    @Column(name = "answer_type", columnDefinition = "varchar")
    @Enumerated(EnumType.STRING)
    private ChksQuestionResultType answerType;

    @Column(name = "objective_type", columnDefinition = "varchar")
    @Enumerated(EnumType.STRING)
    private ChksQuestionResultObjectiveType objectiveType;

    @Column(name = "upper_limit")
    private Double upperLimit;

    @Column(name = "lower_limit")
    private Double lowerLimit;

    @Column(name = "unit", columnDefinition = "varchar ")
    private String unit;

//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "chks_question_result_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_results_chks_question_result_id_chks_question_results_id"))
//    private ChksQuestionResult chksQuestionResult;

    @Column(name = "matrix_name", columnDefinition = "varchar ")
    private String matrixName;

    @Column(name = "chks_matrix_row_name", columnDefinition = "varchar ")
    private String chksMatrixRowName;

    @Column(name = "chks_matrix_col_name", columnDefinition = "varchar ")
    private String chksMatrixColName;

    @Column(name = "matrix_file_location", columnDefinition = "varchar ")
    private String matrixFileLocation;

    @Column(name = "matrix_row_header_names", columnDefinition = "_varchar ")
    private List<String> matrixRowHeaderNames;

    @Column(name = "matrix_column_header_names", columnDefinition = "_varchar ")
    private List<String> matrixColumnHeaderNames;

    @Column(name = "no_of_results")
    private Long noOfResults;

    @Column(name = "no_of_rows")
    private Long noOfRows;

    @Column(name = "no_of_columns")
    private Long noOfColumns;

    @Column(name = "is_optional", columnDefinition = "bool default false")
    private Boolean isOptional = false;

//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "chks_question_rslt_hdr_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_results_chks_question_rslt_hdr_id_chks_question_result_headers_id"))
//    private ChksQuestionResultHeader chksQuestionRsltHdr;

    @Temporal(TemporalType.TIMESTAMP)
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, length = 29, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Date createdAt = new Date();

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "updated_at", length = 29, columnDefinition = "TIMESTAMP")
    private Date updatedAt;

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = new Date();
    }

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "deleted_at", length = 29)
    private Date deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_results_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_results_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_results_updated_by_user_id"))
    private User updatedBy;

    public void setUnit(String unit) {
        this.unit = unit != null ? unit.trim() : null;
    }

    public void setMatrixName(String matrixName) {
        this.matrixName = matrixName != null ? matrixName.trim() : null;
    }
}