package com.chatbot.talia.ws.adapter.codex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Settings for one local Codex app-server connection. */
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
    public static final int DEFAULT_EARLY_NOTIFICATIONS = 128;

    public CodexSettings {
        Objects.requireNonNull(url, "url");
        positive(rpcTimeout, "rpcTimeout");
        positive(turnStartTimeout, "turnStartTimeout");
        positive(interruptTimeout, "interruptTimeout");
        positive(approvalTimeout, "approvalTimeout");
        positive(connectTimeout, "connectTimeout");
        positive(sendTimeout, "sendTimeout");
        positive(closeTimeout, "closeTimeout");

        if (maxEarlyNotifications < 1 || sendQueueCapacity < 1 || maxMessageBytes < 1) {
            throw new IllegalArgumentException("Codex buffer limits must be positive");
        }
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** Standalone factory; Spring wiring reads application-codex.properties. */
    public static CodexSettings local(URI url) {
        return new CodexSettings(
                url,
                DEFAULT_RPC_TIMEOUT,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofSeconds(90),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                DEFAULT_EARLY_NOTIFICATIONS,
                DEFAULT_SEND_QUEUE_CAPACITY,
                DEFAULT_MAX_MESSAGE_BYTES,
                Duration.ofSeconds(2));
    }
}
