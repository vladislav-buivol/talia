
package com.chatbot.talia.http.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Service
@Profile("codex")
public class SupportAssistantServiceCodexHttp {

    public String generateResponse(String message) {
        Path output = null;

        try {
            output = Files.createTempFile("codex-", ".txt");

            ProcessBuilder builder = new ProcessBuilder(
                    "cmd.exe", "/c", "codex", "exec",
                    "--sandbox", "read-only",
                    "--skip-git-repo-check",
                    "--ephemeral",
                    "-"
            );

            builder.directory(Path.of(
                    System.getProperty("java.io.tmpdir")
            ).toFile());

            builder.redirectOutput(output.toFile());
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process process = builder.start();

            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(message.getBytes(StandardCharsets.UTF_8));
            }

            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Codex timed out");
            }

            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        "Codex exited with code " + process.exitValue()
                );
            }

            return Files.readString(output).trim();

        } catch (IOException e) {
            throw new IllegalStateException("Failed to execute Codex", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Codex interrupted", e);
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // Temporary file cleanup failed.
                }
            }
        }
    }
}
