package com.chatbot.talia.ws.core.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChatRequest.StartNewDialogRequest.class, name = "startNewDialog"),
        @JsonSubTypes.Type(value = ChatRequest.ContinueDialogRequest.class, name = "continueDialog"),
        @JsonSubTypes.Type(value = ChatRequest.GetDialogsRequest.class, name = "getDialogs"),
        @JsonSubTypes.Type(value = ChatRequest.GetHistoryRequest.class, name = "getHistory"),
        @JsonSubTypes.Type(value = ChatRequest.SendMessageRequest.class, name = "sendMessage"),
        @JsonSubTypes.Type(value = ChatRequest.ChangeModelRequest.class, name = "changeModel"),
        @JsonSubTypes.Type(value = ChatRequest.CloseChatRequest.class, name = "closeChat"),
        @JsonSubTypes.Type(value = ChatRequest.FinishChatRequest.class, name = "finishChat"),
        @JsonSubTypes.Type(value = ChatRequest.CloseChatAndDeleteRequest.class, name = "closeChatAndDelete"),
        @JsonSubTypes.Type(value = ChatRequest.FinishChatAndDeleteRequest.class, name = "finishChatAndDelete"),
        @JsonSubTypes.Type(value = ChatRequest.DeleteChatRequest.class, name = "deleteChat"),
        @JsonSubTypes.Type(value = ChatRequest.ConnectAsReaderRequest.class, name = "connectAsReader"),
        @JsonSubTypes.Type(value = ChatRequest.ConnectAsWriterRequest.class, name = "connectAsWriter"),
        @JsonSubTypes.Type(value = ChatRequest.TransferWriterRequest.class, name = "transferWriter")
})
public sealed interface ChatRequest {
    @JsonProperty(value = "type", access = JsonProperty.Access.READ_ONLY)
    Type type();

    enum Type {
        GET, POST, DELETE
    }

    sealed interface ChatActionGetRequest extends ChatRequest {
        @Override
        default Type type() {
            return Type.GET;
        }
    }

    sealed interface ChatActionPostRequest extends ChatRequest {
        @Override
        default Type type() {
            return Type.POST;
        }
    }

    sealed interface ChatActionDeleteRequest extends ChatRequest {
        @Override
        default Type type() {
            return Type.DELETE;
        }
    }

    record StartNewDialogRequest(String model) implements ChatActionPostRequest {
    }

    record ContinueDialogRequest(String threadId) implements ChatActionPostRequest {
    }

    record GetDialogsRequest(Integer limit, String cursor) implements ChatActionGetRequest {
        public GetDialogsRequest(int limit) {
            this(limit, null);
        }
    }

    record GetHistoryRequest(Integer limit) implements ChatActionGetRequest {
    }

    record SendMessageRequest(String text) implements ChatActionPostRequest {
    }

    record ChangeModelRequest(String model) implements ChatActionPostRequest {
    }

    record CloseChatRequest() implements ChatActionPostRequest {
    }

    record FinishChatRequest() implements ChatActionPostRequest {
    }

    record CloseChatAndDeleteRequest() implements ChatActionDeleteRequest {
    }

    record FinishChatAndDeleteRequest() implements ChatActionDeleteRequest {
    }

    record DeleteChatRequest(String threadId) implements ChatActionDeleteRequest {
    }

    record ConnectAsReaderRequest(String threadId) implements ChatActionPostRequest {
        public ConnectAsReaderRequest() {
            this(null);
        }
    }

    record ConnectAsWriterRequest(String threadId) implements ChatActionPostRequest {
        public ConnectAsWriterRequest() {
            this(null);
        }
    }

    record TransferWriterRequest(String clientId) implements ChatActionPostRequest {
    }
}
