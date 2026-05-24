package com.checkSheet.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

/**
 * The failing-question subset that this {@link Inspection}
 * tracks. By construction = (intervention questions) ∩ (questions this
 * dealership originally got NOT OK).
 */
@NoArgsConstructor
@Data
@AllArgsConstructor
@Entity
@Table(name = "intervention_assignment_questions",
       uniqueConstraints = @UniqueConstraint(name = "uk_iaq_inspection_question",
                                             columnNames = {"inspection_id", "chks_question_id"}))
public class InterventionAssignmentQuestion implements java.io.Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inspection_id", referencedColumnName = "id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_iaq_inspection_id"))
    private Inspection inspection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chks_question_id", referencedColumnName = "id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_iaq_chks_question_id"))
    private ChksQuestion chksQuestion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chks_question_result_id", referencedColumnName = "id",
                foreignKey = @ForeignKey(name = "fk_iaq_chks_question_result_id"))
    private ChksQuestionResult chksQuestionResult;

    @Temporal(TemporalType.TIMESTAMP)
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, length = 29, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Date createdAt = new Date();

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "deleted_at", length = 29)
    private Date deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id")
    private User deletedBy;
}
