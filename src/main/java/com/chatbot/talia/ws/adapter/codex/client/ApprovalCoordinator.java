package com.chatbot.talia.ws.adapter.codex.client;

import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.adapter.codex.protocol.response.decoder.ServerRequestMethod;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Local per-connection approval coordination, fail-closed for unrecognized methods. */
public final class ApprovalCoordinator implements AutoCloseable {
    public enum Decision {
        ACCEPT("accept"), DECLINE("decline");
        private final String wire;
        Decision(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public record ApprovalPrompt(String requestId, String threadId, String turnId,
            String method, String reason, String command, String cwd) {}

    private record Pending(CodexMessageCodec.ServerRequest request) {}
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

    public void setListener(Consumer<ApprovalPrompt> listener) { this.listener = listener; }
    public void setResolvedListener(Consumer<String> listener) { this.resolvedListener = listener; }

    public void onNotification(CodexMessageCodec.Notification notification) {
        if (notification.method() != com.chatbot.talia.ws.adapter.codex.protocol.response.decoder.NotificationMethod.SERVER_REQUEST_RESOLVED) return;
        var params = notification.params();
        if (params == null) return;
        var rawId = params.path("requestId");
        String id = rawId.isIntegralNumber() ? "n:" + rawId.asLong()
                : rawId.isString() ? "s:" + rawId.asString() : null;
        if (id != null && pending.remove(id) != null) resolved(id);
    }

    private void resolved(String id) {
        Consumer<String> current = resolvedListener;
        if (current != null) current.accept(id);
    }

    private static String key(CodexMessageCodec.RequestId id) {
        return switch (id) {
            case CodexMessageCodec.NumericRequestId n -> "n:" + n.value();
            case CodexMessageCodec.StringRequestId s -> "s:" + s.value();
        };
    }

    public void receive(CodexMessageCodec.ServerRequest request) {
        if (closed) return;
        ServerRequestMethod method = request.method();
        if (method != ServerRequestMethod.ITEM_COMMAND_EXECUTION_REQUEST_APPROVAL
                && method != ServerRequestMethod.ITEM_FILE_CHANGE_REQUEST_APPROVAL) {
            client.sendServerError(request.id(), -32601, "Unsupported server request: " + request.wireMethod());
            return;
        }
        var params = request.params();
        if (params == null || !params.path("threadId").isString()) {
            client.sendServerError(request.id(), -32602, "Missing approval threadId");
            return;
        }
        String id = key(request.id());
        Pending item = new Pending(request);
        if (pending.putIfAbsent(id, item) != null) return;
        CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
            if (pending.remove(id, item)) { decline(request); resolved(id); }
        });
        Consumer<ApprovalPrompt> current = listener;
        if (current == null) {
            if (pending.remove(id, item)) decline(request);
            return;
        }
        try {
            current.accept(new ApprovalPrompt(id, params.path("threadId").asString(),
                    params.path("turnId").asString(null), request.wireMethod(),
                    params.path("reason").asString(null), params.path("command").asString(null),
                    params.path("cwd").asString(null)));
        } catch (RuntimeException error) {
            if (pending.remove(id, item)) decline(request);
        }
    }

    public boolean resolve(String requestId, Decision decision) {
        if (requestId == null || decision == null || closed) return false;
        Pending item = pending.remove(requestId);
        if (item == null) return false;
        sendDecision(item.request(), decision);
        resolved(requestId);
        return true;
    }

    private void decline(CodexMessageCodec.ServerRequest request) {
        sendDecision(request, Decision.DECLINE);
    }

    private void sendDecision(CodexMessageCodec.ServerRequest request, Decision decision) {
        client.sendServerResult(request.id(), Map.of("decision", decision.wire()));
    }

    @Override public void close() {
        closed = true;
        pending.clear();
    }
}
