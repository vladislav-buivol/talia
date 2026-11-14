package com.chatbot.talia.ws.core.websocket;

import com.chatbot.talia.ws.core.ChatService;
import com.chatbot.talia.ws.core.protocol.ChatRequest;
import com.chatbot.talia.ws.core.protocol.ChatResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

public final class ChatWebSocketHandler extends TextWebSocketHandler implements AutoCloseable {
    private final ObjectMapper mapper;
    private final ChatService chats;
    private final ConcurrentHashMap<String, SocketSender> senders = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ObjectMapper mapper, ChatService chats) {
        this.mapper = mapper;
        this.chats = chats;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        SocketSender sender = new SocketSender(socket);
        senders.put(socket.getId(), sender);
        chats.connect(socket.getId(), sender::offer);
        sender.thread.start();
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        ChatRequest.Command request;
        try {
            request = mapper.readValue(message.getPayload(), ChatRequest.Command.class);
        } catch (JacksonException error) {
            chats.invalidRequest(socket.getId());
            return;
        }
        chats.handle(socket.getId(), request);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        disconnect(socket.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable error) throws IOException {
        disconnect(socket.getId());
        if (socket.isOpen()) {
            socket.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void close() {
        for (SocketSender sender : senders.values()) {
            sender.stop();
        }
        chats.close();
    }

    private void disconnect(String clientId) {
        SocketSender sender = senders.remove(clientId);
        if (sender != null) {
            sender.stop();
        }
        chats.disconnect(clientId);
    }

    /** Each socket has one sender so a slow reader cannot block the stream. */
    private final class SocketSender {
        private final WebSocketSession socket;
        private final ArrayBlockingQueue<ChatResponse> outgoing = new ArrayBlockingQueue<>(256);
        private final Thread thread = Thread.ofVirtual().unstarted(this::send);

        SocketSender(WebSocketSession socket) {
            this.socket = socket;
        }

        void offer(ChatResponse response) {
            if (!outgoing.offer(response)) {
                stop();
            }
        }

        void stop() {
            if (Thread.currentThread() != thread) {
                thread.interrupt();
            }
        }

        private void send() {
            try {
                while (socket.isOpen()) {
                    socket.sendMessage(new TextMessage(mapper.writeValueAsString(outgoing.take())));
                }
            } catch (InterruptedException | IOException | RuntimeException error) {
                // Disconnect cleanup below removes the sender and chat membership.
            } finally {
                disconnect(socket.getId());
                try {
                    if (socket.isOpen()) {
                        socket.close(CloseStatus.GOING_AWAY);
                    }
                } catch (IOException ignored) {
                    // The client has already been removed.
                }
            }
        }
    }
}
