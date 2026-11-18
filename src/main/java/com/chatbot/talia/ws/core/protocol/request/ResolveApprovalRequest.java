package com.chatbot.talia.ws.core.protocol.request;
import com.chatbot.talia.ws.adapter.codex.client.ApprovalCoordinator;

public record ResolveApprovalRequest(String requestId, ApprovalCoordinator.Decision decision)
        implements ChatActionPostRequest {}
