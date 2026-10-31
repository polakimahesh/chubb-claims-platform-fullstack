package com.chubb.claims.controller;

import com.chubb.claims.ai.ClaimsAgent;
import com.chubb.platform.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Chat with the AI assistant, which carries out claim operations for the signed-in user. */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Tag(name = "AI assistant")
public class AgentController {

    public record Message(@NotBlank @Pattern(regexp = "user|assistant") String role,
                          @NotBlank @Size(max = 4000) String content) {
    }

    public record ChatRequest(@NotEmpty @Size(max = 20) List<@Valid Message> messages) {
    }

    private final ClaimsAgent agent;

    @PostMapping("/chat")
    @Operation(summary = "Ask the AI assistant to do something with claims (roles CLAIMANT, OFFICER). It acts as the "
            + "authenticated user, so it can only do what that user could do. Needs a Google AI Studio key (app.ai.gemini.api-key).")
    public ClaimsAgent.Reply chat(Authentication auth, @Valid @RequestBody ChatRequest request) {
        List<ClaimsAgent.Turn> turns = request.messages().stream()
                .map(m -> new ClaimsAgent.Turn(m.role(), m.content())).toList();
        return agent.chat(Actor.from(auth), turns);
    }
}
