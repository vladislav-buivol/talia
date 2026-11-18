package com.chatbot.talia.ws.adapter.codex.client.approval;

import com.chatbot.talia.ws.adapter.codex.client.rpc.CodexWebSocketClient;
import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec;
import com.chatbot.talia.ws.adapter.codex.protocol.response.decoder.ServerRequestMethod;
import com.chatbot.talia.ws.adapter.codex.protocol.message.CodexServerRequestParams;
import com.chatbot.talia.ws.adapter.codex.protocol.response.ApprovalDecisionResult;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalCoordinatorTests {
    private final CodexWebSocketClient rpc = mock(CodexWebSocketClient.class);

    @Test
    void preservesOriginalNumericIdAndRejectsDuplicateDecision() {
        CodexMessageCodec.ServerRequest request = request(new CodexMessageCodec.NumericRequestId(17L),
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
        verify(rpc, times(1)).sendServerResult(request.id(), new ApprovalDecisionResult("accept"));
        coordinator.close();
    }

    @Test
    void stringRequestIdDeclinesWithoutListener() {
        CodexMessageCodec.ServerRequest request = request(new CodexMessageCodec.StringRequestId("abc"),
                ServerRequestMethod.ITEM_FILE_CHANGE_REQUEST_APPROVAL,
                "item/fileChange/requestApproval");
        when(rpc.sendServerResult(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        ApprovalCoordinator coordinator = new ApprovalCoordinator(rpc, Duration.ofSeconds(10));
        coordinator.receive(request);
        verify(rpc).sendServerResult(request.id(), new ApprovalDecisionResult("decline"));
        coordinator.close();
    }

    @Test
    void unsupportedRequestsFailClosed() {
        CodexMessageCodec.ServerRequest request = request(new CodexMessageCodec.NumericRequestId(32),
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
        CodexServerRequestParams params = new CodexServerRequestParams(
                "thr", "turn", null, "testing", "mvn test", null);
        return new CodexMessageCodec.ServerRequest(id, method, wire, params);
    }
}
