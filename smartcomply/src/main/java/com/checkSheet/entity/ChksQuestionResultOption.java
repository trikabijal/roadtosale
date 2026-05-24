package com.checkSheet.entity;

import com.checkSheet.DTO.ChksQuestionResultOptionDTO;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@Getter
@Setter
@Entity
@Table(name = "chks_question_result_options")
@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getOptionData",
                classes = @ConstructorResult(
                        targetClass = ChksQuestionResultOptionDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "option", type = String.class),
                                @ColumnResult(name = "judgement", type = String.class),
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getOptionDataByChksId",
                classes = @ConstructorResult(
                        targetClass = ChksQuestionResultOptionDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "option", type = String.class),
                                @ColumnResult(name = "judgement", type = String.class),
                                @ColumnResult(name = "chks_question_result_id", type = Long.class),
                        }
                )
        )
})
public class ChksQuestionResultOption {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chks_question_result_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_result_options_chks_question_result_id_chks_question_results_id"))
    private ChksQuestionResult chksQuestionResult;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checksheet_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_result_options_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

    @Column(name = "option", columnDefinition = "varchar ")
    private String option;

    @Column(name = "judgement", nullable = false, columnDefinition = "varchar ")
    private String judgement;


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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_result_headers_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_result_headers_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_result_headers_updated_by_user_id"))
    private User updatedBy;

    public void setOption(String option) {
        this.option = option != null ? option.trim() : null;
    }

    public void setJudgement(String judgement) {
        this.judgement = judgement != null ? judgement.trim() : null;
    }
}