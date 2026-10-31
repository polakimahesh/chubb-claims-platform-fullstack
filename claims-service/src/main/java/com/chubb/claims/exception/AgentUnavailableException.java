package com.chubb.claims.exception;

/** The AI assistant cannot run, for example because no API key is configured or the model call failed. */
public class AgentUnavailableException extends RuntimeException {
    public AgentUnavailableException(String message) {
        super(message);
    }

    public AgentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
