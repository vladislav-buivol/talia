package com.chatbot.talia.ws.adapter.codex.client;

import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec.Notification;
import com.chatbot.talia.ws.adapter.codex.protocol.response.TurnStartResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.decoder.NotificationMethod;
import tools.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

final class CodexTurn {
    private final Consumer<String> onText;
    private final int maxEarlyNotifications;
    private final Duration startTimeout;
    private final Function<String, CompletableFuture<Void>> interruptSender;
    private final CompletableFuture<CodexTurnResult> result = new CompletableFuture<>();
    private final List<Notification> earlyNotifications = new ArrayList<>();
    private String turnId;
    private boolean cancellationRequested;
    private CompletableFuture<Void> interruption;

    CodexTurn(Consumer<String> onText) {
        this(onText, 128, Duration.ofSeconds(30), id -> CompletableFuture.failedFuture(
                new UnsupportedOperationException("Interrupt handler not configured")));
    }

    CodexTurn(Consumer<String> onText, int maxEarlyNotifications, Duration startTimeout,
              Function<String, CompletableFuture<Void>> interruptSender) {
        this.onText = Objects.requireNonNull(onText);
        this.maxEarlyNotifications = maxEarlyNotifications;
        this.startTimeout = startTimeout;
        this.interruptSender = Objects.requireNonNull(interruptSender);
    }

    PendingRequest<TurnStartResponse> startRequest() {
        CompletableFuture<TurnStartResponse> reply = new CompletableFuture<>();
        reply.whenComplete(this::onStarted);
        return new PendingRequest<>(TurnStartResponse.class, reply, "turn/start", startTimeout);
    }

    CompletableFuture<CodexTurnResult> result() { return result; }

    synchronized CompletableFuture<Void> interrupt() {
        if (result.isDone()) return CompletableFuture.completedFuture(null);
        if (interruption != null) return interruption.copy();
        cancellationRequested = true;
        interruption = new CompletableFuture<>();
        if (turnId != null) sendInterrupt();
        return interruption.copy();
    }

    private synchronized void sendInterrupt() {
        if (result.isDone()) {
            interruption.complete(null);
            return;
        }
        try {
            interruptSender.apply(turnId).whenComplete((ok, error) -> {
                if (error != null) interruption.completeExceptionally(error);
                else interruption.complete(null);
            });
        } catch (RuntimeException error) {
            interruption.completeExceptionally(error);
        }
    }

    private synchronized void onStarted(TurnStartResponse response, Throwable error) {
        if (error != null) {
            result.completeExceptionally(error);
            if (interruption != null) interruption.completeExceptionally(error);
            return;
        }
        if (response.result() == null || response.result().turn() == null || response.result().turn().id() == null) {
            IllegalStateException failure = new IllegalStateException("Codex turn/start reply has no turn ID");
            result.completeExceptionally(failure);
            if (interruption != null) interruption.completeExceptionally(failure);
            return;
        }
        turnId = response.result().turn().id();
        if (cancellationRequested) sendInterrupt();
        List<Notification> deferred = List.copyOf(earlyNotifications);
        earlyNotifications.clear();
        deferred.forEach(this::onNotification);
    }

    synchronized void onNotification(Notification notification) {
        if (result.isDone()) return;
        if (turnId == null) {
            if (earlyNotifications.size() >= maxEarlyNotifications) {
                result.completeExceptionally(new IllegalStateException("Early notification buffer exceeded"));
                return;
            }
            earlyNotifications.add(notification);
            return;
        }
        JsonNode params = notification.params();
        if (params == null) return;
        if (notification.method() == NotificationMethod.ITEM_AGENT_MESSAGE_DELTA) {
            if (!cancellationRequested && turnId.equals(params.path("turnId").asString(null))
                    && params.path("delta").isString()) {
                try { onText.accept(params.path("delta").asString()); }
                catch (RuntimeException error) { result.completeExceptionally(error); }
            }
        } else if (notification.method() == NotificationMethod.TURN_COMPLETED) {
            JsonNode completed = params.path("turn");
            if (turnId.equals(completed.path("id").asString(null))) {
                result.complete(new CodexTurnResult(turnId, completed.path("status").asString(),
                        completed.path("error").path("message").asString(null)));
                if (interruption != null && !interruption.isDone()) interruption.complete(null);
            }
        }
    }
}
