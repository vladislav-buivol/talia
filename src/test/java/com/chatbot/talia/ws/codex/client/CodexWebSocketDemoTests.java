package com.chatbot.talia.ws.codex.client;

import com.chatbot.talia.ws.codex.protocol.request.CodexModel;
import com.chatbot.talia.ws.codex.protocol.request.ThreadListRequest;
import com.chatbot.talia.ws.codex.protocol.request.ThreadResumeRequest;
import com.chatbot.talia.ws.codex.protocol.request.ThreadStartRequest;
import com.chatbot.talia.ws.codex.protocol.request.ThreadTurnsListRequest;
import com.chatbot.talia.ws.codex.protocol.response.ThreadListResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadResumeResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadStartResponse;
import com.chatbot.talia.ws.codex.protocol.response.ThreadTurnsListResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.ignoreStubs;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CodexWebSocketDemoTests {
    private final CodexWebSocketClient rpc = mock(CodexWebSocketClient.class);
    private final CodexClient client = new CodexClient(rpc);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void writesRecentHistoryRequestsInCodexWireFormat() {
        JsonNode list = mapper.valueToTree(ThreadListRequest.create(10).withId(1L));
        assertEquals("thread/list", list.path("method").asString());
        assertEquals(10, list.path("params").path("limit").asInt());
        assertEquals("updated_at", list.path("params").path("sortKey").asString());
        assertEquals("desc", list.path("params").path("sortDirection").asString());

        JsonNode turns = mapper.valueToTree(ThreadTurnsListRequest.create("match", "cursor").withId(2L));
        assertEquals("thread/turns/list", turns.path("method").asString());
        assertEquals("asc", turns.path("params").path("sortDirection").asString());
        assertEquals("full", turns.path("params").path("itemsView").asString());
        assertEquals("cursor", turns.path("params").path("cursor").asString());

        JsonNode resume = mapper.valueToTree(ThreadResumeRequest.create("match").withId(3L));
        assertEquals("thread/resume", resume.path("method").asString());
        assertEquals("match", resume.path("params").path("threadId").asString());
        assertTrue(resume.path("params").path("excludeTurns").asBoolean());
    }

    @Test
    void resumesTheNewestExactFirstMessageMatch() throws Exception {
        ThreadListResponse recent = mapper.readValue("""
                {"id":1,"result":{"data":[{"id":"newest"},{"id":"with-image"},{"id":"match"},{"id":"older"}],"nextCursor":"next-page"}}
                """, ThreadListResponse.class);
        when(rpc.send(ThreadListRequest.create(10), ThreadListResponse.class))
                .thenReturn(CompletableFuture.completedFuture(recent));
        stubTurns("newest", null, """
                {"id":2,"result":{"data":[{"items":[
                  {"type":"userMessage","content":[{"type":"text","text":"hello from intellij idea again"}]},
                  {"type":"userMessage","content":[{"type":"text","text":"hello from intellij idea"}]}
                ]}],"nextCursor":null}}
                """);
        stubTurns("with-image", null, """
                {"id":2,"result":{"data":[{"items":[
                  {"type":"userMessage","content":[
                    {"type":"text","text":"hello from intellij idea"},
                    {"type":"localImage","path":"image.png"}
                  ]}
                ]}],"nextCursor":null}}
                """);
        stubTurns("match", null, """
                {"id":3,"result":{"data":[{"items":[]}],"nextCursor":"next-turn"}}
                """);
        stubTurns("match", "next-turn", """
                {"id":4,"result":{"data":[{"items":[
                  {"type":"reasoning","content":["reasoning text"]},
                  {"type":"userMessage","content":[{"type":"text","text":"hello from intellij idea"}]}
                ]}],"nextCursor":null}}
                """);
        ThreadResumeResponse resumed = mapper.readValue("""
                {"id":5,"result":{"thread":{"id":"match"}}}
                """, ThreadResumeResponse.class);
        when(rpc.send(ThreadResumeRequest.create("match"), ThreadResumeResponse.class))
                .thenReturn(CompletableFuture.completedFuture(resumed));

        assertEquals("match", CodexWebSocketDemo.openConversation(client));
        verifyNoMoreInteractions(ignoreStubs(rpc));
    }

    @Test
    void startsAConversationWhenTheRecentPageHasNoMatch() throws Exception {
        ThreadListResponse recent = mapper.readValue("""
                {"id":1,"result":{"data":[{"id":"different"}],"nextCursor":"next-page"}}
                """, ThreadListResponse.class);
        when(rpc.send(ThreadListRequest.create(10), ThreadListResponse.class))
                .thenReturn(CompletableFuture.completedFuture(recent));
        stubTurns("different", null, """
                {"id":2,"result":{"data":[{"items":[
                  {"type":"userMessage","content":[{"type":"text","text":"Different opening"}]}
                ]}],"nextCursor":null}}
                """);
        ThreadStartResponse started = mapper.readValue("""
                {"id":3,"result":{"thread":{"id":"new"}}}
                """, ThreadStartResponse.class);
        when(rpc.send(ThreadStartRequest.create(CodexModel.GPT_5_6_LUNA), ThreadStartResponse.class))
                .thenReturn(CompletableFuture.completedFuture(started));

        assertEquals("new", CodexWebSocketDemo.openConversation(client));
        verifyNoMoreInteractions(ignoreStubs(rpc));
    }

    private void stubTurns(String threadId, String cursor, String json) {
        ThreadTurnsListResponse turns = mapper.readValue(json, ThreadTurnsListResponse.class);
        when(rpc.send(ThreadTurnsListRequest.create(threadId, cursor), ThreadTurnsListResponse.class))
                .thenReturn(CompletableFuture.completedFuture(turns));
    }
}
