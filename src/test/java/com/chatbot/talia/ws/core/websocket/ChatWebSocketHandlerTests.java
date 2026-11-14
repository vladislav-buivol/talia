package com.chatbot.talia.ws.core.websocket;

import com.chatbot.talia.ws.adapter.codex.CodexChatBackend;
import com.chatbot.talia.ws.adapter.codex.client.CodexClient;
import com.chatbot.talia.ws.adapter.codex.client.CodexTurnResult;
import com.chatbot.talia.ws.adapter.codex.client.ConversationHistory;
import com.chatbot.talia.ws.adapter.codex.protocol.request.CodexModel;
import com.chatbot.talia.ws.adapter.codex.protocol.request.InitializeRequest.ClientInfo;
import com.chatbot.talia.ws.core.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatWebSocketHandlerTests {
    private static final ClientInfo CLIENT_INFO = new ClientInfo("chat-test", "Chat Test", "2.0.0");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void sharesHistoryAndStreamingWhileWriterChanges() throws Exception {
        CodexClient codex = mock(CodexClient.class);
        when(codex.connect(CLIENT_INFO.name(), CLIENT_INFO.title(), CLIENT_INFO.version()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(codex.resumeConversation("thread-1")).thenReturn(CompletableFuture.completedFuture("thread-1"));
        when(codex.listConversations(2, null)).thenReturn(CompletableFuture.completedFuture(
                new CodexClient.ConversationPage(List.of(
                        new CodexClient.ConversationSummary("thread-1", "Saved chat", "Saved message", null)), null)));
        when(codex.getConversationHistory("thread-1", 50, ConversationHistory.Order.NEWEST_FIRST))
                .thenReturn(new ConversationHistory("thread-1",
                        List.of(new ConversationHistory.Message(ConversationHistory.Role.USER, "Saved message", null))));
        CompletableFuture<Consumer<String>> streaming = new CompletableFuture<>();
        CompletableFuture<CodexTurnResult> active = new CompletableFuture<>();
        when(codex.runTurn(eq("thread-1"), eq("First"), any())).thenAnswer(invocation -> {
            streaming.complete(invocation.getArgument(2, Consumer.class));
            return active;
        });
        when(codex.runTurn(eq("thread-1"), eq("Second"), eq(CodexModel.GPT_5_6_SOL), any()))
                .thenReturn(CompletableFuture.completedFuture(new CodexTurnResult("turn-2", "completed", null)));

        ChatWebSocketHandler handler = new ChatWebSocketHandler(mapper,
                new ChatService(mapper, new CodexChatBackend(() -> codex, CLIENT_INFO)));
        Peer writer = new Peer("writer");
        Peer reader = new Peer("reader");
        try {
            handler.afterConnectionEstablished(writer.socket);
            handler.afterConnectionEstablished(reader.socket);
            assertEquals("startNewDialog", writer.nextEvent("connected").path("availableAction").path(0).asString());
            reader.next("connected");
            writer.send(handler, """
                    {"action":"connectAsWriter","threadId":"thread-1"}
                    """);
            assertEquals("writer", writer.next("session.joined").path("writerId").asString());
            reader.send(handler, """
                    {"action":"getDialogs","limit":2}
                    """);
            JsonNode dialog = reader.next("dialogs").path("dialogs").path(0);
            assertEquals("thread-1", dialog.path("threadId").asString());
            assertEquals("Saved chat", dialog.path("name").asString());
            assertEquals(1, dialog.path("availableAction").size());
            reader.send(handler, """
                    {"action":"continueDialog","threadId":"thread-1"}
                    """);
            assertEquals("connectAsReader", reader.nextEvent("dialog.selected").path("availableAction").path(3).asString());
            reader.send(handler, """
                    {"action":"connectAsWriter"}
                    """);
            assertEquals("writer_unavailable", reader.next("error").path("code").asString());
            reader.send(handler, """
                    {"action":"connectAsReader"}
                    """);
            assertEquals("writer", reader.next("session.joined").path("writerId").asString());
            assertEquals("reader", writer.next("session.updated").path("joinedClientId").asString());

            reader.send(handler, """
                    {"action":"getHistory"}
                    """);
            assertEquals("Saved message", reader.next("history").path("messages").path(0).path("text").asString());
            reader.send(handler, """
                    {"action":"sendMessage","text":"Blocked"}
                    """);
            assertEquals("read_only", reader.next("error").path("code").asString());
            verify(codex, never()).runTurn(eq("thread-1"), eq("Blocked"), any());

            writer.send(handler, """
                    {"action":"sendMessage","text":"First"}
                    """);
            String turnId = writer.next("user.text").path("turnId").asString();
            assertEquals(turnId, reader.next("user.text").path("turnId").asString());
            Consumer<String> delta = streaming.get(5, TimeUnit.SECONDS);
            writer.send(handler, """
                    {"action":"transferWriter","clientId":"reader"}
                    """);
            assertEquals("reader", writer.next("writer.changed").path("writerId").asString());
            assertEquals("reader", reader.next("writer.changed").path("writerId").asString());
            reader.send(handler, """
                    {"action":"sendMessage","text":"Second"}
                    """);
            assertEquals("turn_in_progress", reader.next("error").path("code").asString());

            delta.accept("Still streaming");
            assertEquals("Still streaming", writer.next("assistant.text.delta").path("text").asString());
            assertEquals("Still streaming", reader.next("assistant.text.delta").path("text").asString());
            active.complete(new CodexTurnResult("turn-1", "completed", null));
            assertEquals(turnId, writer.next("assistant.text.done").path("turnId").asString());
            assertEquals(turnId, reader.next("assistant.text.done").path("turnId").asString());

            handler.afterConnectionClosed(reader.socket, CloseStatus.NORMAL);
            assertEquals("writer", writer.next("writer.changed").path("writerId").asString());
            writer.send(handler, """
                    {"action":"changeModel","model":"gpt-5.6-sol"}
                    """);
            assertEquals("gpt-5.6-sol", writer.next("model.changed").path("model").asString());
            writer.send(handler, """
                    {"action":"sendMessage","text":"Second"}
                    """);
            writer.next("user.text");
            assertEquals("turn-2", writer.next("assistant.text.done").path("codexTurnId").asString());
            verify(codex).resumeConversation("thread-1");
            writer.send(handler, """
                    {"action":"finishChat"}
                    """);
            assertEquals("startNewDialog", writer.nextEvent("chat.finished").path("availableAction").path(0).asString());
            writer.send(handler, """
                    {"action":"connectAsReader","threadId":"thread-1"}
                    """);
            assertFalse(writer.nextEvent("session.joined").path("availableAction").toString().contains("sendMessage"));
            writer.send(handler, """
                    {"action":"closeChat"}
                    """);
            assertEquals("thread-1", writer.next("chat.closed").path("threadId").asString());
        } finally {
            handler.afterConnectionClosed(writer.socket, CloseStatus.NORMAL);
            handler.afterConnectionClosed(reader.socket, CloseStatus.NORMAL);
            handler.close();
        }
    }

    @Test
    void startsAndWritesToNewDialogWithoutKnowingItsId() throws Exception {
        CodexClient codex = mock(CodexClient.class);
        when(codex.connect(CLIENT_INFO.name(), CLIENT_INFO.title(), CLIENT_INFO.version()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(codex.startConversation(CodexModel.GPT_5_6_SOL)).thenReturn(CompletableFuture.completedFuture("new-thread"));
        when(codex.runTurn(eq("new-thread"), eq("Hello"), eq(CodexModel.GPT_5_6_SOL), any()))
                .thenReturn(CompletableFuture.completedFuture(new CodexTurnResult("turn", "completed", null)));
        ChatWebSocketHandler handler = new ChatWebSocketHandler(mapper,
                new ChatService(mapper, new CodexChatBackend(() -> codex, CLIENT_INFO)));
        Peer creator = new Peer("creator");
        try {
            handler.afterConnectionEstablished(creator.socket);
            creator.next("connected");
            creator.send(handler, """
                    {"action":"startNewDialog","model":"gpt-5.6-sol"}
                    """);
            assertEquals("new-thread", creator.next("session.joined").path("threadId").asString());
            creator.send(handler, """
                    {"action":"sendMessage","text":"Hello"}
                    """);
            creator.next("user.text");
            assertEquals("completed", creator.next("assistant.text.done").path("status").asString());
            verify(codex, never()).resumeConversation(any());
        } finally {
            handler.afterConnectionClosed(creator.socket, CloseStatus.NORMAL);
            handler.close();
        }
    }

    private final class Peer {
        private final WebSocketSession socket = mock(WebSocketSession.class);
        private final LinkedBlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();

        Peer(String id) throws Exception {
            when(socket.getId()).thenReturn(id);
            when(socket.getUri()).thenReturn(URI.create("ws://localhost/ws/chat"));
            when(socket.isOpen()).thenReturn(true);
            doAnswer(invocation -> {
                TextMessage message = invocation.getArgument(0);
                messages.add(mapper.readTree(message.getPayload()));
                return null;
            }).when(socket).sendMessage(any(TextMessage.class));
        }

        void send(ChatWebSocketHandler handler, String command) {
            handler.handleTextMessage(socket, new TextMessage(command));
        }

        JsonNode next(String type) throws InterruptedException {
            return nextEvent(type).path("data");
        }

        JsonNode nextEvent(String type) throws InterruptedException {
            JsonNode message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "Expected " + type);
            assertEquals(type, message.path("type").asString());
            return message;
        }
    }
}
