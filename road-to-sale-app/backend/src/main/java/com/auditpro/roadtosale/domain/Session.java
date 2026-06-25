package com.auditpro.roadtosale.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** The core entity: a salesperson's conversation with a customer. */
@Entity
@Table(name = "sessions")
public class Session {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "dealership_id", nullable = false)
    private UUID dealershipId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private SessionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SessionStatus status = SessionStatus.ACTIVE;

    @Column(name = "checksheet_code", nullable = false)
    private String checksheetCode;

    /** Optional free-form context, e.g. {@code {customerName, vehicleOfInterest}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context", columnDefinition = "jsonb")
    private Map<String, Object> context;

    @Column(name = "transcript")
    private String transcript;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @PrePersist
    void ensureDefaults() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (startedAt == null) {
            startedAt = OffsetDateTime.now();
        }
        if (status == null) {
            status = SessionStatus.ACTIVE;
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDealershipId() {
        return dealershipId;
    }

    public void setDealershipId(UUID dealershipId) {
        this.dealershipId = dealershipId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public SessionType getType() {
        return type;
    }

    public void setType(SessionType type) {
        this.type = type;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }

    public String getChecksheetCode() {
        return checksheetCode;
    }

    public void setChecksheetCode(String checksheetCode) {
        this.checksheetCode = checksheetCode;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    public String getTranscript() {
        return transcript;
    }

    public void setTranscript(String transcript) {
        this.transcript = transcript;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(OffsetDateTime endedAt) {
        this.endedAt = endedAt;
    }
}
