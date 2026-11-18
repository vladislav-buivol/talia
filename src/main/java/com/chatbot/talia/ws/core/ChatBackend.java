package com.chatbot.talia.ws.core;

import com.chatbot.talia.ws.core.protocol.response.ChatMessage;
import com.chatbot.talia.ws.adapter.codex.client.ApprovalCoordinator;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public interface ChatBackend {
    List<String> models();

    boolean supportsModel(String model);

    Conversation start(String model) throws InterruptedException, ExecutionException;

    DialogPage list(int limit, String cursor) throws InterruptedException, ExecutionException;

    Conversation open(String threadId);

    record Dialog(String threadId, String name, String preview) {
    }

    record DialogPage(List<Dialog> dialogs, String nextCursor) {
    }

    record TurnResult(String turnId, String status, String errorMessage) {
    }

    interface Conversation extends AutoCloseable {
        String threadId();

        List<ChatMessage> history(int limit) throws InterruptedException, ExecutionException;

        TurnResult runTurn(String text, String model, Consumer<String> onText)
                throws InterruptedException, ExecutionException;

        void delete() throws InterruptedException, ExecutionException;

        default void onApproval(java.util.function.Consumer<ApprovalCoordinator.ApprovalPrompt> listener) {}

        default boolean resolveApproval(String requestId, ApprovalCoordinator.Decision decision) {
            return false;
        }

        default void interrupt() throws InterruptedException, ExecutionException {
            throw new UnsupportedOperationException("Backend does not support interrupt");
        }

        @Override
        void close();
    }
}
