// ── State ───────────────────────────────────────────────────
let currentSessionId = null;
let isStreaming = false;

// Per-session message history for rendering: { sessionId: [{ role, content, toolCalls }] }
const chatHistory = {};

// ── DOM refs ────────────────────────────────────────────────
const messagesEl = document.getElementById('messages');
const inputEl = document.getElementById('message-input');
const sendBtn = document.getElementById('send-btn');
const sessionListEl = document.getElementById('session-list');
const newChatBtn = document.getElementById('new-chat-btn');
const statusEl = document.getElementById('status-indicator');
const sidebarToggle = document.getElementById('sidebar-toggle');
const sidebar = document.getElementById('sidebar');

// ── Markdown setup ──────────────────────────────────────────
if (typeof marked !== 'undefined') {
    marked.setOptions({
        highlight: function(code, lang) {
            if (typeof hljs !== 'undefined' && lang && hljs.getLanguage(lang)) {
                try { return hljs.highlight(code, { language: lang }).value; }
                catch (e) { /* fall through */ }
            }
            if (typeof hljs !== 'undefined') {
                try { return hljs.highlightAuto(code).value; }
                catch (e) { /* fall through */ }
            }
            return code;
        },
        breaks: true,
        gfm: true
    });
}

// ── Initialization ──────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    loadSessions();
    setupEventListeners();
});

function setupEventListeners() {
    sendBtn.addEventListener('click', sendMessage);

    inputEl.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });

    // Auto-resize textarea
    inputEl.addEventListener('input', () => {
        inputEl.style.height = 'auto';
        inputEl.style.height = Math.min(inputEl.scrollHeight, 200) + 'px';
    });

    newChatBtn.addEventListener('click', createNewSession);

    sidebarToggle.addEventListener('click', () => {
        sidebar.classList.toggle('open');
    });

    // Close sidebar on mobile when clicking main area
    document.getElementById('main').addEventListener('click', () => {
        sidebar.classList.remove('open');
    });
}

// ── Session Management ──────────────────────────────────────
async function loadSessions() {
    try {
        const res = await fetch('/api/sessions');
        const sessions = await res.json();
        renderSessionList(sessions);
    } catch (e) {
        console.error('Failed to load sessions:', e);
    }
}

async function createNewSession() {
    try {
        const res = await fetch('/api/sessions', { method: 'POST' });
        const session = await res.json();
        currentSessionId = session.id;
        chatHistory[session.id] = [];
        await loadSessions();
        renderMessages();
        inputEl.focus();
        sidebar.classList.remove('open');
    } catch (e) {
        console.error('Failed to create session:', e);
    }
}

function switchSession(sessionId) {
    currentSessionId = sessionId;
    if (!chatHistory[sessionId]) {
        chatHistory[sessionId] = [];
    }
    renderMessages();
    highlightActiveSession();
    inputEl.focus();
    sidebar.classList.remove('open');
}

function renderSessionList(sessions) {
    sessionListEl.innerHTML = '';
    sessions.forEach(session => {
        const el = document.createElement('div');
        el.className = 'session-item' + (session.id === currentSessionId ? ' active' : '');
        el.textContent = session.title || 'New Chat';
        el.addEventListener('click', () => switchSession(session.id));
        sessionListEl.appendChild(el);
    });
}

function highlightActiveSession() {
    document.querySelectorAll('.session-item').forEach((el, i) => {
        // Re-render the list to update active state
    });
    loadSessions();
}

// ── Message Rendering ───────────────────────────────────────
function renderMessages() {
    const messages = chatHistory[currentSessionId] || [];

    if (messages.length === 0) {
        messagesEl.innerHTML = `
            <div class="welcome-message">
                <h2>Agentic SDK</h2>
                <p>Start a conversation with the coding assistant. It can read, write, and edit files, run shell commands, search code, and more.</p>
            </div>`;
        return;
    }

    messagesEl.innerHTML = '';
    messages.forEach(msg => {
        appendMessageBubble(msg.role, msg.content, msg.toolCalls);
    });
    scrollToBottom();
}

function appendMessageBubble(role, content, toolCalls) {
    const msgEl = document.createElement('div');
    msgEl.className = 'message ' + role;

    const avatarEl = document.createElement('div');
    avatarEl.className = 'message-avatar';
    avatarEl.textContent = role === 'user' ? 'U' : 'A';

    const contentEl = document.createElement('div');
    contentEl.className = 'message-content';

    if (role === 'assistant') {
        contentEl.innerHTML = renderMarkdown(content || '');
    } else {
        contentEl.textContent = content;
    }

    // Append tool call cards if any
    if (toolCalls && toolCalls.length > 0) {
        toolCalls.forEach(tc => {
            contentEl.appendChild(createToolCallCard(tc));
        });
    }

    msgEl.appendChild(avatarEl);
    msgEl.appendChild(contentEl);
    messagesEl.appendChild(msgEl);

    // Highlight code blocks
    msgEl.querySelectorAll('pre code').forEach(block => {
        if (typeof hljs !== 'undefined') {
            hljs.highlightElement(block);
        }
    });
}

function createToolCallCard(tc) {
    const card = document.createElement('div');
    card.className = 'tool-call-card';

    const statusClass = tc.error ? 'error' : (tc.output ? 'done' : 'running');
    const statusText = tc.error ? 'Error' : (tc.output ? 'Done' : 'Running...');

    card.innerHTML = `
        <div class="tool-call-header">
            <span class="tool-icon">&#9881;</span>
            <span class="tool-name">${escapeHtml(tc.tool || 'unknown')}</span>
            <span class="tool-status ${statusClass}">${statusText}</span>
            <span class="expand-icon">&#9654;</span>
        </div>
        <div class="tool-call-body">
            <pre>${escapeHtml(tc.output || tc.error || 'Executing...')}</pre>
        </div>`;

    card.querySelector('.tool-call-header').addEventListener('click', () => {
        card.classList.toggle('expanded');
    });

    return card;
}

function renderMarkdown(text) {
    if (typeof marked !== 'undefined' && text) {
        try {
            return marked.parse(text);
        } catch (e) {
            console.warn('Markdown parse error:', e);
        }
    }
    return escapeHtml(text);
}

// ── Sending Messages ────────────────────────────────────────
async function sendMessage() {
    const text = inputEl.value.trim();
    if (!text || isStreaming) return;

    // Ensure we have a session
    if (!currentSessionId) {
        await createNewSession();
    }

    // Add user message to history
    if (!chatHistory[currentSessionId]) {
        chatHistory[currentSessionId] = [];
    }
    chatHistory[currentSessionId].push({ role: 'user', content: text, toolCalls: [] });

    // Clear input
    inputEl.value = '';
    inputEl.style.height = 'auto';

    // Render user message
    renderMessages();

    // Start streaming
    await streamResponse(text);

    // Refresh session list (title may have updated)
    loadSessions();
}

async function streamResponse(userMessage) {
    isStreaming = true;
    sendBtn.disabled = true;
    setStatus('streaming', 'Thinking...');

    // Create assistant message placeholder
    const assistantMsg = { role: 'assistant', content: '', toolCalls: [] };
    chatHistory[currentSessionId].push(assistantMsg);

    // Render the empty assistant bubble
    renderMessages();

    // Add loading indicator
    const loadingEl = document.createElement('div');
    loadingEl.className = 'loading-indicator';
    loadingEl.id = 'loading';
    loadingEl.innerHTML = '<div class="loading-dots"><span></span><span></span><span></span></div><span>Generating...</span>';
    messagesEl.appendChild(loadingEl);
    scrollToBottom();

    try {
        const response = await fetch('/api/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                message: userMessage,
                sessionId: currentSessionId
            })
        });

        if (!response.ok) {
            throw new Error('HTTP ' + response.status + ': ' + response.statusText);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });

            // Parse SSE events from buffer
            const lines = buffer.split('\n');
            buffer = lines.pop(); // keep incomplete last line

            let eventName = '';
            let eventData = '';

            for (const line of lines) {
                if (line.startsWith('event:')) {
                    eventName = line.substring(6).trim();
                } else if (line.startsWith('data:')) {
                    eventData = line.substring(5).trim();
                } else if (line === '' && eventData) {
                    // End of SSE event
                    handleSseEvent(eventName, eventData, assistantMsg);
                    eventName = '';
                    eventData = '';
                }
            }
        }
    } catch (e) {
        console.error('Stream error:', e);
        assistantMsg.content += '\n\n[Error: ' + e.message + ']';
        setStatus('error', 'Error: ' + e.message);
    } finally {
        // Remove loading indicator
        const loading = document.getElementById('loading');
        if (loading) loading.remove();

        isStreaming = false;
        sendBtn.disabled = false;
        setStatus('idle', 'Ready');
        renderMessages();
    }
}

function handleSseEvent(eventName, dataStr, assistantMsg) {
    try {
        const data = JSON.parse(dataStr);

        switch (data.type || eventName) {
            case 'text-delta':
                assistantMsg.content += data.delta;
                updateStreamingContent(assistantMsg);
                setStatus('streaming', 'Streaming...');
                break;

            case 'tool-call':
                assistantMsg.toolCalls.push({
                    tool: data.tool,
                    callId: data.callId,
                    output: null,
                    error: false
                });
                setStatus('streaming', 'Calling ' + data.tool + '...');
                updateStreamingContent(assistantMsg);
                break;

            case 'tool-result': {
                const tc = assistantMsg.toolCalls.find(t => t.callId === data.callId);
                if (tc) {
                    tc.output = data.output;
                    tc.error = data.error;
                }
                setStatus('streaming', 'Thinking...');
                updateStreamingContent(assistantMsg);
                break;
            }

            case 'done':
                if (data.sessionId) {
                    currentSessionId = data.sessionId;
                }
                break;

            case 'permission-asked':
                showPermissionPrompt(data);
                setStatus('streaming', 'Waiting for permission...');
                break;

            case 'tool-progress':
                setStatus('streaming', data.title || 'Running...');
                break;

            case 'error':
                assistantMsg.content += '\n\n[Error: ' + data.message + ']';
                setStatus('error', 'Error');
                updateStreamingContent(assistantMsg);
                break;
        }
    } catch (e) {
        console.warn('Failed to parse SSE data:', dataStr, e);
    }
}

function updateStreamingContent(assistantMsg) {
    // Find the last assistant message bubble and update it
    const messageBubbles = messagesEl.querySelectorAll('.message.assistant');
    const lastBubble = messageBubbles[messageBubbles.length - 1];
    if (!lastBubble) return;

    const contentEl = lastBubble.querySelector('.message-content');
    if (!contentEl) return;

    // Render markdown content
    contentEl.innerHTML = renderMarkdown(assistantMsg.content);

    // Add tool call cards
    assistantMsg.toolCalls.forEach(tc => {
        contentEl.appendChild(createToolCallCard(tc));
    });

    // Highlight code blocks
    contentEl.querySelectorAll('pre code').forEach(block => {
        if (typeof hljs !== 'undefined') {
            hljs.highlightElement(block);
        }
    });

    scrollToBottom();
}

// ── Permission Prompt ──────────────────────────────────────
function showPermissionPrompt(data) {
    // Remove any existing prompt
    const existing = document.getElementById('permission-prompt');
    if (existing) existing.remove();

    const overlay = document.createElement('div');
    overlay.id = 'permission-prompt';
    overlay.className = 'permission-overlay';
    overlay.innerHTML = `
        <div class="permission-dialog">
            <div class="permission-header">Permission Required</div>
            <div class="permission-body">
                <div class="permission-field">
                    <span class="permission-label">Tool:</span>
                    <span class="permission-value">${escapeHtml(data.tool || data.permission || 'unknown')}</span>
                </div>
                <div class="permission-field">
                    <span class="permission-label">Action:</span>
                    <span class="permission-value">${escapeHtml(data.target || '')}</span>
                </div>
                ${data.description ? `<div class="permission-field">
                    <span class="permission-label">Detail:</span>
                    <span class="permission-value">${escapeHtml(data.description)}</span>
                </div>` : ''}
            </div>
            <div class="permission-actions">
                <button class="perm-btn perm-reject" onclick="replyPermission('${data.id}', 'reject')">Reject</button>
                <button class="perm-btn perm-once" onclick="replyPermission('${data.id}', 'once')">Allow Once</button>
                <button class="perm-btn perm-always" onclick="replyPermission('${data.id}', 'always')">Allow Always</button>
            </div>
        </div>`;
    document.body.appendChild(overlay);
}

async function replyPermission(requestId, reply) {
    try {
        await fetch('/api/permissions/' + requestId + '/reply', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ reply: reply })
        });
    } catch (e) {
        console.error('Failed to reply permission:', e);
    }
    const prompt = document.getElementById('permission-prompt');
    if (prompt) prompt.remove();
    setStatus('streaming', 'Continuing...');
}

// ── Utilities ───────────────────────────────────────────────
function scrollToBottom() {
    requestAnimationFrame(() => {
        messagesEl.scrollTop = messagesEl.scrollHeight;
    });
}

function setStatus(type, text) {
    statusEl.className = 'status ' + type;
    statusEl.textContent = text;
}

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
