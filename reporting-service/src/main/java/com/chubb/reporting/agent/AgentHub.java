package com.chubb.reporting.agent;

import com.chubb.claims.events.ClaimEvent;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Runs every agent on each claim event and pushes the insights to connected browsers over SSE. Insights are held
 * in memory only (a short ring buffer for late joiners): this is live demo telemetry, not a system of record.
 */
@Slf4j
@Service
public class AgentHub {

    static final int KEEP = 50;

    private final List<ClaimAgent> agents;
    private final Deque<AgentInsight> recent = new ArrayDeque<>();
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    public AgentHub(List<ClaimAgent> agents) {
        this.agents = agents;
    }

    /** Never throws: an agent problem must not disturb the projection or trigger Kafka retries. */
    public void onEvent(ClaimEvent event) {
        for (ClaimAgent agent : agents) {
            try {
                agent.observe(event).ifPresent(this::publish);
            } catch (RuntimeException ex) {
                log.warn("{} failed on event {}", agent.name(), event.eventId(), ex);
            }
        }
    }

    public synchronized List<AgentInsight> recent() {
        List<AgentInsight> copy = new ArrayList<>(recent);
        return copy; // newest first
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout; the client reconnects if it drops
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("ready").data("connected"));
        } catch (IOException e) {
            subscribers.remove(emitter);
        }
        return emitter;
    }

    private synchronized void remember(AgentInsight insight) {
        recent.addFirst(insight);
        while (recent.size() > KEEP) recent.removeLast();
    }

    private void publish(AgentInsight insight) {
        remember(insight);
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name("insight").data(insight));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(emitter);
            }
        }
    }
}
