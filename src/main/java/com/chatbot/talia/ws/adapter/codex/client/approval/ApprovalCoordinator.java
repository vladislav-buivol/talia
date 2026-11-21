package com.chatbot.talia.ws.adapter.codex.client.approval;

import com.chatbot.talia.ws.adapter.codex.client.rpc.CodexWebSocketClient;
import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.adapter.codex.protocol.message.CodexServerRequestParams;
import com.chatbot.talia.ws.adapter.codex.protocol.message.NotificationParams;
import com.chatbot.talia.ws.adapter.codex.protocol.response.ApprovalDecisionResult;
import com.chatbot.talia.ws.adapter.codex.protocol.response.decoder.NotificationMethod;
import com.chatbot.talia.ws.adapter.codex.protocol.response.decoder.ServerRequestMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Coordinates server-initiated approval requests for one Codex connection.
 * Unrecognized requests are rejected rather than approved automatically.
 */
public final class ApprovalCoordinator implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ApprovalCoordinator.class);
    private final CodexWebSocketClient client;
    private final Duration timeout;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    private volatile Consumer<ApprovalPrompt> listener;
    private volatile Consumer<String> resolvedListener;
    private volatile boolean closed;

    public ApprovalCoordinator(CodexWebSocketClient client, Duration timeout) {
        this.client = Objects.requireNonNull(client);
        this.timeout = Objects.requireNonNull(timeout);
    }

    public void setListener(Consumer<ApprovalPrompt> listener) {
        this.listener = listener;
    }

    public void setResolvedListener(Consumer<String> listener) {
        this.resolvedListener = listener;
    }

    public void onNotification(CodexMessageCodec.Notification notification) {
        if (notification.method() != NotificationMethod.SERVER_REQUEST_RESOLVED) {
            return;
        }

        NotificationParams params = notification.params();
        if (params == null || params.resolvedRequestId() == null) {
            return;
        }

        String requestId = key(params.resolvedRequestId());
        if (pending.remove(requestId) != null) {
            notifyResolved(requestId);
        }
    }

    public void receive(CodexMessageCodec.ServerRequest request) {
        if (closed) {
            return;
        }

        ServerRequestMethod method = request.method();
        CodexServerRequestParams params = request.params();
        if (method == ServerRequestMethod.MCP_SERVER_ELICITATION_REQUEST) {
            // Talia has no form or URL approval flow for MCP user input.
            client.sendServerResult(request.id(), Map.of("action", "cancel"));
            log.info("Canceled MCP input request: server={}, thread={}, turn={}",
                    params == null ? null : params.serverName(),
                    params == null ? null : params.threadId(),
                    params == null ? null : params.turnId());
            return;
        }
        if (method != ServerRequestMethod.ITEM_COMMAND_EXECUTION_REQUEST_APPROVAL
                && method != ServerRequestMethod.ITEM_FILE_CHANGE_REQUEST_APPROVAL) {
            client.sendServerError(request.id(), -32601, "Unsupported server request: " + request.wireMethod());
            return;
        }

        if (params == null || params.threadId() == null || params.threadId().isBlank()) {
            client.sendServerError(request.id(), -32602, "Missing approval threadId");
            return;
        }

        String requestId = key(request.id());
        Pending item = new Pending(request);
        if (pending.putIfAbsent(requestId, item) != null) {
            return;
        }

        CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
            if (pending.remove(requestId, item)) {
                decline(request);
                notifyResolved(requestId);
            }
        });

        Consumer<ApprovalPrompt> current = listener;
        if (current == null) {
            if (pending.remove(requestId, item)) {
                decline(request);
            }
            return;
        }

        try {
            current.accept(new ApprovalPrompt(
                    requestId,
                    params.threadId(),
                    params.turnId(),
                    request.wireMethod(),
                    params.reason(),
                    params.command(),
                    params.cwd()));
        } catch (RuntimeException error) {
            if (pending.remove(requestId, item)) {
                decline(request);
            }
        }
    }

    public boolean resolve(String requestId, Decision decision) {
        if (requestId == null || decision == null || closed) {
            return false;
        }

        Pending item = pending.remove(requestId);
        if (item == null) {
            return false;
        }

        sendDecision(item.request(), decision);
        notifyResolved(requestId);
        return true;
    }

    private void decline(CodexMessageCodec.ServerRequest request) {
        sendDecision(request, Decision.DECLINE);
    }

    private void sendDecision(CodexMessageCodec.ServerRequest request, Decision decision) {
        client.sendServerResult(request.id(), new ApprovalDecisionResult(decision.wire()));
    }

    private void notifyResolved(String requestId) {
        Consumer<String> current = resolvedListener;
        if (current != null) {
            current.accept(requestId);
        }
    }

    private static String key(CodexMessageCodec.RequestId id) {
        return switch (id) {
            case CodexMessageCodec.NumericRequestId numeric -> "n:" + numeric.value();
            case CodexMessageCodec.StringRequestId text -> "s:" + text.value();
        };
    }

    @Override
    public void close() {
        closed = true;
        pending.clear();
    }

    public enum Decision {
        ACCEPT("accept"),
        DECLINE("decline");

        private final String wire;

        Decision(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public record ApprovalPrompt(String requestId, String threadId, String turnId,
                                 String method, String reason, String command, String cwd) {
    }

    private record Pending(CodexMessageCodec.ServerRequest request) {
    }
}
