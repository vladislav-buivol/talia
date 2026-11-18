package com.chatbot.talia.ws.adapter.codex.protocol.message;

/** Typed parameters of supported Codex approval requests. */
public record CodexServerRequestParams(String threadId, String turnId, String itemId, String reason, String command, String cwd) {
}
