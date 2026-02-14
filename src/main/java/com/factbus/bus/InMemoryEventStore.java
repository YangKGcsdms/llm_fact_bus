package com.factbus.bus;

import com.factbus.contract.EventCategory;
import com.factbus.contract.EventEnvelope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Component
public class InMemoryEventStore implements EventStore {

    private final CopyOnWriteArrayList<EventEnvelope> events = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public EventEnvelope append(EventEnvelope event) {
        event.setSequenceNumber(sequence.incrementAndGet());
        events.add(event);
        return event;
    }

    @Override
    public List<EventEnvelope> query(Optional<String> traceId,
                                    Optional<EventCategory> eventCategory,
                                    Optional<String> subjectType,
                                    Optional<String> subjectId,
                                    int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }

        return events.stream()
            .filter(e -> traceId.map(t -> t.equals(e.getTraceId())).orElse(true))
            .filter(e -> eventCategory.map(c -> c == e.getEventCategory()).orElse(true))
            .filter(e -> subjectType.map(s -> e.getSubject() != null && s.equals(e.getSubject().getType())).orElse(true))
            .filter(e -> subjectId.map(s -> e.getSubject() != null && s.equals(e.getSubject().getId())).orElse(true))
            .limit(limit)
            .collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public boolean existsByEventId(String eventId) {
        return events.stream().anyMatch(e -> eventId.equals(e.getEventId()));
    }

    @Override
    public long getLatestSequence() {
        return sequence.get();
    }

    @Override
    public List<EventEnvelope> queryBySequenceRange(long fromInclusive, long toInclusive, int limit) {
        if (limit <= 0 || toInclusive < fromInclusive) {
            return Collections.emptyList();
        }
        return events.stream()
            .filter(e -> e.getSequenceNumber() != null)
            .filter(e -> e.getSequenceNumber() >= fromInclusive && e.getSequenceNumber() <= toInclusive)
            .limit(limit)
            .collect(Collectors.toCollection(ArrayList::new));
    }
}
