package com.chatbot.talia.ws.adapter.codex;

import com.chatbot.talia.ws.adapter.codex.client.CodexClient;
import com.chatbot.talia.ws.adapter.codex.client.CodexTurnResult;
import com.chatbot.talia.ws.adapter.codex.client.ConversationHistory;
import com.chatbot.talia.ws.adapter.codex.protocol.request.CodexModel;
import com.chatbot.talia.ws.adapter.codex.protocol.request.InitializeRequest.ClientInfo;
import com.chatbot.talia.ws.core.ChatBackend;
import com.chatbot.talia.ws.core.protocol.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class CodexChatBackend implements ChatBackend {
    private final Supplier<CodexClient> clients;
    private final ClientInfo clientInfo;

    public CodexChatBackend(Supplier<CodexClient> clients, ClientInfo clientInfo) {
        this.clients = clients;
        this.clientInfo = clientInfo;
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
            String threadId = client.startConversation(CodexModel.fromId(model)).get();
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

    private final class CodexConversation implements Conversation {
        private final String threadId;
        private CodexClient client;
        private CompletableFuture<Void> ready;
        private CodexClient resumedClient;
        private boolean closed;

        CodexConversation(String threadId, CodexClient startedClient) {
            this.threadId = threadId;
            client = startedClient;
            resumedClient = startedClient;
            ready = CompletableFuture.completedFuture(null);
        }

        @Override
        public String threadId() {
            return threadId;
        }

        @Override
        public List<ChatResponse.Message> history(int limit) throws InterruptedException, ExecutionException {
            ConversationHistory history = initializedClient().getConversationHistory(
                    threadId, limit, ConversationHistory.Order.NEWEST_FIRST);
            List<ChatResponse.Message> messages = new ArrayList<>();
            for (ConversationHistory.Message message : history.messages().reversed()) {
                messages.add(new ChatResponse.Message(message.role().name(), message.text()));
            }
            return messages;
        }

        @Override
        public TurnResult runTurn(String text, String model, Consumer<String> onText)
                throws InterruptedException, ExecutionException {
            CodexClient current = initializedClient();
            if (resumedClient != current) {
                current.resumeConversation(threadId).get();
                resumedClient = current;
            }
            CodexTurnResult result = model == null
                    ? current.runTurn(threadId, text, onText).get()
                    : current.runTurn(threadId, text, CodexModel.fromId(model), onText).get();
            return new TurnResult(result.turnId(), result.status(), result.errorMessage());
        }

        private CodexClient initializedClient() throws InterruptedException, ExecutionException {
            CodexClient current;
            CompletableFuture<Void> connection;
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("Conversation is closed");
                }
                if (client == null || client.isClosed() || ready.isCompletedExceptionally()) {
                    client = clients.get();
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
        public void delete() throws InterruptedException, ExecutionException {
            initializedClient().deleteConversation(threadId).get();
        }

        @Override
        public synchronized void close() {
            closed = true;
            if (client != null) {
                client.close();
            }
        }
    }
}
