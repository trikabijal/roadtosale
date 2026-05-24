package com.checkSheet.entity;

import java.util.Date;

import org.hibernate.annotations.CreationTimestamp;

import com.checkSheet.DTO.TrendChartDTO;
import com.checkSheet.DTO.UserChecksheetAnswerDTO;

import jakarta.persistence.Column;
import jakarta.persistence.ColumnResult;
import jakarta.persistence.ConstructorResult;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SqlResultSetMapping;
import jakarta.persistence.SqlResultSetMappings;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getUserChksAnswers",
                classes = @ConstructorResult(
                        targetClass = UserChecksheetAnswerDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "inspection_id", type = Long.class),
                                @ColumnResult(name = "chks_question_result_id", type = Long.class),
                                @ColumnResult(name = "answer", type = String.class),
                                @ColumnResult(name = "chks_question_rslt_option_id", type = Long.class),
                                @ColumnResult(name = "judgement", type = Short.class),
                                @ColumnResult(name = "answered_at", type = Date.class),
                                @ColumnResult(name = "is_not_applicable", type = Boolean.class)
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getTrendChartData",
                classes = @ConstructorResult(
                        targetClass = TrendChartDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "user_checksheet_answers_id", type = Long.class),
                                @ColumnResult(name = "chks_question_id", type = Long.class),
                                @ColumnResult(name = "judgement", type = Short.class),
                                @ColumnResult(name = "check_date", type = Date.class),
                                @ColumnResult(name = "answer_date", type = Date.class),
                                @ColumnResult(name = "submitted_at", type = Date.class),
                                @ColumnResult(name = "status", type = String.class),
                                @ColumnResult(name = "answer", type = String.class),
                                @ColumnResult(name = "first_name", type = String.class),
                                @ColumnResult(name = "last_name", type = String.class),
                                @ColumnResult(name = "username", type = String.class)
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getChecksheetSummaryAnswers",
                classes = @ConstructorResult(
                        targetClass = UserChecksheetAnswerDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "inspection_id", type = Long.class),
                                @ColumnResult(name = "chks_question_result_id", type = Long.class),
                                @ColumnResult(name = "answer", type = String.class),
                                @ColumnResult(name = "chks_question_rslt_option_id", type = Long.class),
                                @ColumnResult(name = "chks_question_id", type = Long.class),
                                @ColumnResult(name = "judgement", type = Short.class),
                        }
                )
        )
})
@Getter
@Setter
@Entity
@Table(
    name = "user_checksheet_answers",
    uniqueConstraints={
        @UniqueConstraint( name = "uk_inspection_id_chks_question_result_id", columnNames ={"inspection_id","chks_question_result_id"})
    }
)
public class UserChecksheetAnswer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_answers_user_checksheet_id_user_checksheets_id"))
    private com.checkSheet.entity.Inspection inspection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chks_question_result_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_answers_chks_question_result_id_chks_question_results_id"))
    private ChksQuestionResult chksQuestionResult;

    @Column(name = "answer", columnDefinition = "varchar ")
    private String answer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chks_question_rslt_option_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_answers_chks_question_rslt_option_id_chks_question_result_options_id"))
    private ChksQuestionResultOption chksQuestionRsltOption;

    @Column(name = "judgement")
    private Short judgement = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chks_question_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_answers_chks_question_id_chks_questions_id"))
    private ChksQuestion chksQuestion;

    @Column(name = "is_not_applicable", columnDefinition = "bool default false")
    private Boolean isNotApplicable = false;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_answers_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_answers_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_answers_updated_by_user_id"))
    private User updatedBy;

}