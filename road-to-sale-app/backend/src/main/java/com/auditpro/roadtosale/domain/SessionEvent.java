package com.auditpro.roadtosale.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Append-only log of a detected cue. Idempotent per session by {@code cue_id}
 * (DB unique constraint on (session_id, cue_id)).
 */
@Entity
@Table(name = "session_events")
public class SessionEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "question_id", nullable = false)
    private String questionId;

    @Column(name = "step_no", nullable = false)
    private int stepNo;

    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt;

    // numeric(4,3): confidence is bounded 0..1 with 3 decimal places (W2).
    @Column(name = "confidence", nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "transcript_span")
    private String transcriptSpan;

    @Convert(converter = EventSourceConverter.class)
    @Column(name = "source", nullable = false)
    private EventSource source;

    @Column(name = "cue_id", nullable = false)
    private String cueId;

    @PrePersist
    void ensureId() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public int getStepNo() {
        return stepNo;
    }

    public void setStepNo(int stepNo) {
        this.stepNo = stepNo;
    }

    public OffsetDateTime getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(OffsetDateTime detectedAt) {
        this.detectedAt = detectedAt;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getTranscriptSpan() {
        return transcriptSpan;
    }

    public void setTranscriptSpan(String transcriptSpan) {
        this.transcriptSpan = transcriptSpan;
    }

    public EventSource getSource() {
        return source;
    }

    public void setSource(EventSource source) {
        this.source = source;
    }

    public String getCueId() {
        return cueId;
    }

    public void setCueId(String cueId) {
        this.cueId = cueId;
    }
}
