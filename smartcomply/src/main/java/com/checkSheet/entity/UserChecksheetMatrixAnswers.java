package com.checkSheet.entity;

import com.checkSheet.DTO.UserChecksheetAnswerDTO;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;
@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getUserChksMtrxAnswers",
                classes = @ConstructorResult(
                        targetClass = UserChecksheetAnswerDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "inspection_id", type = Long.class),
                                @ColumnResult(name = "chks_question_result_id", type = Long.class),
                                @ColumnResult(name = "chks_question_result_matrix_id", type = Long.class),
                                @ColumnResult(name = "result", type = String.class),
                                @ColumnResult(name = "order_no", type = Integer.class),
                                @ColumnResult(name = "judgement", type = Short.class),
                                @ColumnResult(name = "mc_result", type = String.class),
                                @ColumnResult(name = "answered_at", type = Date.class)
                        }
                )
        )
})
@Getter
@Setter
@Entity
@Table(
    name = "user_checksheet_matrix_answers",
    uniqueConstraints={
        @UniqueConstraint(name = "uk_inspection_id_chks_qtion_rslt_id_chks_qtion_rslt_mtrx_id",
                columnNames ={"inspection_id","chks_question_result_id","chks_question_result_matrix_id","order_no"})
    }
)
public class UserChecksheetMatrixAnswers {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_matrix_answers_user_checksheet_id_user_checksheets_id"))
    private com.checkSheet.entity.Inspection inspection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chks_question_result_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_matrix_answers_chks_question_result_id_chks_question_results_id"))
    private ChksQuestionResult chksQuestionResult;

    @Column(name = "result", columnDefinition = "varchar ")
    private String result;

    @Column(name = "mc_result", columnDefinition = "varchar ")
    private String mcResult;

    @Column(name = "order_no", nullable = false)
    private Integer orderNo = 1;

    @Column(name = "judgement")
    private Short judgement = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chks_question_result_matrix_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_id_chks_question_rslt_id_chks_question_result_mtrx_id"))
    private ChksQuestionResultMatrix chksQuestionResultMatrix;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chks_question_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_matrix_answers_chks_question_id_chks_questions_id"))
    private ChksQuestion chksQuestion;

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

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "answered_at", nullable = false, length = 29, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Date answeredAt = new Date();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_matrix_answers_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_matrix_answers_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_matrix_answers_updated_by_user_id"))
    private User updatedBy;

}