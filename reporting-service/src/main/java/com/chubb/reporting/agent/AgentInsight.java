package com.chubb.reporting.agent;

import java.time.Instant;
import java.util.UUID;

/** One observation made by a demo agent about a claim event. */
public record AgentInsight(
        UUID id,
        String agent,
        Severity severity,
        UUID claimId,
        String claimNumber,
        String message,
        Instant at) {

    public enum Severity { INFO, WARNING, ALERT }
}
