package com.chatbot.talia.ws.adapter.codex.client;

/** The terminal outcome reported by the Codex server. */
public record CodexTurnResult(String turnId, String status, String errorMessage) {
}
