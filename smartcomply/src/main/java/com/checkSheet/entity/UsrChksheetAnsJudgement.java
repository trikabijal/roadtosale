package com.checkSheet.entity;

import com.checkSheet.DTO.ChksGeneralFieldValueDTO;
import com.checkSheet.DTO.UsrChecksheetAnsJudgementDTO;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;
@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getUsrChecksheetAnsJudgements",
                classes = @ConstructorResult(
                        targetClass = UsrChecksheetAnsJudgementDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "inspection_id", type = Long.class),
                                @ColumnResult(name = "chks_question_id", type = Long.class),
                                @ColumnResult(name = "judgement", type = String.class),
                                @ColumnResult(name = "remarks", type = String.class)
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getChecksheetSummaryQueJudgements",
                classes = @ConstructorResult(
                        targetClass = UsrChecksheetAnsJudgementDTO.class,
                        columns = {
                                @ColumnResult(name = "inspection_id", type = Long.class),
                                @ColumnResult(name = "chks_question_id", type = Long.class),
                                @ColumnResult(name = "judgement", type = String.class),
                                @ColumnResult(name = "submitted_at", type = Date.class),
                                @ColumnResult(name = "first_name", type = String.class),
                                @ColumnResult(name = "last_name", type = String.class),
                                @ColumnResult(name = "username", type = String.class),
                        }
                )
        ),
})
@Getter
@Setter
@Entity
@Table(name = "usr_chksheet_ans_judgements", indexes = {
        @Index(name = "idx_usr_chksheet_ans_judgements_judgement", columnList = "judgement"),
        @Index(name = "idx_usr_chksheet_ans_judgements_user_checksheet_id", columnList = "inspection_id"),
        @Index(name = "idx_usr_chksheet_ans_judgements_chks_question_id", columnList = "chks_question_id"),
})
public class UsrChksheetAnsJudgement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_usr_chksheet_ans_judgements_user_checksheet_id_user_checksheets_id"))
    private com.checkSheet.entity.Inspection inspection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chks_question_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_usr_chksheet_ans_judgements_chks_question_id_chks_questions_id"))
    private ChksQuestion chksQuestion;

    @Column(name = "judgement", length = 10)
    private String judgement;

    @Column(name = "remarks", columnDefinition = "varchar ")
    private String remarks;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_usr_chksheet_ans_judgements_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_usr_chksheet_ans_judgements_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_usr_chksheet_ans_judgements_updated_by_user_id"))
    private User updatedBy;

}