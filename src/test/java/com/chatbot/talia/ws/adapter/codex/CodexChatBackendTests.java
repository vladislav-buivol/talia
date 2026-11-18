package com.chatbot.talia.ws.adapter.codex;

import com.chatbot.talia.ws.adapter.codex.client.CodexClient;
import com.chatbot.talia.ws.adapter.codex.client.CodexTurnResult;
import com.chatbot.talia.ws.adapter.codex.protocol.request.InitializeRequest.ClientInfo;
import com.chatbot.talia.ws.core.ChatBackend;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodexChatBackendTests {

    @Test
    void failedResumeDoesNotInterruptTheFollowingTurn() throws Exception {
        CodexClient client = mock(CodexClient.class);
        when(client.connect("talia", "Talia", "0.1.0"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(client.resumeConversation("thread-1"))
                .thenReturn(
                        CompletableFuture.failedFuture(new IllegalStateException("Temporary resume failure")),
                        CompletableFuture.completedFuture("thread-1"));
        when(client.runTurn(eq("thread-1"), eq("second"), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        new CodexTurnResult("turn-2", "completed", null)));

        CodexChatBackend backend = new CodexChatBackend(
                () -> client, new ClientInfo("talia", "Talia", "0.1.0"));

        try (ChatBackend.Conversation conversation = backend.open("thread-1")) {
            conversation.interrupt();
            assertThrows(ExecutionException.class, () -> conversation.runTurn("first", null, text -> {
            }));

            ChatBackend.TurnResult result = conversation.runTurn("second", null, text -> {
            });
            assertEquals("turn-2", result.turnId());
            assertEquals("completed", result.status());
            verify(client, times(2)).resumeConversation("thread-1");
            verify(client, never()).interruptTurn("thread-1");
        }
    }
}
