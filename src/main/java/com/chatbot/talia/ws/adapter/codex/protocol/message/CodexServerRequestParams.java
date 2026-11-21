package com.chatbot.talia.ws.adapter.codex.protocol.message;

/** Fields used to identify Codex approval and MCP input requests. */
public record CodexServerRequestParams(String threadId, String turnId, String itemId, String reason,
                                      String command, String cwd, String serverName) {
}
