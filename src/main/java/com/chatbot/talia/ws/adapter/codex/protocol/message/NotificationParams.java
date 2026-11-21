package com.chatbot.talia.ws.adapter.codex.protocol.message;

import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec.RequestId;

/** Typed fields from notifications consumed by the local Codex adapter. */
public record NotificationParams(
        String threadId,
        String turnId,
        String itemId,
        String delta,
        Item item,
        Turn turn,
        RequestId resolvedRequestId) {

    public record Item(String type, String id, String phase, String text) {
        public boolean isCommentary() {
            return "agentMessage".equals(type) && "commentary".equals(phase);
        }
    }

    public record Turn(String id, String status, Failure error) {
    }

    public record Failure(String message) {
    }
}
