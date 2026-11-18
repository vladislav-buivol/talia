package com.chatbot.talia.ws.core.protocol;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ChatAction {
    START_NEW_DIALOG("startNewDialog"),
    CONTINUE_DIALOG("continueDialog"),
    GET_DIALOGS("getDialogs"),
    GET_HISTORY("getHistory"),
    GET_MODELS("getModels"),
    SEND_MESSAGE("sendMessage"),
    CANCEL_TURN("cancelTurn"),
    RESOLVE_APPROVAL("resolveApproval"),
    CHANGE_MODEL("changeModel"),
    CLOSE_CHAT("closeChat"),
    FINISH_CHAT("finishChat"),
    CLOSE_CHAT_AND_DELETE("closeChatAndDelete"),
    FINISH_CHAT_AND_DELETE("finishChatAndDelete"),
    DELETE_CHAT("deleteChat"),
    CONNECT_AS_READER("connectAsReader"),
    CONNECT_AS_WRITER("connectAsWriter"),
    TRANSFER_WRITER("transferWriter");

    private final String value;

    ChatAction(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
