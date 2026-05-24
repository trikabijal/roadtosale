package com.checkSheet.entity;

import com.checkSheet.DTO.ChksQuestionResultMatrixDTO;
import com.checkSheet.DTO.UserDTO;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getMatrixData",
                classes = @ConstructorResult(
                        targetClass = ChksQuestionResultMatrixDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "chks_matrix_row_hdr", type = String.class),
                                @ColumnResult(name = "chks_matrix_col_hdr", type = String.class),
                                @ColumnResult(name = "data", type = String.class),
                                @ColumnResult(name = "comment", type = String.class),
                                @ColumnResult(name = "row_id", type = Long.class),
                                @ColumnResult(name = "column_id", type = Long.class),
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getMatrixDataByChksId",
                classes = @ConstructorResult(
                        targetClass = ChksQuestionResultMatrixDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "chks_matrix_row_hdr", type = String.class),
                                @ColumnResult(name = "chks_matrix_col_hdr", type = String.class),
                                @ColumnResult(name = "data", type = String.class),
                                @ColumnResult(name = "comment", type = String.class),
                                @ColumnResult(name = "row_id", type = Long.class),
                                @ColumnResult(name = "column_id", type = Long.class),
                                @ColumnResult(name = "chks_question_result_id", type = Long.class),
                        }
                )
        ),
})
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(
    name = "chks_question_result_matrices",
    uniqueConstraints={
        @UniqueConstraint(name = "uk_chks_question_rslt_id_chks_mtrx_row_hdr_chks_mtrx_col_hdr", columnNames ={"chks_question_result_id","chks_matrix_row_hdr","chks_matrix_col_hdr"})
    }
)
public class ChksQuestionResultMatrix {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chks_question_result_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_result_matrices_chks_question_result_id_chks_question_results_id"))
    private ChksQuestionResult chksQuestionResult;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checksheet_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_result_matrices_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

    @Column(name = "chks_matrix_row_hdr", columnDefinition = "varchar ")
    private String chksMatrixRowHdr;

    @Column(name = "chks_matrix_col_hdr", columnDefinition = "varchar ")
    private String chksMatrixColHdr;

    @Column(name = "data", columnDefinition = "varchar ")
    private String data;

    @Column(name = "comment", columnDefinition = "varchar ")
    private String comment;

    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "column_id")
    private Long columnId;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_result_matrices_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_result_matrices_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_result_matrices_updated_by_user_id"))
    private User updatedBy;

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
}