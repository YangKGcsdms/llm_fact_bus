package com.factbus.bus;

import com.factbus.api.DuplicateEventException;
import com.factbus.arbitration.ArbitrationService;
import com.factbus.contract.EventCategory;
import com.factbus.contract.EventContractValidator;
import com.factbus.contract.EventEnvelope;
import com.factbus.fdr.FactDerivationReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public class EventBusService {

    private static final Logger log = LoggerFactory.getLogger(EventBusService.class);

    private final EventContractValidator validator;
    private final EventStore eventStore;
    private final FactDerivationReactor fdr;
    private final ArbitrationService arbitrationService;
    private final ConcurrentHashMap<String, Consumer<EventEnvelope>> subscribers = new ConcurrentHashMap<>();

    public EventBusService(EventContractValidator validator,
                           EventStore eventStore,
                           FactDerivationReactor fdr,
                           ArbitrationService arbitrationService) {
        this.validator = validator;
        this.eventStore = eventStore;
        this.fdr = fdr;
        this.arbitrationService = arbitrationService;
    }

    public EventEnvelope publish(EventEnvelope event) {
        // Idempotency: reject duplicate event_id (Step 9)
        if (event.getEventId() != null && eventStore.existsByEventId(event.getEventId())) {
            throw new DuplicateEventException(event.getEventId());
        }

        validator.validate(event, eventStore);
        EventEnvelope appended = eventStore.append(event);
        notifySubscribers(appended);

        // Arbitration: auto-arbitrate PROPOSAL_EVENTs (DESIGN.md §6)
        if (appended.getEventCategory() == EventCategory.PROPOSAL_EVENT) {
            List<EventEnvelope> subjectFacts = eventStore.query(
                Optional.empty(),
                Optional.of(EventCategory.FACT_EVENT),
                appended.getSubject() != null ? Optional.of(appended.getSubject().getType()) : Optional.empty(),
                appended.getSubject() != null ? Optional.of(appended.getSubject().getId()) : Optional.empty(),
                1000
            );
            EventEnvelope decision = arbitrationService.arbitrate(appended, subjectFacts);
            log.info("Arbitration produced DECISION_EVENT outcome={} for proposal={}",
                decision.getPayload().get("outcome"), appended.getEventId());
            validator.validate(decision, eventStore);
            EventEnvelope appendedDecision = eventStore.append(decision);
            notifySubscribers(appendedDecision);
        }

        // FDR: automatically derive FACT_EVENT from EXECUTION_EVENT (DESIGN.md §4.7.3)
        if (appended.getEventCategory() == EventCategory.EXECUTION_EVENT) {
            EventEnvelope derivedFact = fdr.tryDerive(appended);
            if (derivedFact != null) {
                log.info("FDR triggering derived FACT_EVENT for execution_event={}",
                    appended.getEventId());
                validator.validate(derivedFact, eventStore);
                EventEnvelope appendedFact = eventStore.append(derivedFact);
                notifySubscribers(appendedFact);
            }
        }

        return appended;
    }

    public List<EventEnvelope> query(Optional<String> traceId,
                                    Optional<EventCategory> eventCategory,
                                    Optional<String> subjectType,
                                    Optional<String> subjectId,
                                    int limit) {
        return eventStore.query(traceId, eventCategory, subjectType, subjectId, limit);
    }

    public long latestSequence() {
        return eventStore.getLatestSequence();
    }

    public String subscribe(Consumer<EventEnvelope> consumer) {
        String id = UUID.randomUUID().toString();
        subscribers.put(id, consumer);
        return id;
    }

    public void unsubscribe(String id) {
        subscribers.remove(id);
    }

    private void notifySubscribers(EventEnvelope event) {
        subscribers.values().forEach(consumer -> {
            try {
                consumer.accept(event);
            } catch (Exception ex) {
                log.warn("Subscriber notification failed for event={}: {}",
                    event.getEventId(), ex.getMessage());
            }
        });
    }
}
