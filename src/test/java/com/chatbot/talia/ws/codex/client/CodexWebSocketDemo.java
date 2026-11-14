package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.protocol.request.CodexModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class CodexWebSocketDemo {
    private static final Logger log = LoggerFactory.getLogger(CodexWebSocketDemo.class);
    private static final String MESSAGE = "hello from intellij idea";
    private static final int RECENT_CONVERSATIONS = 10;
    private static final Duration HISTORY_PAGE_TIMEOUT = Duration.ofSeconds(10);

    private CodexWebSocketDemo() {
    }

    public static void main(String[] args) throws Exception {
        String serverUrl = "ws://127.0.0.1:4222";

        try (CodexClient client = CodexClient.create(serverUrl)) {
            client.connect("talia", "Talia", "0.1.0").get(15, TimeUnit.SECONDS);

            String threadId = openConversation(client);
            CodexTurnResult result = client.runTurn(threadId, MESSAGE, System.out::print).get(2, TimeUnit.MINUTES);
            System.out.println();
            log.info("Turn {} finished with status {}", result.turnId(), result.status());
            if (result.errorMessage() != null) {
                log.warn("{}", result.errorMessage());
            }
            String status = client.finishConversationAndCloseAsync(threadId).get(15, TimeUnit.SECONDS);
            log.info("Conversation unsubscribe status: {}", status);
            log.info("WebSocket connection closed successfully. Exiting.");
        }
    }

    static String openConversation(CodexClient client)
            throws InterruptedException, ExecutionException, TimeoutException {
        CodexClient.ConversationPage recent = client.listConversations(RECENT_CONVERSATIONS).get(10, TimeUnit.SECONDS);
        log.info("Recent conversations: {}", recent);
        for (CodexClient.ConversationSummary conversation : recent.conversations()) {
            if (MESSAGE.equals(client.getFirstUserText(conversation.id(), HISTORY_PAGE_TIMEOUT))) {
                String threadId = client.resumeConversation(conversation.id()).get(10, TimeUnit.SECONDS);
                log.info("Resumed conversation {}", threadId);
                return threadId;
            }
        }

        String threadId = client.startConversation(CodexModel.GPT_5_6_LUNA).get(10, TimeUnit.SECONDS);
        log.info("Started conversation {}", threadId);
        return threadId;
    }
}
