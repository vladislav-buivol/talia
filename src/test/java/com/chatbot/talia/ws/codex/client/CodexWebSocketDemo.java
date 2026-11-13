package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.codex.protocol.handler.CodexMessageHandlerRegistry;
import com.chatbot.talia.ws.codex.protocol.request.CodexModel;
import com.chatbot.talia.ws.codex.protocol.request.ThreadListRequest;
import com.chatbot.talia.ws.codex.protocol.request.ThreadResumeRequest;
import com.chatbot.talia.ws.codex.protocol.request.ThreadStartRequest;
import com.chatbot.talia.ws.codex.protocol.request.ThreadTurnsListRequest;
import com.chatbot.talia.ws.codex.protocol.request.TurnStartRequest;
import com.chatbot.talia.ws.codex.protocol.response.ThreadListResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadResumeResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadStartResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadTurnsListResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class CodexWebSocketDemo {
    private static final Logger log = LoggerFactory.getLogger(CodexWebSocketDemo.class);
    private static final String MESSAGE = "hello from intellij idea";
    private static final int RECENT_CONVERSATIONS = 10;

    private CodexWebSocketDemo() {
    }

    public static void main(String[] args) throws Exception {
        CodexMessageCodec messageCodec = new CodexMessageCodec(new ObjectMapper());
        CodexMessageHandlerRegistry handlerRegistry = CodexMessageHandlerRegistry.create(new CodexMessageReporter());
        String serverUrl = "ws://127.0.0.1:4222";

        try (CodexWebSocketClient client = new CodexWebSocketClient(serverUrl, messageCodec, handlerRegistry)) {
            client.connectAndInitialize("talia", "Talia", "0.1.0").get(15, TimeUnit.SECONDS);

            String threadId = openConversation(client);
            TurnStartRequest request = TurnStartRequest.create(threadId, MESSAGE);
            CodexTurnResult result = client.runTurn(request, System.out::print).get(2, TimeUnit.MINUTES);
            System.out.println();
            log.info("Turn {} finished with status {}", result.turnId(), result.status());
            if (result.errorMessage() != null) {
                log.warn("{}", result.errorMessage());
            }
            client.closeAsync().get(5, TimeUnit.SECONDS);
            log.info("WebSocket connection closed successfully. Exiting.");
        }
    }

    static String openConversation(CodexWebSocketClient client)
            throws InterruptedException, ExecutionException, TimeoutException {
        ThreadListResponse recent = client.send(ThreadListRequest.create(RECENT_CONVERSATIONS),
                ThreadListResponse.class).get(10, TimeUnit.SECONDS);
        for (ThreadListResponse.ThreadInfo thread : recent.result().data()) {
            if (MESSAGE.equals(firstUserMessage(client, thread.id()))) {
                ThreadResumeResponse resumed = client.send(ThreadResumeRequest.create(thread.id()),
                        ThreadResumeResponse.class).get(10, TimeUnit.SECONDS);
                log.info("Resumed conversation {}", resumed.result().thread().id());
                return resumed.result().thread().id();
            }
        }

        ThreadStartResponse started = client.send(ThreadStartRequest.create(CodexModel.GPT_5_6_LUNA),
                ThreadStartResponse.class).get(10, TimeUnit.SECONDS);
        log.info("Started conversation {}", started.result().thread().id());
        return started.result().thread().id();
    }

    private static String firstUserMessage(CodexWebSocketClient client, String threadId)
            throws InterruptedException, ExecutionException, TimeoutException {
        String cursor = null;
        do {
            ThreadTurnsListResponse response = client.send(ThreadTurnsListRequest.create(threadId, cursor),
                    ThreadTurnsListResponse.class).get(10, TimeUnit.SECONDS);
            for (ThreadTurnsListResponse.Turn turn : response.result().data()) {
                for (ThreadTurnsListResponse.Item item : turn.items()) {
                    if ("userMessage".equals(item.type())) {
                        return messageText(item.content());
                    }
                }
            }
            cursor = response.result().nextCursor();
        } while (cursor != null);
        return null;
    }

    private static String messageText(JsonNode content) {
        if (content == null || !content.isArray()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode input : content) {
            if (!"text".equals(input.path("type").asString()) || !input.path("text").isString()) {
                return null;
            }
            text.append(input.path("text").asString());
        }
        return text.toString();
    }
}
