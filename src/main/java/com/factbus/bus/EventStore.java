package com.factbus.bus;

import com.factbus.contract.EventCategory;
import com.factbus.contract.EventEnvelope;

import java.util.List;
import java.util.Optional;

public interface EventStore {
    EventEnvelope append(EventEnvelope event);

    List<EventEnvelope> query(Optional<String> traceId,
                             Optional<EventCategory> eventCategory,
                             Optional<String> subjectType,
                             Optional<String> subjectId,
                             int limit);

    boolean existsByEventId(String eventId);

    long getLatestSequence();

    List<EventEnvelope> queryBySequenceRange(long fromInclusive, long toInclusive, int limit);
}
