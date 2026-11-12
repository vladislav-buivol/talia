package com.chatbot.talia.ws.codex.protocol.response.decoder;

public enum NotificationMethod {

    ERROR("error"),
    THREAD_STARTED("thread/started"),
    THREAD_STATUS_CHANGED("thread/status/changed"),
    THREAD_READ_STATE_CHANGED("thread/readState/changed"),
    THREAD_ARCHIVED("thread/archived"),
    THREAD_DELETED("thread/deleted"),
    THREAD_UNARCHIVED("thread/unarchived"),
    THREAD_CLOSED("thread/closed"),
    THREAD_REVERTED("thread/reverted"),
    SKILLS_CHANGED("skills/changed"),
    THREAD_NAME_UPDATED("thread/name/updated"),
    THREAD_ATTACHMENT_UPDATED("thread/attachment/updated"),
    THREAD_GOAL_UPDATED("thread/goal/updated"),
    THREAD_PREDICTION_UPDATED("thread/prediction/updated"),
    THREAD_GOAL_CLEARED("thread/goal/cleared"),
    THREAD_QUEUE_CHANGED("thread/queue/changed"),
    PROJECT_CHANGED("project/changed"),
    THREAD_PROJECT_UPDATED("thread/project/updated"),
    THREAD_ENVIRONMENT_CONNECTED("thread/environment/connected"),
    THREAD_ENVIRONMENT_DISCONNECTED("thread/environment/disconnected"),
    THREAD_SETTINGS_UPDATED("thread/settings/updated"),
    THREAD_TOKEN_USAGE_UPDATED("thread/tokenUsage/updated"),

    TURN_STARTED("turn/started"),
    TURN_COMPLETED("turn/completed"),
    TURN_DIFF_UPDATED("turn/diff/updated"),
    TURN_PLAN_UPDATED("turn/plan/updated"),
    TURN_MODERATION_METADATA("turn/moderationMetadata"),

    HOOK_STARTED("hook/started"),
    HOOK_COMPLETED("hook/completed"),

    ITEM_STARTED("item/started"),
    ITEM_COMPLETED("item/completed"),
    ITEM_AUTO_APPROVAL_REVIEW_STARTED("item/autoApprovalReview/started"),
    ITEM_AUTO_APPROVAL_REVIEW_COMPLETED("item/autoApprovalReview/completed"),
    AUTO_APPROVAL_REVIEW_STRICT_REVIEW_REQUIRED(
            "autoApprovalReview/strictReviewRequired"
    ),
    ITEM_AGENT_MESSAGE_DELTA("item/agentMessage/delta"),
    ITEM_PLAN_DELTA("item/plan/delta"),
    ITEM_COMMAND_EXECUTION_OUTPUT_DELTA("item/commandExecution/outputDelta"),
    ITEM_COMMAND_EXECUTION_TERMINAL_INTERACTION(
            "item/commandExecution/terminalInteraction"
    ),
    ITEM_FILE_CHANGE_OUTPUT_DELTA("item/fileChange/outputDelta"),
    ITEM_FILE_CHANGE_PATCH_UPDATED("item/fileChange/patchUpdated"),
    ITEM_MCP_TOOL_CALL_PROGRESS("item/mcpToolCall/progress"),
    ITEM_REASONING_SUMMARY_TEXT_DELTA("item/reasoning/summaryTextDelta"),
    ITEM_REASONING_SUMMARY_PART_ADDED("item/reasoning/summaryPartAdded"),
    ITEM_REASONING_TEXT_DELTA("item/reasoning/textDelta"),

    COMMAND_EXEC_OUTPUT_DELTA("command/exec/outputDelta"),
    PROCESS_OUTPUT_DELTA("process/outputDelta"),
    PROCESS_EXITED("process/exited"),
    SERVER_REQUEST_RESOLVED("serverRequest/resolved"),

    MCP_SERVER_OAUTH_LOGIN_COMPLETED("mcpServer/oauthLogin/completed"),
    MCP_SERVER_STARTUP_STATUS_UPDATED("mcpServer/startupStatus/updated"),
    MCP_SERVER_EVENT_STREAM_NOTIFICATION("mcpServer/event/stream/notification"),

    ACCOUNT_UPDATED("account/updated"),
    ACCOUNT_GATEWAY_OAUTH_CHANGED("account/gatewayOAuth/changed"),
    ACCOUNT_RATE_LIMITS_UPDATED("account/rateLimits/updated"),
    ACCOUNT_LOGIN_COMPLETED("account/login/completed"),

    APP_LIST_UPDATED("app/list/updated"),
    REMOTE_CONTROL_STATUS_CHANGED("remoteControl/status/changed"),
    EXTERNAL_AGENT_CONFIG_IMPORT_PROGRESS(
            "externalAgentConfig/import/progress"
    ),
    EXTERNAL_AGENT_CONFIG_IMPORT_COMPLETED(
            "externalAgentConfig/import/completed"
    ),
    FS_CHANGED("fs/changed"),

    THREAD_COMPACTED("thread/compacted"),
    MODEL_REROUTED("model/rerouted"),
    MODEL_VERIFICATION("model/verification"),
    MODEL_PROVIDER_AUTH_RECOVERY_STARTED("modelProvider/authRecoveryStarted"),
    MODEL_PROVIDER_AUTH_RECOVERY_COMPLETED("modelProvider/authRecoveryCompleted"),
    MODEL_SAFETY_BUFFERING_UPDATED("model/safetyBuffering/updated"),

    WARNING("warning"),
    GUARDIAN_WARNING("guardianWarning"),
    DEPRECATION_NOTICE("deprecationNotice"),
    CONFIG_WARNING("configWarning"),

    FUZZY_FILE_SEARCH_SESSION_UPDATED("fuzzyFileSearch/sessionUpdated"),
    FUZZY_FILE_SEARCH_SESSION_COMPLETED("fuzzyFileSearch/sessionCompleted"),

    THREAD_REALTIME_STARTED("thread/realtime/started"),
    THREAD_REALTIME_ITEM_ADDED("thread/realtime/itemAdded"),
    THREAD_REALTIME_ITEM_STARTED("thread/realtime/item/started"),
    THREAD_REALTIME_ITEM_TRANSCRIPT_DELTA("thread/realtime/item/transcript/delta"),
    THREAD_REALTIME_ITEM_COMPLETED("thread/realtime/item/completed"),
    THREAD_REALTIME_TRANSCRIPT_DELTA("thread/realtime/transcript/delta"),
    THREAD_REALTIME_TRANSCRIPT_DONE("thread/realtime/transcript/done"),
    THREAD_REALTIME_OUTPUT_AUDIO_DELTA("thread/realtime/outputAudio/delta"),
    THREAD_REALTIME_SDP("thread/realtime/sdp"),
    THREAD_REALTIME_ERROR("thread/realtime/error"),
    THREAD_REALTIME_CLOSED("thread/realtime/closed"),

    WINDOWS_WORLD_WRITABLE_WARNING("windows/worldWritableWarning"),
    WINDOWS_SANDBOX_SETUP_COMPLETED("windowsSandbox/setupCompleted"),

    UNKNOWN(null);

    private final String wireName;

    NotificationMethod(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static NotificationMethod fromWireName(String wireName) {
        for (NotificationMethod method : values()) {
            if (method.wireName != null && method.wireName.equals(wireName)) {
                return method;
            }
        }

        return UNKNOWN;
    }
}