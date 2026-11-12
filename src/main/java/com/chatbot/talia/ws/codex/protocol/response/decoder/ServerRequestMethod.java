package com.chatbot.talia.ws.codex.protocol.response.decoder;

public enum ServerRequestMethod {

    ITEM_COMMAND_EXECUTION_REQUEST_APPROVAL("item/commandExecution/requestApproval"),
    ITEM_FILE_CHANGE_REQUEST_APPROVAL("item/fileChange/requestApproval"),
    ITEM_TOOL_REQUEST_USER_INPUT("item/tool/requestUserInput"),
    MCP_SERVER_ELICITATION_REQUEST("mcpServer/elicitation/request"),
    ITEM_PERMISSIONS_REQUEST_APPROVAL("item/permissions/requestApproval"),
    ITEM_TOOL_CALL("item/tool/call"),
    ACCOUNT_CHATGPT_AUTH_TOKENS_REFRESH("account/chatgptAuthTokens/refresh"),
    ATTESTATION_GENERATE("attestation/generate"),
    CURRENT_TIME_READ("currentTime/read"),
    APPLY_PATCH_APPROVAL("applyPatchApproval"),
    EXEC_COMMAND_APPROVAL("execCommandApproval"),

    UNKNOWN(null);

    private final String wireName;

    ServerRequestMethod(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ServerRequestMethod fromWireName(String wireName) {
        for (ServerRequestMethod method : values()) {
            if (method.wireName != null && method.wireName.equals(wireName)) {
                return method;
            }
        }

        return UNKNOWN;
    }
}