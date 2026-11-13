package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.protocol.CodexMessageCodec.Notification;
import com.chatbot.talia.ws.codex.protocol.response.TurnStartResponse;
import com.chatbot.talia.ws.codex.protocol.response.decoder.NotificationMethod;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class CodexTurn {
    private final Consumer<String> onText;
    private final CompletableFuture<CodexTurnResult> result = new CompletableFuture<>();
    // The successful start reply and notifications arrive on the same WebSocket listener.
    private final List<Notification> earlyNotifications = new ArrayList<>();
    private String turnId;

    CodexTurn(Consumer<String> onText) {
        this.onText = Objects.requireNonNull(onText);
    }

    PendingRequest<TurnStartResponse> startRequest() {
        CompletableFuture<TurnStartResponse> reply = new CompletableFuture<>();
        reply.whenComplete(this::onStarted);
        return new PendingRequest<>(TurnStartResponse.class, reply);
    }

    CompletableFuture<CodexTurnResult> result() {
        return result;
    }

    private void onStarted(TurnStartResponse response, Throwable error) {
        if (error != null) {
            result.completeExceptionally(error);
            return;
        }
        if (response.result() == null || response.result().turn() == null || response.result().turn().id() == null) {
            result.completeExceptionally(new IllegalStateException("Codex turn/start reply has no turn ID"));
            return;
        }

        turnId = response.result().turn().id();
        for (Notification notification : earlyNotifications) {
            onNotification(notification);
        }
        earlyNotifications.clear();
    }

    void onNotification(Notification notification) {
        if (result.isDone()) {
            return;
        }
        if (turnId == null) {
            earlyNotifications.add(notification);
            return;
        }

        JsonNode params = notification.params();
        if (notification.method() == NotificationMethod.ITEM_AGENT_MESSAGE_DELTA) {
            if (turnId.equals(params.path("turnId").asString(null)) && params.path("delta").isString()) {
                deliverText(params.path("delta").asString());
            }
        } else {
            JsonNode turn = params.path("turn");
            if (turnId.equals(turn.path("id").asString(null))) {
                result.complete(new CodexTurnResult(turnId, turn.path("status").asString(),
                        turn.path("error").path("message").asString(null)));
            }
        }
    }

    private void deliverText(String text) {
        try {
            onText.accept(text);
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
        }
    }
}
