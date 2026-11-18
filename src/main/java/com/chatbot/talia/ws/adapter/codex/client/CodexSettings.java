package com.chatbot.talia.ws.adapter.codex.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Configuration for a local Codex app-server connection, not wire protocol constants. */
@ConfigurationProperties(prefix = "talia.codex")
public record CodexSettings(
        URI url,
        Duration rpcTimeout,
        Duration turnStartTimeout,
        Duration interruptTimeout,
        Duration approvalTimeout,
        Duration connectTimeout,
        Duration sendTimeout,
        int maxEarlyNotifications,
        int sendQueueCapacity,
        int maxMessageBytes,
        Duration closeTimeout) {
    public static final Duration DEFAULT_RPC_TIMEOUT = Duration.ofSeconds(15);
    public static final int DEFAULT_SEND_QUEUE_CAPACITY = 64;
    public static final int DEFAULT_MAX_MESSAGE_BYTES = 2_097_152;

    public CodexSettings {
        Objects.requireNonNull(url, "url");
        positive(rpcTimeout, "rpcTimeout");
        positive(turnStartTimeout, "turnStartTimeout");
        positive(interruptTimeout, "interruptTimeout");
        positive(approvalTimeout, "approvalTimeout");
        positive(connectTimeout, "connectTimeout");
        positive(sendTimeout, "sendTimeout");
        if (maxEarlyNotifications < 1 || sendQueueCapacity < 1 || maxMessageBytes < 1)
            throw new IllegalArgumentException("Codex buffer limits must be positive");
        positive(closeTimeout, "closeTimeout");
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** Backward-compatible standalone factory; application wiring uses application-codex.properties. */
    public static CodexSettings local(URI url) {
        return new CodexSettings(url, DEFAULT_RPC_TIMEOUT, Duration.ofSeconds(30),
                Duration.ofSeconds(5), Duration.ofSeconds(90),
                Duration.ofSeconds(5), Duration.ofSeconds(10), 128,
                DEFAULT_SEND_QUEUE_CAPACITY, DEFAULT_MAX_MESSAGE_BYTES, Duration.ofSeconds(2));
    }
}
