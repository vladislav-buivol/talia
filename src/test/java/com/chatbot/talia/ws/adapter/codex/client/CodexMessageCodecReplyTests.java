package com.chatbot.talia.ws.adapter.codex.client;

import com.chatbot.talia.ws.adapter.codex.protocol.CodexMessageCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CodexMessageCodecReplyTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodexMessageCodec codec = new CodexMessageCodec(mapper);

    @Test
    void encodesNumericAndStringIdsAsJsonRpcScalars() {
        JsonNode numeric = mapper.readTree(codec.encodeServerResult(
                new CodexMessageCodec.NumericRequestId(5), Map.of("decision","accept")));
        assertEquals(5, numeric.path("id").asInt());
        assertEquals("accept", numeric.path("result").path("decision").asString());

        JsonNode text = mapper.readTree(codec.encodeServerResult(
                new CodexMessageCodec.StringRequestId("approval-1"), Map.of("decision","decline")));
        assertEquals("approval-1", text.path("id").asString());
    }

    @Test
    void serializesErrorCodeWithoutInventingRequestId() {
        JsonNode reply = mapper.readTree(codec.encodeServerError(
                new CodexMessageCodec.StringRequestId("cmd"),
                -32601, "Unsupported"));
        assertEquals("cmd", reply.path("id").asString());
        assertEquals(-32601, reply.path("error").path("code").asInt());
    }
}
