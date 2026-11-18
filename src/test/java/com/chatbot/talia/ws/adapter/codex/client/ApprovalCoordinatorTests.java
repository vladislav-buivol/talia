package com.chatbot.talia.ws.adapter.codex.client;

import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.adapter.codex.protocol.response.decoder.ServerRequestMethod;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalCoordinatorTests {
    private final CodexWebSocketClient rpc = mock(CodexWebSocketClient.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesOriginalNumericIdAndRejectsDuplicateDecision() {
        var request = request(new CodexMessageCodec.NumericRequestId(17L),
                ServerRequestMethod.ITEM_COMMAND_EXECUTION_REQUEST_APPROVAL,
                "item/commandExecution/requestApproval");
        when(rpc.sendServerResult(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        ApprovalCoordinator coordinator = new ApprovalCoordinator(rpc, Duration.ofSeconds(10));
        AtomicReference<ApprovalCoordinator.ApprovalPrompt> prompt = new AtomicReference<>();
        coordinator.setListener(prompt::set);
        coordinator.receive(request);
        assertEquals("n:17", prompt.get().requestId());
        assertTrue(coordinator.resolve(prompt.get().requestId(), ApprovalCoordinator.Decision.ACCEPT));
        assertFalse(coordinator.resolve(prompt.get().requestId(), ApprovalCoordinator.Decision.DECLINE));
        verify(rpc, times(1)).sendServerResult(request.id(), Map.of("decision", "accept"));
        coordinator.close();
    }

    @Test
    void stringRequestIdDeclinesWithoutListener() {
        var request = request(new CodexMessageCodec.StringRequestId("abc"),
                ServerRequestMethod.ITEM_FILE_CHANGE_REQUEST_APPROVAL,
                "item/fileChange/requestApproval");
        when(rpc.sendServerResult(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        ApprovalCoordinator coordinator = new ApprovalCoordinator(rpc, Duration.ofSeconds(10));
        coordinator.receive(request);
        verify(rpc).sendServerResult(request.id(), Map.of("decision", "decline"));
        coordinator.close();
    }

    @Test
    void unsupportedRequestsFailClosed() {
        var request = request(new CodexMessageCodec.NumericRequestId(32),
                ServerRequestMethod.UNKNOWN, "item/tool/call");
        when(rpc.sendServerError(any(), anyLong(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        ApprovalCoordinator coordinator = new ApprovalCoordinator(rpc, Duration.ofSeconds(10));
        coordinator.receive(request);
        verify(rpc).sendServerError(request.id(), -32601L, "Unsupported server request: item/tool/call");
        coordinator.close();
    }

    private CodexMessageCodec.ServerRequest request(CodexMessageCodec.RequestId id,
            ServerRequestMethod method, String wire) {
        var params = mapper.readTree("""
                {"threadId":"thr","turnId":"turn","reason":"testing","command":"mvn test"}
                """);
        return new CodexMessageCodec.ServerRequest(id, method, wire, params, params);
    }
}
