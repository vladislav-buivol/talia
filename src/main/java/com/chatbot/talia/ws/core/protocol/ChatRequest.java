package com.chatbot.talia.ws.core.protocol;

import com.chatbot.talia.ws.core.protocol.request.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StartNewDialogRequest.class, name = "startNewDialog"),
        @JsonSubTypes.Type(value = ContinueDialogRequest.class, name = "continueDialog"),
        @JsonSubTypes.Type(value = GetDialogsRequest.class, name = "getDialogs"),
        @JsonSubTypes.Type(value = GetHistoryRequest.class, name = "getHistory"),
        @JsonSubTypes.Type(value = SendMessageRequest.class, name = "sendMessage"),
        @JsonSubTypes.Type(value = ChangeModelRequest.class, name = "changeModel"),
        @JsonSubTypes.Type(value = CloseChatRequest.class, name = "closeChat"),
        @JsonSubTypes.Type(value = FinishChatRequest.class, name = "finishChat"),
        @JsonSubTypes.Type(value = CloseChatAndDeleteRequest.class, name = "closeChatAndDelete"),
        @JsonSubTypes.Type(value = FinishChatAndDeleteRequest.class, name = "finishChatAndDelete"),
        @JsonSubTypes.Type(value = DeleteChatRequest.class, name = "deleteChat"),
        @JsonSubTypes.Type(value = ConnectAsReaderRequest.class, name = "connectAsReader"),
        @JsonSubTypes.Type(value = ConnectAsWriterRequest.class, name = "connectAsWriter"),
        @JsonSubTypes.Type(value = TransferWriterRequest.class, name = "transferWriter")
})
public interface ChatRequest {
    @JsonProperty(value = "type", access = JsonProperty.Access.READ_ONLY)
    RequestType type();
}
