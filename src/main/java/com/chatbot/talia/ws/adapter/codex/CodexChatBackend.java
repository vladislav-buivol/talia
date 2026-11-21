package com.chatbot.talia.ws.adapter.codex;

import com.chatbot.talia.ws.adapter.codex.client.CodexClient;
import com.chatbot.talia.ws.adapter.codex.client.CodexTurnResult;
import com.chatbot.talia.ws.adapter.codex.client.ConversationHistory;
import com.chatbot.talia.ws.adapter.codex.client.approval.ApprovalCoordinator;
import com.chatbot.talia.ws.adapter.codex.client.rpc.CodexRpcException;
import com.chatbot.talia.ws.adapter.codex.protocol.request.CodexModel;
import com.chatbot.talia.ws.adapter.codex.protocol.request.InitializeRequest.ClientInfo;
import com.chatbot.talia.ws.core.ChatBackend;
import com.chatbot.talia.ws.core.protocol.response.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class CodexChatBackend implements ChatBackend {
    private static final Logger log = LoggerFactory.getLogger(CodexChatBackend.class);
    private static final CodexModel DEFAULT_MODEL = CodexModel.GPT_5_6_LUNA;
    private final Supplier<CodexClient> clients;
    private final ClientInfo clientInfo;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> releasingThreads = new ConcurrentHashMap<>();

    public CodexChatBackend(Supplier<CodexClient> clients, ClientInfo clientInfo) {
        this.clients = clients;
        this.clientInfo = clientInfo;
    }

    @Override
    public List<String> models() {
        List<String> models = new ArrayList<>();
        for (CodexModel model : CodexModel.values()) {
            models.add(model.id());
        }
        return List.copyOf(models);
    }

    @Override
    public boolean supportsModel(String model) {
        return CodexModel.fromId(model) != null;
    }

    @Override
    public Conversation start(String model) throws InterruptedException, ExecutionException {
        CodexClient client = clients.get();
        try {
            connect(client).get();
            String threadId = client.startConversation(model == null ? DEFAULT_MODEL : CodexModel.fromId(model)).get();
            return new CodexConversation(threadId, client);
        } catch (InterruptedException | ExecutionException | RuntimeException error) {
            client.close();
            throw error;
        }
    }

    @Override
    public DialogPage list(int limit, String cursor) throws InterruptedException, ExecutionException {
        try (CodexClient client = clients.get()) {
            connect(client).get();
            CodexClient.ConversationPage page = client.listConversations(limit, cursor).get();
            List<Dialog> dialogs = new ArrayList<>();
            for (CodexClient.ConversationSummary dialog : page.conversations()) {
                dialogs.add(new Dialog(dialog.id(), dialog.name(), dialog.preview()));
            }
            return new DialogPage(dialogs, page.nextCursor());
        }
    }

    @Override
    public Conversation open(String threadId) {
        return new CodexConversation(threadId, null);
    }

    private CompletableFuture<Void> connect(CodexClient client) {
        return client.connect(clientInfo.name(), clientInfo.title(), clientInfo.version());
    }

    private void releaseThread(String threadId, CodexClient client) {
        var released = new CompletableFuture<Void>();
        releasingThreads.put(threadId, released);
        Thread.startVirtualThread(() -> {
            try {
                client.finishConversation(threadId).get();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | RuntimeException failure) {
                log.debug("Could not unsubscribe Codex thread {} before closing the connection", threadId, failure);
            } finally {
                try {
                    client.close();
                } finally {
                    released.complete(null);
                    releasingThreads.remove(threadId, released);
                }
            }
        });
    }

    private final class CodexConversation implements Conversation {
        private final String threadId;
        private CodexClient client;
        private CompletableFuture<Void> ready;
        private volatile CodexClient resumedClient;
        private volatile boolean awaitingFirstTurn;
        private boolean closed;
        private boolean interruptRequested;
        private Consumer<ApprovalCoordinator.ApprovalPrompt> approvalListener = prompt -> {};
        private Consumer<String> resolvedListener = id -> {};

        CodexConversation(String threadId, CodexClient startedClient) {
            this.threadId = threadId;
            client = startedClient;
            if (client != null) {
                client.onApproval(approvalListener);
                client.onApprovalResolved(resolvedListener);
            }
            resumedClient = startedClient;
            awaitingFirstTurn = startedClient != null;
            ready = CompletableFuture.completedFuture(null);
        }

        @Override
        public String threadId() {
            return threadId;
        }

        @Override
        public List<ChatMessage> history(int limit) throws InterruptedException, ExecutionException {
            // Codex persists the rollout when the new thread receives its first turn.
            if (awaitingFirstTurn) {
                return List.of();
            }
            ConversationHistory history = initializedClient().getConversationHistory(
                    threadId, limit, ConversationHistory.Order.NEWEST_FIRST);
            List<ChatMessage> messages = new ArrayList<>();
            for (ConversationHistory.Message message : history.messages().reversed()) {
                messages.add(new ChatMessage(message.role().name(), message.text()));
            }
            return messages;
        }

        @Override
        public TurnResult runTurn(String text, String model, Consumer<String> onText)
                throws InterruptedException, ExecutionException {
            return runTurn(text, model, onText, null);
        }

        @Override
        public TurnResult runTurn(String text, String model, Consumer<String> onText, Consumer<String> onProgress)
                throws InterruptedException, ExecutionException {
            try {
                CompletableFuture<Void> release = releasingThreads.get(threadId);
                if (release != null) {
                    release.get();
                }
                CodexClient current = initializedClient();
                if (resumedClient != current) {
                    try {
                        current.resumeConversation(threadId).get();
                    } catch (ExecutionException error) {
                        if (error.getCause() instanceof CodexRpcException rpcError
                                && rpcError.code() == -32600
                                && rpcError.getMessage().equals("thread " + threadId + " already has an active writer")) {
                            throw new ThreadWriteConflict(rpcError);
                        }
                        throw error;
                    }
                }

                CompletableFuture<CodexTurnResult> active;
                synchronized (this) {
                    if (closed || client != current) {
                        throw new IllegalStateException("Conversation connection closed before the turn");
                    }
                    resumedClient = current;
                    CodexModel chosen = model == null ? DEFAULT_MODEL : CodexModel.fromId(model);
                    active = onProgress == null ? current.runTurn(threadId, text, chosen, onText)
                            : current.runTurn(threadId, text, chosen, onText, onProgress);
                    if (interruptRequested) {
                        current.interruptTurn(threadId);
                    }
                }

                CodexTurnResult result = active.get();
                awaitingFirstTurn = false;
                return new TurnResult(result.turnId(), result.status(), result.errorMessage());
            } finally {
                synchronized (this) {
                    interruptRequested = false;
                }
            }
        }

        private CodexClient initializedClient() throws InterruptedException, ExecutionException {
            CodexClient current;
            CompletableFuture<Void> connection;
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("Conversation is closed");
                }
                if (client == null || client.isClosed() || ready.isCompletedExceptionally()) {
                    if (client != null) {
                        client.close();
                    }
                    client = clients.get();
                    client.onApproval(approvalListener);
                    client.onApprovalResolved(resolvedListener);
                    ready = new CompletableFuture<>();
                    CodexClient connecting = client;
                    CompletableFuture<Void> completion = ready;
                    Thread.startVirtualThread(() -> initialize(connecting, completion));
                }
                current = client;
                connection = ready;
            }
            connection.get();
            return current;
        }

        @Override
        public synchronized void onApproval(Consumer<ApprovalCoordinator.ApprovalPrompt> listener) {
            approvalListener = listener;
            if (client != null) {
                client.onApproval(listener);
            }
        }

        @Override
        public synchronized void onApprovalResolved(Consumer<String> listener) {
            resolvedListener = listener;
            if (client != null) {
                client.onApprovalResolved(listener);
            }
        }

        @Override
        public synchronized boolean resolveApproval(String requestId, ApprovalCoordinator.Decision decision) {
            return client != null && client.resolveApproval(requestId, decision);
        }

        private void initialize(CodexClient connecting, CompletableFuture<Void> completion) {
            try {
                connect(connecting).get();
                completion.complete(null);
            } catch (InterruptedException | ExecutionException | RuntimeException error) {
                connecting.close();
                completion.completeExceptionally(error);
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void interrupt() throws InterruptedException, ExecutionException {
            CodexClient current;
            synchronized (this) {
                interruptRequested = true;
                current = client;
            }
            if (current != null && !current.isClosed()) {
                current.interruptTurn(threadId).get();
            }
        }

        @Override
        public void delete() throws InterruptedException, ExecutionException {
            initializedClient().deleteConversation(threadId).get();
            resumedClient = null;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (client != null && client == resumedClient && !client.isClosed()) {
                releaseThread(threadId, client);
            } else if (client != null) {
                client.close();
            }
        }
    }
}
