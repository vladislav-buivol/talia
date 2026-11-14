package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.codex.protocol.handler.CodexMessageHandlerRegistry;
import com.chatbot.talia.ws.codex.protocol.request.CodexModel;
import com.chatbot.talia.ws.codex.protocol.request.InitializeRequest;
import com.chatbot.talia.ws.codex.protocol.request.InitializedNotification;
import com.chatbot.talia.ws.codex.protocol.request.ThreadStartRequest;
import com.chatbot.talia.ws.codex.protocol.request.TurnStartRequest;
import com.chatbot.talia.ws.codex.protocol.response.InitializeResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadStartResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("codex")
class CodexWebSocketSessionTests {
    @LocalServerPort
    private int port;
    @Autowired
    private LoopbackServer server;

    @Test
    void listsRpcMethodsFromTheCodexSchema(@TempDir Path directory) throws IOException {
        Path schema = directory.resolve("ClientRequest.json");
        Files.writeString(schema, """
                {"oneOf":[
                  {"properties":{"method":{"enum":["thread/resume"]}}},
                  {"properties":{"method":{"enum":["model/list"]}}},
                  {"properties":{"method":{"const":"thread/resume"}}}
                ]}
                """);
        assertEquals(List.of("thread/resume", "model/list"), CodexRpcSchema.getAvailableActions(schema));

        Files.writeString(schema, "{\"oneOf\":[{\"properties\":{}}]}");
        assertThrows(IOException.class, () -> CodexRpcSchema.getAvailableActions(schema));
    }

    @Test
    void initializesOnceThenExchangesApplicationMessagesAndCloseFrames() throws Exception {
        IllegalArgumentException encodingFailure = new IllegalArgumentException("encoding failed");
        CodexMessageCodec codec = new CodexMessageCodec(new ObjectMapper()) {
            @Override
            public String encode(Object message) {
                if (message instanceof ThreadStartRequest request && request.params().model() == CodexModel.GPT_5_6_SOL) {
                    throw encodingFailure;
                }
                return super.encode(message);
            }
        };
        CodexMessageHandlerRegistry registry = CodexMessageHandlerRegistry.create(new CodexMessageReporter());
        String serverUrl = "ws://127.0.0.1:" + port + "/codex-test";
        try (CodexClient codex = new CodexClient(new CodexWebSocketClient(serverUrl, codec, registry))) {
            CodexWebSocketClient client = codex.rpc();
            assertInstanceOf(IllegalStateException.class, startThread(client).exceptionNow());

            CompletableFuture<Void> initialized = codex.connect("talia", "Talia", "0.1.0");
            JsonNode initialize = server.nextMessage();
            assertEquals("initialize", initialize.path("method").asString());
            assertEquals(0L, initialize.path("id").asLong());
            assertFalse(initialized.isDone());
            assertInstanceOf(IllegalStateException.class, startThread(client).exceptionNow());
            assertInstanceOf(IllegalStateException.class,
                    client.connectAndInitialize("talia", "Talia", "0.1.0").exceptionNow());

            WebSocketSession socket = server.session.get(5, TimeUnit.SECONDS);
            socket.sendMessage(new TextMessage("{\"id\":0,\"result\":{}}"));
            initialized.get(5, TimeUnit.SECONDS);
            JsonNode acknowledgement = server.nextMessage();
            assertEquals("initialized", acknowledgement.path("method").asString());
            assertFalse(acknowledgement.has("id"));
            assertInstanceOf(IllegalArgumentException.class,
                    client.sendNotification(InitializedNotification.create()).exceptionNow());

            assertSame(encodingFailure, client.send(ThreadStartRequest.create(CodexModel.GPT_5_6_SOL),
                    ThreadStartResponse.class).exceptionNow());
            assertInstanceOf(IllegalArgumentException.class, client.send(
                    InitializeRequest.create("talia", "Talia", "0.1.0"),
                    InitializeResponse.class).exceptionNow());
            ThreadStartRequest threadStart = ThreadStartRequest.create(CodexModel.GPT_5_6_LUNA);
            CompletableFuture<ThreadStartResponse> started = client.send(threadStart, ThreadStartResponse.class);
            JsonNode request = server.nextMessage();
            assertEquals("thread/start", request.path("method").asString());
            assertEquals("gpt-5.6-luna", request.path("params").path("model").asString());
            assertEquals(2L, request.path("id").asLong());
            socket.sendMessage(new TextMessage("{\"id\":2,\"result\":{\"thread\":{\"id\":\"thread-1\"}}}"));
            assertEquals("thread-1", started.get(5, TimeUnit.SECONDS).result().thread().id());

            CompletableFuture<ThreadStartResponse> pending = client.send(threadStart, ThreadStartResponse.class);
            JsonNode secondRequest = server.nextMessage();
            assertEquals("thread/start", secondRequest.path("method").asString());
            assertEquals(3L, secondRequest.path("id").asLong());
            assertNull(threadStart.id());
            exchangeTurn(client, socket);
            exchangeConversationHistory(codex, socket);

            CompletableFuture<CodexTurnResult> pendingTurn = client.runTurn(
                    TurnStartRequest.create("thread-1", "disconnect"), text -> fail("Unexpected text"));
            assertEquals("turn/start", server.nextMessage().path("method").asString());
            CompletableFuture<String> finished = codex.finishConversationAndCloseAsync("thread-2");
            JsonNode unsubscribe = server.nextMessage();
            assertEquals("thread/unsubscribe", unsubscribe.path("method").asString());
            assertEquals("thread-2", unsubscribe.path("params").path("threadId").asString());
            assertFalse(finished.isDone());
            assertFalse(server.closed.isDone());
            socket.sendMessage(new TextMessage("""
                    {"id":%d,"result":{"status":"unsubscribed"}}
                    """.formatted(unsubscribe.path("id").asLong())));
            assertEquals("unsubscribed", finished.get(5, TimeUnit.SECONDS));
            assertEquals(CloseStatus.NORMAL.getCode(), server.closed.get(5, TimeUnit.SECONDS).getCode());
            assertInstanceOf(IOException.class, assertThrows(ExecutionException.class,
                    () -> pending.get(5, TimeUnit.SECONDS)).getCause());
            assertInstanceOf(IOException.class, assertThrows(ExecutionException.class,
                    () -> pendingTurn.get(5, TimeUnit.SECONDS)).getCause());
            assertInstanceOf(IOException.class, startThread(client).exceptionNow());
        }
    }

    private void exchangeConversationHistory(CodexClient client, WebSocketSession socket) throws Exception {
        CompletableFuture<CodexClient.ConversationPage> listed = client.listConversations(2);
        reply(socket, "thread/list", """
                {"data":[{"id":"thread-1","name":"Demo","preview":"Hello","updatedAt":42}],"nextCursor":"more"}
                """);
        assertEquals("Demo", listed.get(5, TimeUnit.SECONDS).conversations().getFirst().name());
        assertEquals("more", listed.resultNow().nextCursor());

        CompletableFuture<String> started = client.startConversation(CodexModel.GPT_5_6_LUNA);
        reply(socket, "thread/start", "{\"thread\":{\"id\":\"thread-2\"}}");
        assertEquals("thread-2", started.get(5, TimeUnit.SECONDS));

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<ConversationHistory> opened = executor.submit(() ->
                    client.openConversation("thread-1", 3, ConversationHistory.Order.NEWEST_FIRST));
            reply(socket, "thread/resume", "{\"thread\":{\"id\":\"thread-1\"}}");
            JsonNode newest = reply(socket, "thread/turns/list", """
                    {"data":[{"items":[{"type":"userMessage","content":[{"type":"text","text":"Question"}]},
                    {"type":"agentMessage","text":"Answer"}]}],"nextCursor":"older"}
                    """);
            assertEquals("desc", newest.path("params").path("sortDirection").asString());
            JsonNode older = reply(socket, "thread/turns/list", """
                    {"data":[{"items":[{"type":"userMessage","content":[{"type":"text","text":"First"}]},
                    {"type":"commandExecution"}]}],"nextCursor":null}
                    """);
            assertEquals("older", older.path("params").path("cursor").asString());
            List<ConversationHistory.Message> messages = opened.get(5, TimeUnit.SECONDS).messages();
            assertEquals(List.of("Answer", "Question", "First"), messages.stream().map(ConversationHistory.Message::text).toList());
            assertEquals(ConversationHistory.Role.ASSISTANT, messages.getFirst().role());

            Future<List<ConversationHistory>> histories = executor.submit(() ->
                    client.getConversationHistory(2, 1, ConversationHistory.Order.OLDEST_FIRST));
            reply(socket, "thread/list", "{\"data\":[{\"id\":\"thread-1\"}],\"nextCursor\":\"more\"}");
            JsonNode oldest = reply(socket, "thread/turns/list", """
                    {"data":[{"items":[{"type":"userMessage","content":[{"type":"text","text":"First"}]},
                    {"type":"agentMessage","text":"Second"}]}],"nextCursor":null}
                    """);
            assertEquals("asc", oldest.path("params").path("sortDirection").asString());
            JsonNode next = reply(socket, "thread/list", "{\"data\":[{\"id\":\"thread-2\"}],\"nextCursor\":null}");
            assertEquals("more", next.path("params").path("cursor").asString());
            reply(socket, "thread/turns/list", "{\"data\":[],\"nextCursor\":null}");
            List<ConversationHistory> recent = histories.get(5, TimeUnit.SECONDS);
            assertEquals(2, recent.size());
            assertEquals("First", recent.getFirst().messages().getFirst().text());
            assertTrue(recent.getLast().messages().isEmpty());
        }

        CompletableFuture<CodexTurnResult> active = client.runTurn("thread-1", "finish", System.out::print);
        sendTurnReply(socket, server.nextMessage(), "turn-finish");
        CompletableFuture<String> finished = client.finishConversation("thread-1");
        JsonNode unsubscribe = reply(socket, "thread/unsubscribe", "{\"status\":\"unsubscribed\"}");
        assertEquals("thread-1", unsubscribe.path("params").path("threadId").asString());
        assertEquals("unsubscribed", finished.get(5, TimeUnit.SECONDS));
        assertThrows(CancellationException.class, active::join);
    }

    private JsonNode reply(WebSocketSession socket, String method, String result) throws Exception {
        JsonNode request = server.nextMessage();
        assertEquals(method, request.path("method").asString());
        socket.sendMessage(new TextMessage("{\"id\":%d,\"result\":%s}".formatted(request.path("id").asLong(), result)));
        return request;
    }

    private void exchangeTurn(CodexWebSocketClient client, WebSocketSession socket) throws Exception {
        List<String> text = new ArrayList<>();
        TurnStartRequest turn = TurnStartRequest.create("thread-1", "hello");
        CompletableFuture<CodexTurnResult> result = client.runTurn(turn, text::add);
        assertInstanceOf(IllegalStateException.class, client.runTurn(turn, text::add).exceptionNow());
        JsonNode request = server.nextMessage();
        assertEquals("turn/start", request.path("method").asString());

        socket.sendMessage(new TextMessage("""
                {"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"old-turn","delta":"ignore"}}
                """));
        socket.sendMessage(new TextMessage("""
                {"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","delta":"Hello"}}
                """));
        sendTurnReply(socket, request, "turn-1");
        socket.sendMessage(new TextMessage("""
                {"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","delta":" world"}}
                """));
        socket.sendMessage(new TextMessage("""
                {"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}}
                """));
        assertEquals(new CodexTurnResult("turn-1", "completed", null), result.get(5, TimeUnit.SECONDS));
        assertEquals(List.of("Hello", " world"), text);

        CompletableFuture<CodexTurnResult> failed = client.runTurn(turn, text::add);
        sendTurnReply(socket, server.nextMessage(), "turn-2");
        socket.sendMessage(new TextMessage("""
                {"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-2","status":"failed","error":{"message":"limit reached"}}}}
                """));
        assertEquals(new CodexTurnResult("turn-2", "failed", "limit reached"), failed.get(5, TimeUnit.SECONDS));
    }

    private static void sendTurnReply(WebSocketSession socket, JsonNode request, String turnId) throws IOException {
        socket.sendMessage(new TextMessage("""
                {"id":%d,"result":{"turn":{"id":"%s","status":"inProgress"}}}
                """.formatted(request.path("id").asLong(), turnId)));
    }

    private static CompletableFuture<ThreadStartResponse> startThread(CodexWebSocketClient client) {
        return client.send(ThreadStartRequest.create(CodexModel.GPT_5_6_LUNA), ThreadStartResponse.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LoopbackConfiguration {
        @Bean
        LoopbackServer loopbackServer() {
            return new LoopbackServer();
        }

        @Bean
        WebSocketConfigurer loopbackEndpoint(LoopbackServer server) {
            return registry -> registry.addHandler(server, "/codex-test");
        }
    }

    static final class LoopbackServer extends TextWebSocketHandler {
        private final ObjectMapper mapper = new ObjectMapper();
        private final LinkedBlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<WebSocketSession> session = new CompletableFuture<>();
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            this.session.complete(session);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(mapper.readTree(message.getPayload()));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }

        JsonNode nextMessage() throws InterruptedException {
            JsonNode message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "Expected a WebSocket message");
            return message;
        }
    }
}
