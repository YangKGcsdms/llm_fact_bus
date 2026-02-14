package com.factbus.api;

import com.factbus.bus.EventBusService;
import com.factbus.contract.EventCategory;
import com.factbus.contract.EventEnvelope;
import com.factbus.contract.ProducerType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gateway endpoint for cold-start intents (DESIGN.md §9.1).
 *
 * POST /v1/gateway/intents
 *
 * Converts external requests into initial FACT_EVENTs so that
 * "the first impulse is also an event" — avoids deadlock where
 * all components wait for events with no first mover.
 *
 * The gateway always produces:
 * - producer.type = "system"
 * - observed_from = "api" (for external API calls) or "human_input" (for human-originated)
 */
@RestController
@RequestMapping("/v1/gateway")
public class GatewayController {

    private final EventBusService eventBusService;

    public GatewayController(EventBusService eventBusService) {
        this.eventBusService = eventBusService;
    }

    /**
     * Accepts an external intent and transforms it into an initial FACT_EVENT.
     *
     * Expected request body:
     * {
     *   "intent_name": "RefundRequested",
     *   "subject": { "type": "order", "id": "ORD-1001" },
     *   "facts": { ... },
     *   "source": "api" | "human_input",   // optional, defaults to "api"
     *   "trace_id": "..."                   // optional, auto-generated if missing
     * }
     */
    @PostMapping("/intents")
    public Map<String, Object> submitIntent(@RequestBody Map<String, Object> request) {
        String intentName = requireString(request.get("intent_name"), "intent_name is required");

        @SuppressWarnings("unchecked")
        Map<String, Object> subjectMap = (Map<String, Object>) request.get("subject");
        if (subjectMap == null || subjectMap.get("type") == null || subjectMap.get("id") == null) {
            throw new com.factbus.contract.ContractViolationException(
                "subject with type and id is required");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> facts = request.get("facts") instanceof Map
            ? (Map<String, Object>) request.get("facts")
            : Map.of();

        String source = request.get("source") instanceof String s ? s : "api";
        if (!("api".equals(source) || "human_input".equals(source))) {
            throw new com.factbus.contract.ContractViolationException(
                "source must be one of: api, human_input");
        }

        String traceId = request.get("trace_id") instanceof String t && !t.isBlank()
            ? t
            : "trace-" + UUID.randomUUID().toString().substring(0, 8);

        // Build FACT_EVENT envelope
        EventEnvelope factEvent = new EventEnvelope();
        factEvent.setSchemaVersion("1.0.0");
        factEvent.setEventId(UUID.randomUUID().toString());
        factEvent.setEventCategory(EventCategory.FACT_EVENT);
        factEvent.setEventName(intentName);
        factEvent.setOccurredAt(Instant.now());
        factEvent.setTraceId(traceId);

        EventEnvelope.Producer producer = new EventEnvelope.Producer();
        producer.setType(ProducerType.SYSTEM);
        producer.setId("gateway");
        producer.setVersion("v1");
        factEvent.setProducer(producer);

        EventEnvelope.Subject subject = new EventEnvelope.Subject();
        subject.setType(String.valueOf(subjectMap.get("type")));
        subject.setId(String.valueOf(subjectMap.get("id")));
        factEvent.setSubject(subject);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("facts", facts);
        payload.put("observed_from", source);
        factEvent.setPayload(payload);

        EventEnvelope published = eventBusService.publish(factEvent);

        return Map.of(
            "status", "accepted",
            "event_id", published.getEventId(),
            "trace_id", published.getTraceId(),
            "sequence_number", published.getSequenceNumber()
        );
    }

    private String requireString(Object value, String message) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new com.factbus.contract.ContractViolationException(message);
        }
        return text;
    }
}
