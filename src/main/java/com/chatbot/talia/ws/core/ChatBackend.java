package com.chatbot.talia.ws.core;

import com.chatbot.talia.ws.core.protocol.response.ChatMessage;
import com.chatbot.talia.ws.adapter.codex.client.approval.ApprovalCoordinator;

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

    /** The upstream session rejected the writer before the message was submitted. */
    final class ThreadWriteConflict extends RuntimeException {
        public ThreadWriteConflict(Throwable cause) {
            super("This chat is open for writing in another Codex session. Close it there, then try sending again.", cause);
        }
    }

    interface Conversation extends AutoCloseable {
        String threadId();

        List<ChatMessage> history(int limit) throws InterruptedException, ExecutionException;

        TurnResult runTurn(String text, String model, Consumer<String> onText)
                throws InterruptedException, ExecutionException;

        default TurnResult runTurn(String text, String model, Consumer<String> onText, Consumer<String> onProgress)
                throws InterruptedException, ExecutionException {
            return runTurn(text, model, onText);
        }

        void delete() throws InterruptedException, ExecutionException;

        default void onApproval(Consumer<ApprovalCoordinator.ApprovalPrompt> listener) {
        }
        default void onApprovalResolved(Consumer<String> listener) {
        }

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
