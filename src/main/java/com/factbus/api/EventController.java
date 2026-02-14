package com.factbus.api;

import com.factbus.bus.EventBusService;
import com.factbus.contract.EventCategory;
import com.factbus.contract.EventEnvelope;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/v1/events")
public class EventController {

    private final EventBusService eventBusService;

    public EventController(EventBusService eventBusService) {
        this.eventBusService = eventBusService;
    }

    @PostMapping
    public Map<String, Object> publish(@RequestBody EventEnvelope event) {
        EventEnvelope published = eventBusService.publish(event);
        return Map.of(
            "status", "accepted",
            "event_id", published.getEventId(),
            "sequence_number", published.getSequenceNumber()
        );
    }

    @GetMapping
    public List<EventEnvelope> query(@RequestParam(required = false) String traceId,
                                    @RequestParam(required = false) EventCategory eventCategory,
                                    @RequestParam(required = false) String subjectType,
                                    @RequestParam(required = false) String subjectId,
                                    @RequestParam(defaultValue = "100") int limit) {
        return eventBusService.query(
            Optional.ofNullable(traceId),
            Optional.ofNullable(eventCategory),
            Optional.ofNullable(subjectType),
            Optional.ofNullable(subjectId),
            Math.min(limit, 1000)
        );
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String traceId,
                            @RequestParam(required = false) EventCategory eventCategory) {
        SseEmitter emitter = new SseEmitter(0L);
        String subscriptionId = eventBusService.subscribe(event -> {
            if (!matchesTrace(traceId, event) || !matchesCategory(eventCategory, event)) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                    .name("event")
                    .data(event));
            } catch (IOException ex) {
                emitter.completeWithError(ex);
            }
        });

        emitter.onCompletion(() -> eventBusService.unsubscribe(subscriptionId));
        emitter.onTimeout(() -> eventBusService.unsubscribe(subscriptionId));
        emitter.onError(ex -> eventBusService.unsubscribe(subscriptionId));
        return emitter;
    }

    private boolean matchesTrace(String traceId, EventEnvelope event) {
        return traceId == null || traceId.equals(event.getTraceId());
    }

    private boolean matchesCategory(EventCategory eventCategory, EventEnvelope event) {
        return eventCategory == null || eventCategory == event.getEventCategory();
    }
}
