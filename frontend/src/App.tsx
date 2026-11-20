import { useEffect, useRef, useState } from "react";
import AvatarPanel from "./avatar/AvatarPanel";
import type { Action, ApprovalPrompt, ChatEvent, ChatMessage, ChatRequest, Dialog } from "./protocol";
import { useReplyVoice } from "./voice/useReplyVoice";

const labels: Record<Action, string> = {
  startNewDialog: "New chat", continueDialog: "Select chat", getDialogs: "Load chats",
  getHistory: "Load history", getModels: "Load models", sendMessage: "Send message", changeModel: "Change model",
  closeChat: "Leave chat", finishChat: "Finish chat", cancelTurn: "Stop", resolveApproval: "Resolve approval", connectAsReader: "Join as reader",
  connectAsWriter: "Join as writer", transferWriter: "Transfer writer",
  deleteChat: "Delete chat", closeChatAndDelete: "Leave & delete",
  finishChatAndDelete: "Finish & delete",
};

const deletion = new Set<Action>(["deleteChat", "closeChatAndDelete", "finishChatAndDelete"]);

export default function App() {
  const socket = useRef<WebSocket | null>(null);
  const bottom = useRef<HTMLDivElement>(null);
  const threadRef = useRef<string | null>(null);
  const pendingRequest = useRef<string | null>(null);
  const messageRevision = useRef(0);
  const historyRevision = useRef(0);
  const messagesRef = useRef<ChatMessage[]>([]);
  const spokenReply = useRef<{ turnId: string; text: string } | null>(null);
  const [status, setStatus] = useState<"disconnected" | "connecting" | "connected">("disconnected");
  const [serverUrl, setServerUrl] = useState("ws://localhost:8080/ws/chat");
  const [actions, setActions] = useState<Action[]>([]);
  const [dialogs, setDialogs] = useState<Dialog[]>([]);
  const [cursor, setCursor] = useState<string>();
  const [selected, setSelected] = useState<string | null>(null);
  const [thread, setThread] = useState<string | null>(null);
  const [clientId, setClientId] = useState<string | null>(null);
  const [writerId, setWriterId] = useState<string | null>(null);
  const [participants, setParticipants] = useState<string[]>([]);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [draft, setDraft] = useState("");
  const [model, setModel] = useState("");
  const [models, setModels] = useState<string[]>([]);
  const [limit, setLimit] = useState(10);
  const [threadInput, setThreadInput] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pending, setPending] = useState<string | null>(null);
  const [approvals, setApprovals] = useState<ApprovalPrompt[]>([]);
  const [thinking, setThinking] = useState(false);
  const [nod, setNod] = useState(0);
  const voice = useReplyVoice();

  useEffect(() => () => socket.current?.close(), []);
  useEffect(() => bottom.current?.scrollIntoView({ behavior: "smooth" }), [messages]);

  function updateMessages(next: ChatMessage[] | ((previous: ChatMessage[]) => ChatMessage[])) {
    const updated = typeof next === "function" ? next(messagesRef.current) : next;
    messagesRef.current = updated;
    setMessages(updated);
  }

  function sendJson(connection: WebSocket, request: ChatRequest) {
    const json = JSON.stringify(request);
    connection.send(json);
    console.log(`[BACKEND] >>> ${json}`);
  }

  function send(request: ChatRequest) {
    if (socket.current?.readyState !== WebSocket.OPEN || pendingRequest.current) return;
    setError("");
    setNotice("");
    pendingRequest.current = request.action;
    setPending(request.action);
    if (request.action === "getHistory") historyRevision.current = messageRevision.current;
    sendJson(socket.current, request);
  }

  function receive(event: ChatEvent) {
    setActions(event.availableAction);
    const data = event.data ?? {};
    const pendingReplies: Record<string, string[]> = {
      startNewDialog: ["session.joined"], continueDialog: ["dialog.selected"],
      getDialogs: ["dialogs"], getHistory: ["history"], sendMessage: ["user.text"],
      getModels: ["models"], cancelTurn: ["assistant.text.done"], resolveApproval: ["approval.resolved"],
      changeModel: ["model.changed"], closeChat: ["chat.closed"], finishChat: ["chat.finished"],
      connectAsReader: ["session.joined"], connectAsWriter: ["session.joined", "writer.changed"],
      transferWriter: ["writer.changed"], deleteChat: ["chat.deleted"],
      closeChatAndDelete: ["chat.deleted"], finishChatAndDelete: ["chat.deleted"],
    };
    if (event.type === "error" || (pendingRequest.current && pendingReplies[pendingRequest.current]?.includes(event.type))) {
      pendingRequest.current = null;
      setPending(null);
    }
    switch (event.type) {
      case "connected":
        setStatus("connected");
        if (event.availableAction.includes("getModels")) send({ type: "GET", action: "getModels" });
        break;
      case "models":
        setModels(data.models ?? []);
        break;
      case "dialogs":
        setDialogs(data.dialogs ?? []);
        setCursor(data.nextCursor);
        break;
      case "dialog.selected":
        setSelected(data.threadId ?? null);
        break;
      case "session.joined":
        voice.stop();
        spokenReply.current = null;
        setThinking(Boolean(data.turnId));
        setApprovals([]);
        threadRef.current = data.threadId ?? null;
        messageRevision.current = 0;
        setThread(data.threadId ?? null);
        setSelected(null);
        setClientId(data.clientId ?? null);
        setWriterId(data.writerId ?? null);
        setParticipants(data.writerId && data.writerId !== data.clientId ? [data.writerId] : []);
        updateMessages([]);
        setNotice(data.turnId ? "A response is already in progress. You will see new text as it arrives." : "");
        if (event.availableAction.includes("getHistory")) {
          historyRevision.current = messageRevision.current;
          if (socket.current) sendJson(socket.current, { type: "GET", action: "getHistory", limit: 50 });
        }
        break;
      case "history":
        if (data.threadId === threadRef.current && historyRevision.current === messageRevision.current) {
          updateMessages(data.messages ?? []);
        } else if (data.threadId === threadRef.current) {
          setNotice("New messages arrived while history was loading. Load history again after the turn finishes.");
        }
        break;
      case "user.text":
        if (data.threadId !== threadRef.current) break;
        voice.stop();
        spokenReply.current = data.turnId ? { turnId: data.turnId, text: "" } : null;
        setThinking(true);
        setNod(previous => previous + 1);
        messageRevision.current += 1;
        updateMessages(previous => [...previous, { role: "USER", text: data.text ?? "", turnId: data.turnId ?? undefined }]);
        break;
      case "assistant.text.delta":
        if (data.threadId !== threadRef.current) break;
        if (spokenReply.current && spokenReply.current.turnId === data.turnId) {
          spokenReply.current.text += data.text ?? "";
        }
        messageRevision.current += 1;
        updateMessages(previous => {
          const last = previous.at(-1);
          if (last?.role === "ASSISTANT" && last.turnId === data.turnId) {
            return [...previous.slice(0, -1), { ...last, text: last.text + (data.text ?? "") }];
          }
          return [...previous, { role: "ASSISTANT", text: data.text ?? "", turnId: data.turnId ?? undefined }];
        });
        break;
      case "approval.required":
        if (data.approval && data.approval.threadId === threadRef.current)
          setApprovals(previous => [...previous.filter(p => p.requestId !== data.approval!.requestId), data.approval!]);
        break;
      case "approval.resolved":
        setApprovals(previous => previous.filter(p => p.requestId !== data.requestId));
        break;
      case "assistant.text.done": {
        if (data.threadId !== threadRef.current) break;
        const reply = spokenReply.current;
        if (reply && reply.turnId !== data.turnId) break;
        spokenReply.current = null;
        setThinking(false);
        setApprovals([]);
        if (data.status && data.status !== "completed") setNotice(data.errorMessage || `Turn ${data.status}`);
        if (reply && data.status === "completed") {
          const context = messagesRef.current.filter(message => message.role !== "ASSISTANT" || message.turnId !== reply.turnId);
          void voice.speak(reply.text, context);
        }
        break;
      }
      case "writer.changed":
      case "session.updated":
        setWriterId(data.writerId ?? null);
        if (data.joinedClientId) setParticipants(previous => [...new Set([...previous, data.joinedClientId!])]);
        if (data.leftClientId) setParticipants(previous => previous.filter(id => id !== data.leftClientId));
        break;
      case "model.changed":
        setModel(data.model ?? "");
        setNotice(`Model selected: ${data.model}`);
        break;
      case "chat.closed":
      case "chat.finished":
      case "chat.deleted":
        setApprovals([]);
        if (data.threadId === threadRef.current) {
          voice.stop();
          spokenReply.current = null;
          setThinking(false);
          threadRef.current = null;
          setThread(null);
          setWriterId(null);
          setClientId(null);
          setParticipants([]);
          updateMessages([]);
        }
        setSelected(null);
        if (event.type === "chat.deleted") setDialogs(previous => previous.filter(dialog => dialog.threadId !== data.threadId));
        setNotice(event.type === "chat.deleted" ? "Chat deleted." : "Returned to the chat menu.");
        break;
      case "error":
        if (data.threadId === threadRef.current && data.code === "turn_failed" &&
            (!spokenReply.current || spokenReply.current.turnId === data.turnId)) {
          spokenReply.current = null;
          setThinking(false);
        }
        setError(data.message ?? "The request failed.");
        break;
    }
  }

  function connect() {
    if (socket.current && socket.current.readyState < WebSocket.CLOSING) return;
    setError("");
    let connection: WebSocket;
    try {
      connection = new WebSocket(serverUrl.trim());
    } catch {
      setError("Enter a valid WebSocket URL starting with ws:// or wss://.");
      return;
    }
    setStatus("connecting");
    socket.current = connection;
    connection.onmessage = message => {
      if (socket.current !== connection) return;
      try {
        const event = JSON.parse(message.data) as ChatEvent;
        if (!Array.isArray(event.availableAction) || typeof event.type !== "string") throw new Error();
        receive(event);
      } catch {
        setError("Could not read a response from Talia.");
      }
    };
    connection.onerror = () => setError(`Could not connect to ${serverUrl.trim()}. Check the URL, server, and allowed origins.`);
    connection.onclose = () => {
      if (socket.current !== connection) return;
      voice.stop();
      spokenReply.current = null;
      setThinking(false);
      socket.current = null;
      threadRef.current = null;
      pendingRequest.current = null;
      setPending(null);
      setStatus("disconnected");
      setActions([]);
      setThread(null);
      setSelected(null);
      setClientId(null);
      setWriterId(null);
      setParticipants([]);
      setDialogs([]);
      setCursor(undefined);
      updateMessages([]);
      setApprovals([]);
      setModels([]);
      setModel("");
      setNotice("");
    };
  }

  function perform(action: Action, threadId?: string) {
    if (deletion.has(action) && !window.confirm("Permanently delete this conversation and its history?")) return;
    switch (action) {
      case "startNewDialog": send({ type: "POST", action, ...(model.trim() ? { model: model.trim() } : {}) }); break;
      case "getDialogs": send({ type: "GET", action, limit }); break;
      case "getHistory": send({ type: "GET", action, limit }); break;
      case "getModels": send({ type: "GET", action }); break;
      case "continueDialog": if (threadId) send({ type: "POST", action, threadId }); break;
      case "connectAsReader":
      case "connectAsWriter": send({ type: "POST", action, ...(threadId ? { threadId } : {}) }); break;
      case "changeModel":
        if (model.trim()) send({ type: "POST", action, model: model.trim() });
        else setError("Enter a model ID first.");
        break;
      case "transferWriter":
        if (threadId?.trim()) send({ type: "POST", action, clientId: threadId.trim() });
        else setError("Choose or enter a connected client ID first.");
        break;
      case "closeChat":
      case "finishChat":
      case "cancelTurn": send({ type: "POST", action }); break;
      case "resolveApproval": break;
      case "deleteChat": send({ type: "DELETE", action, ...(threadId ? { threadId } : {}) }); break;
      case "closeChatAndDelete":
      case "finishChatAndDelete": send({ type: "DELETE", action }); break;
      case "sendMessage": break;
    }
  }

  function submitMessage() {
    const text = draft.trim();
    if (!text || !actions.includes("sendMessage")) return;
    send({ type: "POST", action: "sendMessage", text });
    setDraft("");
  }

  const available = (action: Action) => actions.includes(action);
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><div className="brand-mark">t<span>.</span></div><div><strong>talia</strong><small>YOUR CHAT WORKSPACE</small></div></div>
        <div className="sidebar-heading"><span>WORKSPACE</span><span className="sidebar-line" /></div>
        <div className="connection-card">
          <div className="connection-title"><span className={`status-dot ${status}`} />{status === "connected" ? "Connected" : status === "connecting" ? "Connecting" : "Disconnected"}</div>
          <p>{status === "connected" ? "Your chat server is ready." : "Connect to your local server to begin."}</p>
          <label className="server-url">WebSocket URL<input type="url" value={serverUrl} disabled={status !== "disconnected"} onChange={event => setServerUrl(event.target.value)} /></label>
          <button className={status === "connected" ? "button subtle" : "button dark"} onClick={status === "connected" ? () => socket.current?.close() : connect} disabled={status === "connecting"}>
            {status === "connected" ? "Disconnect" : status === "connecting" ? "Connecting…" : "Connect to Talia"} <span>↗</span>
          </button>
        </div>
        <div className="sidebar-heading chats-heading"><span>YOUR CHATS</span><span className="sidebar-line" /></div>
        <div className="sidebar-controls">
          {available("startNewDialog") && <button className="button new-chat" disabled={!!pending} onClick={() => perform("startNewDialog")}>＋ New chat</button>}
          {available("getDialogs") && <div className="load-row"><button className="text-button" disabled={!!pending} onClick={() => perform("getDialogs")}>↻ Load chats</button><label>Limit <input type="number" min="1" max="100" value={limit} onChange={event => setLimit(Number(event.target.value))} /></label></div>}
          {available("continueDialog") && <form className="open-by-id" onSubmit={event => { event.preventDefault(); if (threadInput.trim()) perform("continueDialog", threadInput.trim()); }}><input aria-label="Chat ID" placeholder="Open by chat ID" value={threadInput} onChange={event => setThreadInput(event.target.value)} /><button aria-label="Select chat" disabled={!!pending || !threadInput.trim()}>→</button></form>}
        </div>
        <div className="dialog-list">
          {dialogs.map(dialog => (
            <div className={`dialog ${selected === dialog.threadId || thread === dialog.threadId ? "selected" : ""}`} key={dialog.threadId}>
              <button className="dialog-main" disabled={!available("continueDialog") || !!pending} onClick={() => perform("continueDialog", dialog.threadId)}>
                <span className="dialog-icon">▤</span><span><strong>{dialog.name || "Untitled chat"}</strong><small>{dialog.preview || dialog.threadId}</small></span>
              </button>
              <div className="dialog-actions">{dialog.availableAction.map(action => <button key={action} disabled={!available("continueDialog") || !!pending} className={deletion.has(action) ? "danger-link" : ""} onClick={() => perform(action, dialog.threadId)}>{labels[action]}</button>)}</div>
            </div>
          ))}
          {cursor && available("getDialogs") && <button className="text-button more" disabled={!!pending} onClick={() => send({ type: "GET", action: "getDialogs", limit, cursor })}>Load next page →</button>}
          {status === "connected" && dialogs.length === 0 && <p className="sidebar-empty">Your conversations will appear here.</p>}
        </div>
        <div className="sidebar-footer"><span className="footer-icon">✳</span><div><strong>Made for conversation</strong><small>Connected to your workspace</small></div></div>
      </aside>

      <main className="main chat-layout">
        <header className="topbar">
          <div><span className="eyebrow">YOUR SPACE / CHAT</span><h1>{thread ? "Conversation" : "Chat workspace"}</h1></div>
          <div className="top-status"><span className={`status-dot ${status}`} />{status === "connected" ? "Online" : status === "connecting" ? "Connecting" : "Offline"}</div>
        </header>
        <div className="workspace">
          <div className="conversation-header">
            <div><span className="eyebrow">CONVERSATION</span><h2>{thread ? (dialogs.find(dialog => dialog.threadId === thread)?.name || "Your chat") : selected ? "Choose how to join" : "A little space to think."}</h2>
              <p>{thread ? `Chat ID: ${thread}` : selected ? `Chat ID: ${selected}` : "Connect, pick a conversation, and take it from there."}</p></div>
            {thread && <div className="role-badge"><span className="role-dot" />{clientId === writerId ? "Writer" : "Reader"}</div>}
          </div>
          {(error || notice) && <div role="status" className={error ? "alert error" : "alert"}>{error || notice}<button aria-label="Dismiss" onClick={() => { setError(""); setNotice(""); }}>×</button></div>}
          {selected && !thread && <div className="join-panel"><div><strong>Ready to join?</strong><p>Choose an available role to open this conversation.</p></div><div className="join-actions">{(["connectAsWriter", "connectAsReader", "deleteChat"] as Action[]).filter(available).map(action => <button key={action} className={deletion.has(action) ? "button danger" : "button dark"} disabled={!!pending} onClick={() => perform(action)}>{labels[action]}</button>)}</div></div>}
          <div className="message-area">
            {messages.length === 0 ? <div className="empty-state"><div className="empty-icon">✳</div><span className="eyebrow">{thread ? "START THE CONVERSATION" : "WELCOME TO TALIA"}</span><h3>{thread ? "A fresh page." : "Good to see you here."}</h3><p>{thread ? "Your messages and replies will appear here." : "Your conversations live here. Connect to Talia to get started."}</p></div> :
              <div className="messages">{messages.map((message, index) => <article className={`message ${message.role === "USER" ? "user" : "assistant"}`} key={index}><div className="avatar">{message.role === "USER" ? "Y" : "✳"}</div><div className="message-content"><div className="message-label">{message.role === "USER" ? "YOU" : "TALIA"}</div><div className="bubble">{message.text}</div></div></article>)}<div ref={bottom} /></div>}
          </div>
          {approvals.length > 0 && thread && <section aria-label="Pending Codex approvals" className="join-panel" style={{display:"grid",gap:12}}>
            {approvals.map(approval => <div key={approval.requestId} style={{display:"grid",gap:8}}>
              <strong>Codex requires your approval</strong>
              <div>{approval.method}</div>
              {approval.command && <code style={{whiteSpace:"pre-wrap"}}>{approval.command}</code>}
              {approval.cwd && <small>Directory: {approval.cwd}</small>}
              {approval.reason && <p>{approval.reason}</p>}
              <div className="join-actions">
                <button className="button dark" disabled={!available("resolveApproval") || !!pending}
                  onClick={() => send({type:"POST",action:"resolveApproval",requestId:approval.requestId,decision:"ACCEPT"})}>Allow once</button>
                <button className="button danger" disabled={!available("resolveApproval") || !!pending}
                  onClick={() => send({type:"POST",action:"resolveApproval",requestId:approval.requestId,decision:"DECLINE"})}>Deny</button>
              </div>
            </div>)}
          </section>}
          {thread && available("cancelTurn") && <button className="button danger" onClick={() => perform("cancelTurn")}>Stop generation</button>}
          {thread && <div className="toolbar">
            {available("getHistory") && <button className="tool-button" disabled={!!pending} onClick={() => perform("getHistory")}>↻ History</button>}
            {available("connectAsWriter") && <button className="tool-button" disabled={!!pending} onClick={() => perform("connectAsWriter")}>Become writer</button>}
            {available("changeModel") && <ModelControl model={model} models={models} setModel={setModel} disabled={!!pending} onChange={() => perform("changeModel")} />}
            {available("transferWriter") && <TransferControl participants={participants.filter(id => id !== clientId)} disabled={!!pending} onTransfer={id => perform("transferWriter", id)} />}
            {(["closeChat", "finishChat", "deleteChat", "closeChatAndDelete", "finishChatAndDelete"] as Action[]).filter(available).map(action => <button key={action} className={deletion.has(action) ? "tool-button danger-link" : "tool-button"} disabled={!!pending} onClick={() => perform(action)}>{labels[action]}</button>)}
          </div>}
          {available("startNewDialog") && <div className="model-row"><label htmlFor="default-model">Model for new chat</label><select id="default-model" value={model} onChange={event => setModel(event.target.value)}><option value="">Server default</option>{models.map(id => <option key={id} value={id}>{id}</option>)}</select></div>}
          {available("getModels") && <div className="model-row"><button className="text-button" disabled={!!pending} onClick={() => perform("getModels")}>↻ Refresh models</button></div>}
          <div className="composer"><textarea aria-label="Message" placeholder={thread ? available("sendMessage") ? "Write a message to Talia…" : "You’re connected as a reader or a response is in progress." : "Open a chat to start messaging…"} value={draft} disabled={!available("sendMessage") || !!pending} onChange={event => setDraft(event.target.value)} onKeyDown={event => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); submitMessage(); } }} /><button aria-label="Send message" className="send-button" disabled={!available("sendMessage") || !draft.trim() || !!pending} onClick={submitMessage}>↑</button></div>
          <div className="composer-hint"><span>Enter to send · Shift + Enter for a new line</span><span>TALIA CHAT</span></div>
        </div>
        <AvatarPanel thinking={thinking} chatNod={nod} voice={voice} />
      </main>
    </div>
  );
}

function ModelControl({ model, models, setModel, disabled, onChange }: { model: string; models: string[]; setModel: (value: string) => void; disabled: boolean; onChange: () => void }) {
  return <div className="inline-control"><select aria-label="Model" value={model} onChange={event => setModel(event.target.value)}><option value="">Select model</option>{models.map(id => <option key={id} value={id}>{id}</option>)}</select><button className="tool-button" disabled={disabled || !model} onClick={onChange}>Change model</button></div>;
}

function TransferControl({ participants, disabled, onTransfer }: { participants: string[]; disabled: boolean; onTransfer: (id: string) => void }) {
  const [target, setTarget] = useState("");
  return <div className="inline-control"><input aria-label="Client ID" placeholder="Reader client ID" value={target} list="participants" onChange={event => setTarget(event.target.value)} /><datalist id="participants">{participants.map(id => <option key={id} value={id} />)}</datalist><button className="tool-button" disabled={disabled} onClick={() => onTransfer(target)}>Transfer writer</button></div>;
}
