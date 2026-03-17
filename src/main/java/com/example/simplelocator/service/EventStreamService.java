package com.example.simplelocator.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fan-out SSE broadcaster.
 *
 * Any server thread calls publish(type, json) and all connected browser tabs
 * receive the event instantly — no polling needed.
 *
 * CopyOnWriteArrayList is safe for the "many reads, occasional write" pattern
 * (client connects / disconnects are infrequent compared to broadcasts).
 */
@Slf4j
@Service
public class EventStreamService {

    public enum EventType {
        LOCATION_UPDATE,
        GEOFENCE_ENTER,
        GEOFENCE_EXIT,
        RIDER_ASSIGNED,
        RIDER_UNASSIGNED
    }

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Called by the browser to open an SSE connection.
     * Timeout 5 minutes; on completion/error the emitter removes itself.
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(300_000L);
        subscribers.add(emitter);
        log.debug("SSE client connected — total subscribers: {}", subscribers.size());

        Runnable cleanup = () -> {
            subscribers.remove(emitter);
            log.debug("SSE client disconnected — remaining: {}", subscribers.size());
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        return emitter;
    }

    /**
     * Broadcasts an event to all connected clients.
     * Dead emitters are removed on send failure.
     */
    public void publish(EventType type, String jsonPayload) {
        List<SseEmitter> dead = new java.util.ArrayList<>();

        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event()
                        .name(type.name())
                        .data(jsonPayload));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }

        subscribers.removeAll(dead);
    }
}
