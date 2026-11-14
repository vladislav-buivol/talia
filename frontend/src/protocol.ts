export type Action =
  | "startNewDialog" | "continueDialog" | "getDialogs" | "getHistory" | "getModels"
  | "sendMessage" | "changeModel" | "closeChat" | "finishChat"
  | "connectAsReader" | "connectAsWriter" | "transferWriter"
  | "deleteChat" | "closeChatAndDelete" | "finishChatAndDelete";

export interface Dialog {
  threadId: string;
  name?: string;
  preview?: string;
  availableAction: Action[];
}

export interface ChatMessage {
  role: "USER" | "ASSISTANT";
  text: string;
  turnId?: string;
}

export interface ChatEvent {
  type: string;
  availableAction: Action[];
  data?: {
    threadId?: string;
    clientId?: string;
    writerId?: string | null;
    joinedClientId?: string;
    leftClientId?: string;
    turnId?: string | null;
    text?: string;
    status?: string;
    errorMessage?: string | null;
    message?: string;
    code?: string;
    model?: string;
    models?: string[];
    dialogs?: Dialog[];
    nextCursor?: string;
    messages?: ChatMessage[];
  };
}

export type ChatRequest =
  | { type: "GET"; action: "getDialogs"; limit: number; cursor?: string }
  | { type: "GET"; action: "getHistory"; limit: number }
  | { type: "GET"; action: "getModels" }
  | { type: "POST"; action: "startNewDialog"; model?: string }
  | { type: "POST"; action: "continueDialog"; threadId: string }
  | { type: "POST"; action: "connectAsReader" | "connectAsWriter"; threadId?: string }
  | { type: "POST"; action: "sendMessage"; text: string }
  | { type: "POST"; action: "changeModel"; model: string }
  | { type: "POST"; action: "transferWriter"; clientId: string }
  | { type: "POST"; action: "closeChat" | "finishChat" }
  | { type: "DELETE"; action: "deleteChat"; threadId?: string }
  | { type: "DELETE"; action: "closeChatAndDelete" | "finishChatAndDelete" };
