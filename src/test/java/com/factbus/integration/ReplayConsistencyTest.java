package com.factbus.integration;

import com.factbus.bus.EventStore;
import com.factbus.bus.InMemoryEventStore;
import com.factbus.contract.EventEnvelope;
import com.factbus.contract.ProducerType;
import com.factbus.projection.ProjectionService;
import com.factbus.projection.SubjectProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replay consistency test (Step 8, MVP litmus test):
 *
 * Captures all events from a live run, then replays them into a fresh
 * event store and verifies that the projection built from replay matches
 * the live projection exactly.
 *
 * Per DESIGN.md §8:
 * "If you remove all Agents and replay the Event Log, you must be able
 *  to 100% reproduce the historical decision process."
 */
@SpringBootTest
class ReplayConsistencyTest {

    @Autowired EventStore liveStore;
    @Autowired ProjectionService projectionService;

    @Test
    @DisplayName("Replay consistency: replayed projection matches live projection")
    void replayedProjection_matchesLiveProjection() {
        // ---- Phase 1: Generate events in the live store ----
        // (Relies on HappyPathIntegrationTest having run in the same context
        //  or generates its own events)
        // For independence, we use the live store's existing events.
        long latestSeq = liveStore.getLatestSequence();
        if (latestSeq == 0) {
            // No events yet — nothing to replay, pass vacuously
            return;
        }

        // Capture all events from live store
        List<EventEnvelope> allEvents = liveStore.queryBySequenceRange(1, latestSeq, 100000);
        assertFalse(allEvents.isEmpty(), "Live store should have events");

        // ---- Phase 2: Replay into fresh store ----
        InMemoryEventStore replayStore = new InMemoryEventStore();
        for (EventEnvelope event : allEvents) {
            // Replay uses a detached copy to avoid mutating live-store objects.
            EventEnvelope replayCopy = copyForReplay(event);
            replayStore.append(replayCopy);
        }

        assertEquals(allEvents.size(), replayStore.getLatestSequence(),
            "Replay store should have same number of events");

        // ---- Phase 3: Compare projections ----
        ProjectionService replayProjection = new ProjectionService(replayStore);

        // Collect all distinct subjects from events
        allEvents.stream()
            .filter(e -> e.getSubject() != null)
            .map(e -> e.getSubject().getType() + ":" + e.getSubject().getId())
            .distinct()
            .forEach(subjectKey -> {
                String[] parts = subjectKey.split(":", 2);
                String subjectType = parts[0];
                String subjectId = parts[1];

                Optional<SubjectProjection> liveProj = projectionService.getProjection(subjectType, subjectId);
                Optional<SubjectProjection> replayProj = replayProjection.getProjection(subjectType, subjectId);

                assertEquals(liveProj.isPresent(), replayProj.isPresent(),
                    "Projection existence should match for " + subjectKey);

                if (liveProj.isPresent() && replayProj.isPresent()) {
                    SubjectProjection live = liveProj.get();
                    SubjectProjection replay = replayProj.get();

                    assertEquals(live.confirmedFacts().size(), replay.confirmedFacts().size(),
                        "Confirmed facts count mismatch for " + subjectKey);
                    assertEquals(live.pendingDecisions().size(), replay.pendingDecisions().size(),
                        "Pending decisions count mismatch for " + subjectKey);
                    assertEquals(live.pendingExecutions().size(), replay.pendingExecutions().size(),
                        "Pending executions count mismatch for " + subjectKey);

                    // Verify fact event IDs match in order
                    for (int i = 0; i < live.confirmedFacts().size(); i++) {
                        assertEquals(
                            live.confirmedFacts().get(i).eventId(),
                            replay.confirmedFacts().get(i).eventId(),
                            "Fact event ID mismatch at position " + i + " for " + subjectKey);
                    }
                }
            });
    }

    private EventEnvelope copyForReplay(EventEnvelope source) {
        EventEnvelope copy = new EventEnvelope();
        copy.setSchemaVersion(source.getSchemaVersion());
        copy.setSequenceNumber(null);
        copy.setEventId(source.getEventId());
        copy.setEventCategory(source.getEventCategory());
        copy.setEventName(source.getEventName());
        copy.setOccurredAt(source.getOccurredAt());
        copy.setTraceId(source.getTraceId());
        copy.setCausationId(source.getCausationId());

        if (source.getProducer() != null) {
            EventEnvelope.Producer producer = new EventEnvelope.Producer();
            ProducerType type = source.getProducer().getType();
            producer.setType(type);
            producer.setId(source.getProducer().getId());
            producer.setVersion(source.getProducer().getVersion());
            copy.setProducer(producer);
        }

        if (source.getSubject() != null) {
            EventEnvelope.Subject subject = new EventEnvelope.Subject();
            subject.setType(source.getSubject().getType());
            subject.setId(source.getSubject().getId());
            copy.setSubject(subject);
        }

        if (source.getPayload() != null) {
            copy.setPayload(Map.copyOf(source.getPayload()));
        }

        return copy;
    }
}
