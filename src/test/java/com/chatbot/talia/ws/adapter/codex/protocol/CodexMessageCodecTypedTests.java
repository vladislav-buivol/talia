package com.chatbot.talia.ws.adapter.codex.protocol;

import com.chatbot.talia.ws.adapter.codex.protocol.response.JsonRpcErrorResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.ThreadTurnsListResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.TurnInterruptResponse;
import com.chatbot.talia.ws.adapter.codex.protocol.response.decoder.NotificationMethod;
import com.chatbot.talia.ws.adapter.codex.protocol.response.decoder.ServerRequestMethod;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CodexMessageCodecTypedTests {
    private final CodexMessageCodec codec = new CodexMessageCodec(new ObjectMapper());

    @Test
    void decodesTurnNotificationsIntoTypedRecords() {
        CodexMessageCodec.Notification notification = assertInstanceOf(
                CodexMessageCodec.Notification.class,
                codec.decode("""
                        {"method":"turn/completed","params":{
                          "threadId":"thread-1",
                          "turn":{"id":"turn-1","status":"interrupted","error":{"message":"stopped"}}
                        }}
                        """, id -> null));

        assertEquals(NotificationMethod.TURN_COMPLETED, notification.method());
        assertEquals("thread-1", notification.params().threadId());
        assertEquals("turn-1", notification.params().turn().id());
        assertEquals("interrupted", notification.params().turn().status());
        assertEquals("stopped", notification.params().turn().error().message());
    }

    @Test
    void decodesServerRequestsWithoutRawJsonFields() {
        CodexMessageCodec.ServerRequest request = assertInstanceOf(
                CodexMessageCodec.ServerRequest.class,
                codec.decode("""
                        {"id":"approval-5","method":"item/commandExecution/requestApproval",
                         "params":{"threadId":"thread-1","turnId":"turn-1",
                                   "command":"mvn test","reason":"Run tests"}}
                        """, id -> null));

        assertInstanceOf(CodexMessageCodec.StringRequestId.class, request.id());
        assertEquals(ServerRequestMethod.ITEM_COMMAND_EXECUTION_REQUEST_APPROVAL, request.method());
        assertEquals("mvn test", request.params().command());
        assertEquals("Run tests", request.params().reason());
    }

    @Test
    void preservesUnknownErrorDataAsOpaqueJson() {
        JsonRpcErrorResponse response = assertInstanceOf(
                JsonRpcErrorResponse.class,
                codec.decode("""
                        {"id":1,"error":{"code":-32000,"message":"failure",
                          "data":{"retryable":false,"detail":{"source":"codex"}}}}
                        """, id -> null));

        assertEquals(-32000, response.getError().code());
        assertNotNull(response.getError().data());
        assertEquals("{\"retryable\":false,\"detail\":{\"source\":\"codex\"}}",
                response.getError().data().json());
    }

    @Test
    void decodesServerResolvedNotificationWithOriginalStringId() {
        CodexMessageCodec.Notification notification = assertInstanceOf(
                CodexMessageCodec.Notification.class,
                codec.decode("""
                        {"method":"serverRequest/resolved","params":{"requestId":"approval-7"}}
                        """, id -> null));

        CodexMessageCodec.StringRequestId requestId = assertInstanceOf(
                CodexMessageCodec.StringRequestId.class,
                notification.params().resolvedRequestId());
        assertEquals("approval-7", requestId.value());
    }

    @Test
    void decodesEmptyInterruptResultAsTypedValue() {
        TurnInterruptResponse response = assertInstanceOf(
                TurnInterruptResponse.class,
                codec.decode("""
                        {"id":14,"result":{}}
                        """, id -> TurnInterruptResponse.class));

        assertEquals(14L, response.id());
        assertNotNull(response.result());
    }

    @Test
    void readsMixedContentWithoutLeakingJacksonNodes() {
        ThreadTurnsListResponse result = codec.readThreadTurnsListResponse("""
                {"id":3,"result":{"data":[{"items":[
                  {"type":"reasoning","content":["thinking"]},
                  {"type":"userMessage","content":[
                    {"type":"text","text":"Hello"},
                    {"type":"localImage","path":"image.png"}
                  ]}
                ]}],"nextCursor":null}}
                """);

        assertEquals(2, result.result().data().getFirst().items().size());
        ThreadTurnsListResponse.Item userMessage = result.result().data().getFirst().items().get(1);
        assertEquals("userMessage", userMessage.type());
        assertEquals("Hello", userMessage.content().getFirst().text());
        assertEquals("image.png", userMessage.content().get(1).path());
    }
}
