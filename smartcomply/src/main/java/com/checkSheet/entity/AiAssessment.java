package com.checkSheet.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@Getter
@Setter
@Entity
@Table(name = "ai_assessments", indexes = {
        @Index(name = "idx_ai_assessments_user_checksheet_id", columnList = "inspection_id"),
        @Index(name = "idx_ai_assessments_chks_question_result_id", columnList = "chks_question_result_id"),
        @Index(name = "idx_ai_assessments_lookup", columnList = "inspection_id, chks_question_result_id")
})
public class AiAssessment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", nullable = false, referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_ai_assessments_user_checksheet_id"))
    private com.checkSheet.entity.Inspection inspection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chks_question_result_id", nullable = false, referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_ai_assessments_chks_question_result_id"))
    private ChksQuestionResult chksQuestionResult;

    @Column(name = "photo_path", nullable = false, columnDefinition = "varchar(500)")
    private String photoPath;

    @Column(name = "suggested_judgement", nullable = false, columnDefinition = "varchar(10)")
    private String suggestedJudgement;

    @Column(name = "explanation", columnDefinition = "text")
    private String explanation;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "ai_model", columnDefinition = "varchar(100)")
    private String aiModel;

    @Column(name = "ai_provider", columnDefinition = "varchar(50)")
    private String aiProvider;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "prompt_sent", columnDefinition = "text")
    private String promptSent;

    @Column(name = "raw_response", columnDefinition = "text")
    private String rawResponse;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "assessed_at", nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Date assessedAt = new Date();

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
    @JoinColumn(name = "created_by", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_ai_assessments_created_by"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_ai_assessments_updated_by"))
    private User updatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_ai_assessments_deleted_by"))
    private User deletedBy;
}
