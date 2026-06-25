package com.auditpro.roadtosale.service;

import com.auditpro.roadtosale.config.RoadToSaleProperties;
import com.auditpro.roadtosale.domain.Session;
import com.auditpro.roadtosale.domain.SessionEvent;
import com.auditpro.roadtosale.domain.SessionStatus;
import com.auditpro.roadtosale.dto.CreateSessionRequest;
import com.auditpro.roadtosale.dto.PostEventsResponse;
import com.auditpro.roadtosale.dto.SessionContextDTO;
import com.auditpro.roadtosale.dto.SessionDTO;
import com.auditpro.roadtosale.dto.SessionEventDTO;
import com.auditpro.roadtosale.dto.SessionOutcomeDTO;
import com.auditpro.roadtosale.dto.SessionProgressDTO;
import com.auditpro.roadtosale.dto.SessionSummaryDTO;
import com.auditpro.roadtosale.repo.SessionEventRepository;
import com.auditpro.roadtosale.repo.SessionRepository;
import com.auditpro.roadtosale.security.AuthenticatedUser;
import com.auditpro.roadtosale.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Session lifecycle + derived outcomes. Every read/write is scoped to the
 * caller's dealership (from the token); cross-tenant access yields 404.
 */
@Service
public class SessionService {

    private final SessionRepository sessionRepository;
    private final SessionEventRepository eventRepository;
    private final ChecksheetService checksheetService;
    private final double confidenceThreshold;

    public SessionService(SessionRepository sessionRepository,
                          SessionEventRepository eventRepository,
                          ChecksheetService checksheetService,
                          RoadToSaleProperties props) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.checksheetService = checksheetService;
        this.confidenceThreshold = props.getOutcome().getConfidenceThreshold();
    }

    @Transactional
    public SessionDTO create(AuthenticatedUser caller, CreateSessionRequest req) {
        // Validate the checksheet exists up front (404 if unknown).
        checksheetService.getByCode(req.checksheetCode());

        Session s = new Session();
        s.setDealershipId(caller.dealershipId());
        s.setUserId(caller.userId());
        s.setType(req.type());
        s.setStatus(SessionStatus.ACTIVE);
        s.setChecksheetCode(req.checksheetCode());
        s.setContext(req.context());
        s.setStartedAt(OffsetDateTime.now());
        Session saved = sessionRepository.save(s);
        return toSessionDTO(saved, List.of());
    }

    @Transactional(readOnly = true)
    public List<SessionSummaryDTO> list(AuthenticatedUser caller) {
        List<Session> sessions = sessionRepository
                .findByDealershipIdAndUserIdOrderByStartedAtDesc(caller.dealershipId(), caller.userId());
        List<SessionSummaryDTO> out = new ArrayList<>(sessions.size());
        for (Session s : sessions) {
            List<SessionEvent> events = eventRepository.findBySessionId(s.getId());
            out.add(toSummaryDTO(s, events));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public SessionDTO get(AuthenticatedUser caller, UUID sessionId) {
        Session s = requireOwnedSession(caller, sessionId);
        List<SessionEvent> events = eventRepository.findBySessionId(s.getId());
        return toSessionDTO(s, events);
    }

    @Transactional
    public SessionDTO submit(AuthenticatedUser caller, UUID sessionId, String transcript) {
        Session s = requireOwnedSession(caller, sessionId);
        if (s.getStatus() == SessionStatus.COMPLETED) {
            throw new ApiException.Conflict("Session is already completed");
        }
        s.setStatus(SessionStatus.COMPLETED);
        s.setEndedAt(OffsetDateTime.now());
        if (transcript != null) {
            s.setTranscript(transcript);
        }
        Session saved = sessionRepository.save(s);
        List<SessionEvent> events = eventRepository.findBySessionId(saved.getId());
        return toSessionDTO(saved, events);
    }

    @Transactional
    public PostEventsResponse appendEvents(AuthenticatedUser caller, UUID sessionId, List<SessionEventDTO> events) {
        Session s = requireOwnedSession(caller, sessionId);
        if (s.getStatus() == SessionStatus.COMPLETED) {
            throw new ApiException.Conflict("Cannot append events to a completed session");
        }
        int accepted = 0;
        for (SessionEventDTO e : events) {
            int inserted = eventRepository.insertIgnoreDuplicate(
                    UUID.randomUUID(),
                    sessionId,
                    e.questionId(),
                    e.stepNo(),
                    e.detectedAt(),
                    BigDecimal.valueOf(e.confidence()),
                    e.transcriptSpan(),
                    e.source().getWire(),
                    e.cueId());
            accepted += inserted;
        }
        return new PostEventsResponse(accepted);
    }

    /** Tenant-scoped fetch: any miss (incl. another dealership's id) -> 404. */
    private Session requireOwnedSession(AuthenticatedUser caller, UUID sessionId) {
        return sessionRepository.findByIdAndDealershipId(sessionId, caller.dealershipId())
                .orElseThrow(() -> new ApiException.NotFound("Session not found"));
    }

    // ── DTO mapping & outcome derivation ─────────────────────────────────────

    private SessionSummaryDTO toSummaryDTO(Session s, List<SessionEvent> events) {
        List<SessionOutcomeDTO> outcomes = deriveOutcomes(events);
        return new SessionSummaryDTO(
                s.getId(), s.getType(), s.getStatus(), s.getChecksheetCode(),
                s.getStartedAt(), s.getEndedAt(), toContextDTO(s.getContext()),
                progress(s.getChecksheetCode(), outcomes));
    }

    private SessionDTO toSessionDTO(Session s, List<SessionEvent> events) {
        List<SessionOutcomeDTO> outcomes = deriveOutcomes(events);
        return new SessionDTO(
                s.getId(), s.getType(), s.getStatus(), s.getChecksheetCode(),
                s.getStartedAt(), s.getEndedAt(), toContextDTO(s.getContext()),
                progress(s.getChecksheetCode(), outcomes),
                s.getTranscript(), outcomes);
    }

    private SessionProgressDTO progress(String checksheetCode, List<SessionOutcomeDTO> outcomes) {
        int total = checksheetService.totalQuestions(checksheetCode);
        int answered = (int) outcomes.stream().filter(SessionOutcomeDTO::satisfied).count();
        return new SessionProgressDTO(answered, total);
    }

    /**
     * Derives one outcome per question that has events: the highest-confidence
     * event wins; satisfied = confidence >= threshold (PRD §7 #18). Never stored.
     */
    private List<SessionOutcomeDTO> deriveOutcomes(List<SessionEvent> events) {
        Map<String, SessionEvent> bestByQuestion = new LinkedHashMap<>();
        for (SessionEvent e : events) {
            SessionEvent current = bestByQuestion.get(e.getQuestionId());
            if (current == null || e.getConfidence().compareTo(current.getConfidence()) > 0) {
                bestByQuestion.put(e.getQuestionId(), e);
            }
        }
        List<SessionOutcomeDTO> outcomes = new ArrayList<>(bestByQuestion.size());
        for (SessionEvent best : bestByQuestion.values()) {
            double confidence = best.getConfidence().doubleValue();
            boolean satisfied = confidence >= confidenceThreshold;
            outcomes.add(new SessionOutcomeDTO(
                    best.getQuestionId(), best.getStepNo(), satisfied,
                    confidence, best.getTranscriptSpan()));
        }
        outcomes.sort(Comparator.comparing(SessionOutcomeDTO::stepNo)
                .thenComparing(SessionOutcomeDTO::questionId));
        return outcomes;
    }

    private SessionContextDTO toContextDTO(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object name = context.get("customerName");
        Object vehicle = context.get("vehicleOfInterest");
        if (name == null && vehicle == null) {
            return null;
        }
        return new SessionContextDTO(
                name == null ? null : name.toString(),
                vehicle == null ? null : vehicle.toString());
    }
}
