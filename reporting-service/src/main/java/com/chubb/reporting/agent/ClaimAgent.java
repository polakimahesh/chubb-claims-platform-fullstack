package com.chubb.reporting.agent;

import com.chubb.claims.events.ClaimEvent;
import java.util.Optional;

/** A small, deterministic agent: looks at one claim event and optionally reports an insight. */
public interface ClaimAgent {

    String name();

    Optional<AgentInsight> observe(ClaimEvent event);
}
