package com.chatbot.talia.ws.adapter.codex;

import com.chatbot.talia.ws.adapter.codex.client.CodexClient;
import com.chatbot.talia.ws.adapter.codex.client.CodexTurnResult;
import com.chatbot.talia.ws.adapter.codex.client.ConversationHistory;
import com.chatbot.talia.ws.adapter.codex.protocol.request.CodexModel;
import com.chatbot.talia.ws.adapter.codex.protocol.request.InitializeRequest.ClientInfo;
import com.chatbot.talia.ws.core.ChatBackend;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodexChatBackendTests {

    @Test
    void newChatHasEmptyHistoryUntilItsFirstTurnAndDefaultsToLuna() throws Exception {
        CodexClient client = mock(CodexClient.class);
        when(client.connect("talia", "Talia", "0.1.0")).thenReturn(CompletableFuture.completedFuture(null));
        when(client.startConversation(CodexModel.GPT_5_6_LUNA))
                .thenReturn(CompletableFuture.completedFuture("new-thread"));
        when(client.finishConversation("new-thread")).thenReturn(CompletableFuture.completedFuture("unsubscribed"));
        when(client.runTurn(eq("new-thread"), eq("Hello"), eq(CodexModel.GPT_5_6_LUNA), any()))
                .thenReturn(CompletableFuture.completedFuture(new CodexTurnResult("turn-1", "completed", null)));
        when(client.getConversationHistory("new-thread", 50, ConversationHistory.Order.NEWEST_FIRST))
                .thenReturn(new ConversationHistory("new-thread", List.of(
                        new ConversationHistory.Message(ConversationHistory.Role.USER, "Hello", null))));
        CodexChatBackend backend = new CodexChatBackend(() -> client, new ClientInfo("talia", "Talia", "0.1.0"));

        try (ChatBackend.Conversation conversation = backend.start(null)) {
            assertEquals(List.of(), conversation.history(50));
            verify(client, never()).getConversationHistory("new-thread", 50, ConversationHistory.Order.NEWEST_FIRST);
            conversation.runTurn("Hello", null, text -> {});
            assertEquals("Hello", conversation.history(50).getFirst().text());
        }
    }

    @Test
    void reopeningWaitsForPreviousSubscriptionToClose() throws Exception {
        CodexClient first = mock(CodexClient.class);
        CodexClient second = mock(CodexClient.class);
        ClientInfo info = new ClientInfo("talia", "Talia", "0.1.0");
        for (CodexClient client : List.of(first, second)) {
            when(client.connect(info.name(), info.title(), info.version())).thenReturn(CompletableFuture.completedFuture(null));
        }
        var releaseRequested = new CompletableFuture<Void>();
        var unsubscribe = new CompletableFuture<String>();
        var firstClosed = new CompletableFuture<Void>();
        when(first.startConversation(CodexModel.GPT_5_6_LUNA)).thenReturn(CompletableFuture.completedFuture("thread-1"));
        when(first.finishConversation("thread-1")).thenAnswer(invocation -> {
            releaseRequested.complete(null);
            return unsubscribe;
        });
        doAnswer(invocation -> { firstClosed.complete(null); return null; }).when(first).close();
        when(second.resumeConversation("thread-1")).thenAnswer(invocation -> {
            assertTrue(firstClosed.isDone());
            return CompletableFuture.completedFuture("thread-1");
        });
        when(second.finishConversation("thread-1")).thenReturn(CompletableFuture.completedFuture("unsubscribed"));
        when(second.runTurn(eq("thread-1"), eq("Continue"), eq(CodexModel.GPT_5_6_LUNA), any()))
                .thenReturn(CompletableFuture.completedFuture(new CodexTurnResult("turn-2", "completed", null)));

        var clients = List.of(first, second).iterator();
        var backend = new CodexChatBackend(clients::next, info);
        backend.start(null).close();
        releaseRequested.get(5, TimeUnit.SECONDS);
        try (var reopened = backend.open("thread-1")) {
            var result = new CompletableFuture<ChatBackend.TurnResult>();
            Thread.startVirtualThread(() -> {
                try {
                    result.complete(reopened.runTurn("Continue", null, text -> {}));
                } catch (Exception error) {
                    result.completeExceptionally(error);
                }
            });
            verify(second, after(150).never()).resumeConversation("thread-1");
            unsubscribe.complete("unsubscribed");
            assertEquals("turn-2", result.get(5, TimeUnit.SECONDS).turnId());
        } finally {
            unsubscribe.complete("unsubscribed");
        }
    }

    @Test
    void failedResumeDoesNotInterruptTheFollowingTurn() throws Exception {
        CodexClient client = mock(CodexClient.class);
        when(client.connect("talia", "Talia", "0.1.0"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(client.resumeConversation("thread-1"))
                .thenReturn(
                        CompletableFuture.failedFuture(new IllegalStateException("Temporary resume failure")),
                        CompletableFuture.completedFuture("thread-1"));
        when(client.runTurn(eq("thread-1"), eq("second"), eq(CodexModel.GPT_5_6_LUNA), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        new CodexTurnResult("turn-2", "completed", null)));
        when(client.finishConversation("thread-1")).thenReturn(CompletableFuture.completedFuture("unsubscribed"));

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
