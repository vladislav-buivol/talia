package com.chatbot.talia.ws.adapter.codex.client;

import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.adapter.codex.protocol.response.TurnStartResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.decoder.NotificationMethod;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CodexTurnCancellationTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void stopBeforeStartReplySendsInterruptForRealCodexTurnId() {
        AtomicReference<String> stopped = new AtomicReference<>();
        AtomicInteger deltas = new AtomicInteger();
        CodexTurn turn = new CodexTurn(text -> deltas.incrementAndGet(), 8, Duration.ofSeconds(30),
                id -> { stopped.set(id); return CompletableFuture.completedFuture(null); });
        CompletableFuture<Void> first = turn.interrupt();
        CompletableFuture<Void> duplicate = turn.interrupt();
        assertFalse(first.isDone());

        turn.startRequest().future().complete(new TurnStartResponse(1L,
                new TurnStartResponse.Result(new TurnStartResponse.TurnInfo("codex-42", "inProgress"))));
        first.join();
        duplicate.join();
        assertEquals("codex-42", stopped.get());
        turn.onNotification(notification(NotificationMethod.ITEM_AGENT_MESSAGE_DELTA,
                "item/agentMessage/delta", """
                {"threadId":"thread","turnId":"codex-42","delta":"late"}
                """));
        turn.onNotification(notification(NotificationMethod.TURN_COMPLETED,
                "turn/completed", """
                {"threadId":"thread","turn":{"id":"codex-42","status":"interrupted"}}
                """));
        assertEquals(0, deltas.get());
        assertEquals("interrupted", turn.result().join().status());
    }

    @Test
    void terminalNotificationBeforeStartReplyIsReplayed() {
        CodexTurn turn = new CodexTurn(text -> {}, 4, Duration.ofSeconds(30),
                id -> CompletableFuture.completedFuture(null));
        turn.onNotification(notification(NotificationMethod.TURN_COMPLETED,
                "turn/completed", """
                {"threadId":"thread","turn":{"id":"codex-7","status":"completed"}}
                """));
        assertFalse(turn.result().isDone());
        turn.startRequest().future().complete(new TurnStartResponse(3L,
                new TurnStartResponse.Result(new TurnStartResponse.TurnInfo("codex-7", "inProgress"))));
        assertEquals("completed", turn.result().join().status());
    }

    private CodexMessageCodec.Notification notification(NotificationMethod method, String name, String json) {
        var params = mapper.readTree(json);
        return new CodexMessageCodec.Notification(method, name, params, params);
    }
}
