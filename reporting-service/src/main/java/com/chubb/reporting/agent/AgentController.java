package com.chubb.reporting.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live demo-agent activity. Under /api/reports, so it is MANAGER-only like every report. */
@RestController
@RequestMapping("/api/reports/agents")
@RequiredArgsConstructor
@Tag(name = "Agents")
public class AgentController {

    private final AgentHub hub;

    @GetMapping("/recent")
    @Operation(summary = "The most recent agent insights, newest first (in memory, lost on restart)")
    public List<AgentInsight> recent() {
        return hub.recent();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Server-sent events: an 'insight' event is pushed the moment an agent reacts to a claim event")
    public SseEmitter stream() {
        return hub.subscribe();
    }
}
