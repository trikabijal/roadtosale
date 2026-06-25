package com.auditpro.roadtosale.repo;

import com.auditpro.roadtosale.domain.SessionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SessionEventRepository extends JpaRepository<SessionEvent, UUID> {

    List<SessionEvent> findBySessionId(UUID sessionId);

    /** Batch-load events for many sessions in ONE query (avoids list N+1, W1). */
    List<SessionEvent> findBySessionIdIn(Collection<UUID> sessionIds);

    /**
     * Idempotent append (PRD §7 #16): inserts one event, ignoring it if
     * (session_id, cue_id) already exists. Returns the rows actually inserted
     * (0 if it was a duplicate, 1 if new) so the service can count "accepted".
     */
    @Modifying
    @Query(value = """
            INSERT INTO session_events
                (id, session_id, question_id, step_no, detected_at, confidence,
                 transcript_span, source, cue_id)
            VALUES
                (:id, :sessionId, :questionId, :stepNo, :detectedAt, :confidence,
                 :transcriptSpan, :source, :cueId)
            ON CONFLICT (session_id, cue_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreDuplicate(@Param("id") UUID id,
                              @Param("sessionId") UUID sessionId,
                              @Param("questionId") String questionId,
                              @Param("stepNo") int stepNo,
                              @Param("detectedAt") OffsetDateTime detectedAt,
                              @Param("confidence") BigDecimal confidence,
                              @Param("transcriptSpan") String transcriptSpan,
                              @Param("source") String source,
                              @Param("cueId") String cueId);
}
