"use strict";

/* ============================ Session ============================ */
const Session = {
    get token() { return localStorage.getItem("lcf_token"); },
    get role() { return localStorage.getItem("lcf_role"); },
    get user() { return localStorage.getItem("lcf_user"); },
    get lawyerId() { const v = localStorage.getItem("lcf_lawyerId"); return v ? Number(v) : null; },
    get clientId() { const v = localStorage.getItem("lcf_clientId"); return v ? Number(v) : null; },
    save(auth) {
        localStorage.setItem("lcf_token", auth.token);
        localStorage.setItem("lcf_role", auth.role);
        localStorage.setItem("lcf_user", auth.username);
        if (auth.lawyerId != null) localStorage.setItem("lcf_lawyerId", auth.lawyerId); else localStorage.removeItem("lcf_lawyerId");
        if (auth.clientId != null) localStorage.setItem("lcf_clientId", auth.clientId); else localStorage.removeItem("lcf_clientId");
    },
    clear() {
        ["lcf_token", "lcf_role", "lcf_user", "lcf_lawyerId", "lcf_clientId"].forEach(k => localStorage.removeItem(k));
    }
};

/* ============================ API ============================ */
async function api(method, path, body) {
    const headers = { "Content-Type": "application/json" };
    if (Session.token) headers["Authorization"] = "Bearer " + Session.token;

    const res = await fetch(path, {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined
    });

    if (res.status === 204) return null;

    let data = null;
    const text = await res.text();
    if (text) { try { data = JSON.parse(text); } catch (e) { data = text; } }

    if (!res.ok) {
        if (res.status === 401 && Session.token) { logout(); }
        const msg = (data && data.message) ? data.message : ("Грешка " + res.status);
        const fields = data && data.fieldErrors
            ? " (" + Object.entries(data.fieldErrors).map(([k, v]) => k + ": " + v).join(", ") + ")"
            : "";
        throw new Error(msg + fields);
    }
    return data;
}

/* ============================ Chat socket ============================ */
const ChatState = {
    socket: null,
    reconnectTimer: null,
    activeConversationId: null,
    unreadCount: 0,
    listFilter: ""
};
const AppSyncState = {
    socket: null,
    reconnectTimer: null,
    refreshTimer: null,
    changedResources: new Set()
};
let activeTabKey = null;

function isChatUser() {
    return Session.role === "CLIENT" || Session.role === "LAWYER" || Session.role === "ADMIN";
}

function connectChatSocket() {
    if (!Session.token || !isChatUser()) return;
    if (ChatState.socket && (ChatState.socket.readyState === WebSocket.OPEN
        || ChatState.socket.readyState === WebSocket.CONNECTING)) return;

    clearTimeout(ChatState.reconnectTimer);
    const scheme = location.protocol === "https:" ? "wss:" : "ws:";
    const socket = new WebSocket(scheme + "//" + location.host + "/ws/chat");
    ChatState.socket = socket;

    socket.addEventListener("open", () => {
        socket.send(JSON.stringify({ type: "AUTH", token: Session.token }));
    });
    socket.addEventListener("message", async event => {
        let payload;
        try { payload = JSON.parse(event.data); } catch (e) { return; }
        if (payload.type === "UNREAD_COUNT_CHANGED" && payload.data) {
            updateChatUnreadBadge(payload.data.unreadCount);
        } else if (payload.type !== "AUTHENTICATED") {
            await refreshChatUnread();
        }
        if (activeTabKey === "chats" && payload.type !== "AUTHENTICATED") {
            await renderChats(ChatState.activeConversationId);
        }
    });
    socket.addEventListener("close", () => {
        if (ChatState.socket === socket) ChatState.socket = null;
        if (Session.token && isChatUser()) {
            ChatState.reconnectTimer = setTimeout(connectChatSocket, 2000);
        }
    });
}

function closeChatSocket() {
    clearTimeout(ChatState.reconnectTimer);
    if (ChatState.socket) ChatState.socket.close();
    ChatState.socket = null;
    ChatState.activeConversationId = null;
    updateChatUnreadBadge(0);
}

async function refreshChatUnread() {
    if (!Session.token || !isChatUser()) return;
    try {
        const data = await api("GET", "/api/chats/unread-count");
        updateChatUnreadBadge(data.unreadCount);
    } catch (e) {
        // The rest of the application remains usable if chat is temporarily unavailable.
    }
}

function updateChatUnreadBadge(count) {
    ChatState.unreadCount = Number(count) || 0;
    const badge = document.querySelector("[data-chat-unread]");
    if (!badge) return;
    badge.textContent = ChatState.unreadCount > 99 ? "99+" : String(ChatState.unreadCount);
    badge.classList.toggle("hidden", ChatState.unreadCount === 0);
}

/* ============================ App-wide live updates ============================ */
const VIEW_RESOURCE_DEPENDENCIES = {
    clients: ["clients", "lawyers"],
    lawyers: ["lawyers"],
    "case-types": ["case-types"],
    "legal-services": ["legal-services", "clients", "lawyers", "case-types", "invoices"],
    documents: ["documents", "clients", "lawyers", "case-types"],
    appointments: ["appointments", "clients", "lawyers"],
    invoices: ["invoices", "legal-services", "clients", "lawyers"]
};

function connectAppSocket() {
    if (!Session.token) return;
    if (AppSyncState.socket && (AppSyncState.socket.readyState === WebSocket.OPEN
        || AppSyncState.socket.readyState === WebSocket.CONNECTING)) return;

    clearTimeout(AppSyncState.reconnectTimer);
    const scheme = location.protocol === "https:" ? "wss:" : "ws:";
    const socket = new WebSocket(scheme + "//" + location.host + "/ws/events");
    AppSyncState.socket = socket;

    socket.addEventListener("open", () => {
        socket.send(JSON.stringify({ type: "AUTH", token: Session.token }));
    });
    socket.addEventListener("message", event => {
        let payload;
        try { payload = JSON.parse(event.data); } catch (e) { return; }
        if (payload.type === "RESOURCE_CHANGED" && payload.resource) {
            scheduleAppSync(payload.resource);
        }
    });
    socket.addEventListener("close", () => {
        if (AppSyncState.socket === socket) AppSyncState.socket = null;
        if (Session.token) AppSyncState.reconnectTimer = setTimeout(connectAppSocket, 2000);
    });
}

function closeAppSocket() {
    clearTimeout(AppSyncState.reconnectTimer);
    clearTimeout(AppSyncState.refreshTimer);
    if (AppSyncState.socket) AppSyncState.socket.close();
    AppSyncState.socket = null;
    AppSyncState.changedResources.clear();
}

function scheduleAppSync(resource) {
    AppSyncState.changedResources.add(resource);
    clearTimeout(AppSyncState.refreshTimer);
    AppSyncState.refreshTimer = setTimeout(syncActiveView, 120);
}

async function syncActiveView() {
    const changed = new Set(AppSyncState.changedResources);
    AppSyncState.changedResources.clear();
    invalidateOptions();

    try {
        if (ENTITIES[activeTabKey]) {
            const dependencies = VIEW_RESOURCE_DEPENDENCIES[activeTabKey] || [activeTabKey];
            if (dependencies.some(resource => changed.has(resource))) await refreshEntityTable(activeTabKey);
            return;
        }
        if (activeTabKey === "reports") {
            await renderReports();
            return;
        }
        if (activeTabKey === "search") {
            await refreshActiveSearch();
            return;
        }
        if (activeTabKey === "audit" && changed.has("audit-events")) {
            await renderAudit();
            return;
        }
        if (activeTabKey === "chats" && ["clients", "lawyers"].some(resource => changed.has(resource))) {
            await renderChats(ChatState.activeConversationId);
        }
    } catch (e) {
        // The current view remains usable; the next event or navigation retries synchronization.
    }
}

async function refreshActiveSearch() {
    const panel = document.querySelector(".search-panel");
    const host = document.querySelector(".search-results");
    if (!panel || !host) return;
    const query = panel.querySelector('input[type="search"]');
    const type = panel.querySelector("select");
    if (!query || !type || query.value.trim().length < 2) return;
    await runSearch(query.value.trim(), type.value, host, ++searchRequestSequence);
}

async function refreshDraftSelectors() {
    const selectors = [...document.querySelectorAll("[data-option-source]")];
    for (const select of selectors) {
        const source = select.dataset.optionSource;
        const current = select.value;
        const rows = await loadOptions(source);
        select.innerHTML = "";
        for (const row of rows) select.appendChild(el("option", { value: row.id }, optionText(source, row)));
        if ([...select.options].some(option => String(option.value) === String(current))) select.value = current;
    }
}

/* ============================ Helpers ============================ */
const $ = sel => document.querySelector(sel);
const el = (tag, attrs = {}, ...children) => {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
        if (k === "class") node.className = v;
        else if (k === "html") node.innerHTML = v;
        else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
        else if (v !== null && v !== undefined) node.setAttribute(k, v);
    }
    for (const c of children) {
        if (c === null || c === undefined) continue;
        node.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    }
    return node;
};

let toastTimer;
function toast(message, type = "") {
    const t = $("#toast");
    t.textContent = message;
    t.className = "toast " + type;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.add("hidden"), 3500);
}

function boolBadge(v) {
    return el("span", { class: "badge " + (v ? "yes" : "no") }, v ? "Да" : "Не");
}

function statusLabel(value) {
    return {
        DRAFT: "Чернова",
        ISSUED: "Издадена",
        PAID: "Платена",
        CANCELLED: "Анулирана",
        REQUESTED: "Заявена",
        CONFIRMED: "Потвърдена",
        COMPLETED: "Приключена"
    }[value] || value || "—";
}

function invoiceState(invoices) {
    if (!invoices.length) return { label: "Няма фактура", className: "no" };
    const active = invoices.filter(invoice => invoice.status !== "CANCELLED");
    const suffix = invoices.length > 1 ? " · " + invoices.length : "";
    if (active.some(invoice => invoice.status === "PAID")) {
        return { label: "Платена" + suffix, className: "yes" };
    }
    const overdue = active.some(invoice => invoice.status === "ISSUED"
        && invoice.dueDate && new Date(invoice.dueDate + "T23:59:59") < new Date());
    if (overdue) return { label: "Просрочена" + suffix, className: "overdue" };
    if (active.some(invoice => invoice.status === "ISSUED")) {
        return { label: "Издадена" + suffix, className: "status" };
    }
    if (active.some(invoice => invoice.status === "DRAFT")) {
        return { label: "Чернова" + suffix, className: "draft" };
    }
    return { label: "Анулирана" + suffix, className: "no" };
}

/* ============================ Option sources ============================ */
const OPTION_SOURCES = {
    lawyers: { endpoint: "/api/lawyers", labelKey: "fullName" },
    clients: { endpoint: "/api/clients", labelKey: "fullName" },
    "case-types": { endpoint: "/api/case-types", labelKey: "name" },
    "legal-services": { endpoint: "/api/legal-services", labelKey: "description" }
};
const optionCache = {};
async function loadOptions(key) {
    if (!optionCache[key]) {
        const src = OPTION_SOURCES[key];
        optionCache[key] = await api("GET", src.endpoint);
    }
    return optionCache[key];
}
function invalidateOptions() { Object.keys(optionCache).forEach(k => delete optionCache[k]); }

function optionText(source, row) {
    if (source === "legal-services") {
        return "#" + row.id + " · " + row.clientName + " · " + row.caseTypeName + " · " + row.fee + " лв.";
    }
    const labelKey = OPTION_SOURCES[source].labelKey;
    return (row[labelKey] || ("#" + row.id)) + " (#" + row.id + ")";
}

/* ============================ Entity configs ============================ */
const ENTITIES = {
    clients: {
        title: "Доверители",
        endpoint: "/api/clients",
        writeRoles: ["ADMIN", "LAWYER"],
        accountProvisioning: true,
        columns: [
            { key: "id", label: "ID" },
            { key: "fullName", label: "Име" },
            { key: "identifier", label: "ЕГН/Идент." },
            { key: "contact", label: "Контакт" },
            { key: "legalAidEligible", label: "Правна помощ", type: "bool" },
            { key: "leadLawyerName", label: "Водещ адвокат" },
            { key: "username", label: "Акаунт", type: "account" }
        ],
        fields: [
            { key: "fullName", label: "Име", type: "text", required: true },
            { key: "identifier", label: "ЕГН / Идентификатор", type: "text" },
            { key: "contact", label: "Контакт", type: "text" },
            { key: "leadLawyerId", label: "Водещ адвокат", type: "select", source: "lawyers", allowEmpty: true, selfRoleOnCreate: "LAWYER" },
            { key: "legalAidEligible", label: "Право на правна помощ (НБПП)", type: "checkbox", full: true }
        ]
    },
    lawyers: {
        title: "Адвокати",
        endpoint: "/api/lawyers",
        writeRoles: ["ADMIN"],
        accountProvisioning: true,
        columns: [
            { key: "id", label: "ID" },
            { key: "registrationNumber", label: "Рег. №" },
            { key: "fullName", label: "Име" },
            { key: "specialty", label: "Специалност" },
            { key: "username", label: "Акаунт", type: "account" }
        ],
        fields: [
            { key: "registrationNumber", label: "Регистрационен №", type: "text", required: true },
            { key: "fullName", label: "Име", type: "text", required: true },
            { key: "specialty", label: "Специалност", type: "text" }
        ]
    },
    "case-types": {
        title: "Видове казуси",
        endpoint: "/api/case-types",
        writeRoles: ["ADMIN", "LAWYER"],
        columns: [
            { key: "id", label: "ID" },
            { key: "name", label: "Име" },
            { key: "description", label: "Описание" }
        ],
        fields: [
            { key: "name", label: "Име", type: "text", required: true },
            { key: "description", label: "Описание", type: "text", full: true }
        ]
    },
    "legal-services": {
        title: "Правни услуги",
        endpoint: "/api/legal-services",
        writeRoles: ["ADMIN", "LAWYER"],
        rowOwnerField: "lawyerId",
        note: "Платецът се определя автоматично. Плащането се управлява от фактурата към услугата.",
        columns: [
            { key: "id", label: "ID" },
            { key: "date", label: "Дата" },
            { key: "lawyerName", label: "Адвокат" },
            { key: "clientName", label: "Клиент" },
            { key: "caseTypeName", label: "Казус" },
            { key: "fee", label: "Хонорар" },
            { key: "payer", label: "Платец", type: "payer" },
            { key: "invoiceState", label: "Фактура", type: "invoice-state" }
        ],
        fields: [
            { key: "date", label: "Дата", type: "date", required: true },
            { key: "lawyerId", label: "Адвокат", type: "select", source: "lawyers", required: true, selfRole: "LAWYER" },
            { key: "clientId", label: "Клиент", type: "select", source: "clients", required: true },
            { key: "caseTypeId", label: "Вид казус", type: "select", source: "case-types", required: true },
            { key: "fee", label: "Хонорар (лв.)", type: "number", step: "0.01", required: true },
            { key: "description", label: "Описание", type: "text", full: true }
        ]
    },
    documents: {
        title: "Документи",
        endpoint: "/api/documents",
        writeRoles: ["ADMIN", "LAWYER"],
        rowOwnerField: "lawyerId",
        columns: [
            { key: "id", label: "ID" },
            { key: "title", label: "Заглавие" },
            { key: "clientName", label: "Клиент" },
            { key: "lawyerName", label: "Адвокат" },
            { key: "issueDate", label: "Издаден" },
            { key: "validityDays", label: "Валидност (дни)" }
        ],
        fields: [
            { key: "title", label: "Заглавие", type: "text", required: true },
            { key: "content", label: "Съдържание", type: "textarea", full: true },
            { key: "clientId", label: "Клиент", type: "select", source: "clients", required: true },
            { key: "lawyerId", label: "Адвокат", type: "select", source: "lawyers", required: true, selfRole: "LAWYER" },
            { key: "issueDate", label: "Дата на издаване", type: "date", required: true },
            { key: "validityDays", label: "Валидност (дни)", type: "number", step: "1", required: true }
        ]
    },
    appointments: {
        title: "Срещи",
        endpoint: "/api/appointments",
        writeRoles: ["ADMIN", "LAWYER", "CLIENT"],
        note: "Онлайн заявки за консултация. Клиентът вижда своите срещи; адвокатът вижда своите.",
        columns: [
            { key: "id", label: "ID" },
            { key: "scheduledAt", label: "Дата/час" },
            { key: "clientName", label: "Клиент" },
            { key: "lawyerName", label: "Адвокат" },
            { key: "topic", label: "Тема" },
            { key: "status", label: "Статус", type: "status" }
        ],
        fields: [
            { key: "clientId", label: "Клиент", type: "select", source: "clients", required: true, selfRole: "CLIENT" },
            { key: "lawyerId", label: "Адвокат", type: "select", source: "lawyers", required: true, selfRole: "LAWYER" },
            { key: "scheduledAt", label: "Дата и час", type: "datetime-local", required: true },
            { key: "status", label: "Статус", type: "select", options: ["REQUESTED", "CONFIRMED", "COMPLETED", "CANCELLED"], optionsByRole: { CLIENT: ["REQUESTED", "CANCELLED"] }, required: true },
            { key: "topic", label: "Тема", type: "text", required: true },
            { key: "notes", label: "Бележки", type: "textarea", full: true }
        ]
    },
    invoices: {
        title: "Фактури",
        endpoint: "/api/invoices",
        writeRoles: ["ADMIN", "LAWYER"],
        note: "MVP фактуриране: сумата и платецът се извеждат от избраната правна услуга. CSV/SAF-T Lite export са демонстрационни, не счетоводен compliance продукт.",
        columns: [
            { key: "id", label: "ID" },
            { key: "invoiceNumber", label: "№" },
            { key: "issueDate", label: "Дата" },
            { key: "dueDate", label: "Падеж" },
            { key: "clientName", label: "Клиент" },
            { key: "lawyerName", label: "Адвокат" },
            { key: "amount", label: "Сума" },
            { key: "payer", label: "Платец", type: "payer" },
            { key: "status", label: "Статус", type: "status" }
        ],
        fields: [
            { key: "invoiceNumber", label: "Номер (празно = автоматично)", type: "text" },
            { key: "legalServiceId", label: "Правна услуга", type: "select", source: "legal-services", required: true, ownLawyerOptions: true },
            { key: "issueDate", label: "Дата на издаване", type: "date", required: true },
            { key: "dueDate", label: "Падеж", type: "date", required: true },
            { key: "status", label: "Статус", type: "select", options: ["DRAFT", "ISSUED", "PAID", "CANCELLED"], required: true }
        ]
    }
};

function canWrite(cfg) { return cfg.writeRoles.includes(Session.role); }

/** Whether the current user may edit/delete a specific row (per-record ownership). */
function ownsRow(cfg, row) {
    if (!cfg.rowOwnerField) return true;          // no per-row ownership rule for this entity
    if (Session.role === "ADMIN") return true;    // admin manages everything
    if (Session.role === "LAWYER") return String(row[cfg.rowOwnerField]) === String(Session.lawyerId);
    return false;
}

/* ============================ Entity view (CRUD) ============================ */
async function renderEntity(key) {
    const cfg = ENTITIES[key];
    const content = $("#content");
    content.innerHTML = "";

    const formHost = el("div", { "data-entity-form": key });
    const head = el("div", { class: "panel-head" }, el("h2", {}, cfg.title));
    const headActions = el("div", { class: "head-actions" });
    if (canWrite(cfg)) {
        headActions.appendChild(el("button", { class: "small", onclick: () => openForm(key, formHost, null) }, "+ Добави"));
    }
    if (key === "documents" && canWrite(cfg)) {
        headActions.appendChild(el("button", {
            class: "small ghost",
            onclick: () => openDocumentDraftForm(formHost)
        }, "Създай чернова"));
    }
    if (key === "legal-services" && Session.role !== "CLIENT") {
        headActions.appendChild(el("button", { class: "small ghost", onclick: () => downloadFile("/api/invoices/export.csv", "legalcaseflow-invoices.csv") }, "CSV"));
        headActions.appendChild(el("button", { class: "small ghost", onclick: () => downloadFile("/api/invoices/saf-t-lite.csv", "legalcaseflow-saf-t-lite.csv") }, "SAF-T Lite"));
    }
    if (headActions.childNodes.length) head.appendChild(headActions);

    const panel = el("div", { class: "panel" }, head);
    if (cfg.note) panel.appendChild(el("p", { class: "panel-note" }, cfg.note));
    panel.appendChild(formHost);

    const tableHost = el("div", { "data-entity-table": key });
    panel.appendChild(tableHost);
    content.appendChild(panel);

    await reloadTable(key, tableHost, formHost);
}

async function refreshEntityTable(key) {
    const tableHost = document.querySelector('[data-entity-table="' + key + '"]');
    const formHost = document.querySelector('[data-entity-form="' + key + '"]');
    if (tableHost && formHost) await reloadTable(key, tableHost, formHost);
}

async function reloadTable(key, tableHost, formHost) {
    const cfg = ENTITIES[key];
    let rows;
    try {
        rows = await api("GET", cfg.endpoint);
        if (key === "legal-services") {
            const invoices = await api("GET", "/api/invoices");
            const invoicesByService = new Map();
            for (const invoice of invoices) {
                const serviceInvoices = invoicesByService.get(Number(invoice.legalServiceId)) || [];
                serviceInvoices.push(invoice);
                invoicesByService.set(Number(invoice.legalServiceId), serviceInvoices);
            }
            rows = rows.map(row => ({
                ...row,
                invoiceState: invoiceState(invoicesByService.get(Number(row.id)) || [])
            }));
        }
    } catch (e) {
        tableHost.innerHTML = "";
        tableHost.appendChild(el("p", { class: "muted" }, "Грешка при зареждане: " + e.message));
        return;
    }

    const writable = canWrite(cfg);
    const serviceInvoiceActions = key === "legal-services";
    const hasActions = writable || serviceInvoiceActions;
    const thead = el("tr", {}, ...cfg.columns.map(c => el("th", {}, c.label)));
    if (hasActions) thead.appendChild(el("th", {}, ""));

    const body = el("tbody");
    if (!rows.length) {
        const span = cfg.columns.length + (hasActions ? 1 : 0);
        body.appendChild(el("tr", {}, el("td", { colspan: span, class: "muted" }, "Няма записи.")));
    }
    for (const row of rows) {
        const tr = el("tr");
        for (const c of cfg.columns) tr.appendChild(renderCell(c, row[c.key]));
        if (hasActions) {
            const actions = el("td", { class: "actions" });
            const canManageInvoice = Session.role === "ADMIN"
                || Session.role === "CLIENT"
                || (Session.role === "LAWYER" && String(row.lawyerId) === String(Session.lawyerId));
            if (serviceInvoiceActions && canManageInvoice) {
                actions.appendChild(el("button", {
                    class: "small ghost",
                    onclick: () => openServiceInvoices(row, formHost)
                }, "Фактура"));
            }
            if (ownsRow(cfg, row)) {
                if (writable) {
                    actions.appendChild(el("button", { class: "small ghost", onclick: () => openForm(key, formHost, row) }, "Редакция"));
                    actions.appendChild(el("button", { class: "small danger", onclick: () => removeRow(key, row, tableHost, formHost) }, "Изтрий"));
                }
            }
            tr.appendChild(actions);
        }
        body.appendChild(tr);
    }

    tableHost.innerHTML = "";
    tableHost.appendChild(el("table", {}, el("thead", {}, thead), body));
}

function renderCell(col, value) {
    if (col.type === "bool") return el("td", {}, boolBadge(value));
    if (col.type === "account") {
        return el("td", {}, value
            ? el("span", { class: "badge yes" }, value)
            : el("span", { class: "badge no" }, "Няма акаунт"));
    }
    if (col.type === "status") return el("td", {}, el("span", { class: "badge status" }, statusLabel(value)));
    if (col.type === "invoice-state") {
        const state = value || { label: "Няма фактура", className: "no" };
        return el("td", {}, el("span", { class: "badge " + state.className }, state.label));
    }
    if (col.type === "payer") {
        return el("td", {}, el("span", { class: "badge payer-" + value }, value === "NBPP" ? "НБПП" : "Клиент"));
    }
    return el("td", {}, value === null || value === undefined ? "—" : String(value));
}

function canManageInvoiceForService(service) {
    return Session.role === "ADMIN"
        || (Session.role === "LAWYER" && String(service.lawyerId) === String(Session.lawyerId));
}

async function openServiceInvoices(service, host) {
    host.innerHTML = "";
    let invoices;
    try {
        invoices = (await api("GET", "/api/invoices"))
            .filter(invoice => String(invoice.legalServiceId) === String(service.id));
    } catch (e) {
        host.appendChild(el("p", { class: "muted" }, "Фактурите не могат да бъдат заредени: " + e.message));
        return;
    }

    const canManage = canManageInvoiceForService(service);
    const actions = el("div", { class: "head-actions" });
    if (canManage) {
        actions.appendChild(el("button", {
            class: "small",
            onclick: () => openInvoiceFormForService(service, host, null)
        }, "Нова фактура"));
    }
    actions.appendChild(el("button", {
        class: "small ghost",
        onclick: () => { host.innerHTML = ""; }
    }, "Затвори"));

    const workspace = el("section", { class: "service-invoice-workspace" },
        el("div", { class: "panel-head compact" },
            el("div", {},
                el("h3", {}, "Фактури · Услуга #" + service.id),
                el("p", { class: "muted" },
                    service.clientName + " · " + service.caseTypeName + " · " + service.fee + " лв.")),
            actions));

    if (!invoices.length) {
        workspace.appendChild(el("div", { class: "invoice-empty" },
            el("strong", {}, "Няма издадена фактура"),
            el("span", {}, canManage
                ? "Създайте фактура директно към тази правна услуга."
                : "Адвокатът все още не е издал фактура за услугата.")));
        host.appendChild(workspace);
        return;
    }

    const body = el("tbody");
    for (const invoice of invoices) {
        const rowActions = el("td", { class: "actions" },
            el("button", {
                class: "small ghost",
                onclick: () => renderInvoicePreview(invoice, service, host)
            }, "Преглед"));
        if (canManage) {
            if (invoice.status !== "PAID" && invoice.status !== "CANCELLED") {
                rowActions.appendChild(el("button", {
                    class: "small",
                    onclick: async () => {
                        try {
                            await api("PATCH", "/api/invoices/" + invoice.id + "/status?status=PAID");
                            toast("Фактурата е отбелязана като платена.", "ok");
                            await refreshEntityTable("legal-services");
                            await openServiceInvoices(service, host);
                        } catch (e) {
                            toast(e.message, "err");
                        }
                    }
                }, "Платена"));
            }
            rowActions.appendChild(el("button", {
                class: "small ghost",
                onclick: () => openInvoiceFormForService(service, host, invoice)
            }, "Редакция"));
            rowActions.appendChild(el("button", {
                class: "small danger",
                onclick: async () => {
                    if (!confirm("Да бъде ли изтрита фактура " + invoice.invoiceNumber + "?")) return;
                    try {
                        await api("DELETE", "/api/invoices/" + invoice.id);
                        toast("Фактурата е изтрита.", "ok");
                        await refreshEntityTable("legal-services");
                        await openServiceInvoices(service, host);
                    } catch (e) {
                        toast(e.message, "err");
                    }
                }
            }, "Изтрий"));
        }
        body.appendChild(el("tr", {},
            el("td", {}, invoice.invoiceNumber),
            el("td", {}, invoice.issueDate),
            el("td", {}, invoice.dueDate),
            el("td", {}, invoice.amount + " лв."),
            el("td", {}, el("span", { class: "badge status" }, statusLabel(invoice.status))),
            rowActions));
    }
    workspace.appendChild(el("div", { class: "invoice-table" },
        el("table", {},
            el("thead", {}, el("tr", {},
                el("th", {}, "№"),
                el("th", {}, "Дата"),
                el("th", {}, "Падеж"),
                el("th", {}, "Сума"),
                el("th", {}, "Статус"),
                el("th", {}, ""))),
            body)));
    host.appendChild(workspace);
}

function renderInvoicePreview(invoice, service, host) {
    const preview = el("section", { class: "service-invoice-workspace invoice-document" },
        el("div", { class: "invoice-document-head" },
            el("div", {},
                el("span", { class: "invoice-kicker" }, "Фактура"),
                el("h3", {}, invoice.invoiceNumber)),
            el("span", { class: "badge status" }, statusLabel(invoice.status))),
        el("div", { class: "invoice-parties" },
            el("div", {},
                el("small", {}, "Получател"),
                el("strong", {}, invoice.clientName),
                el("span", {}, "Платец: " + (invoice.payer === "NBPP" ? "НБПП" : "Клиент"))),
            el("div", {},
                el("small", {}, "Адвокат"),
                el("strong", {}, invoice.lawyerName),
                el("span", {}, "Издадена: " + invoice.issueDate),
                el("span", {}, "Падеж: " + invoice.dueDate))),
        el("table", { class: "invoice-lines" },
            el("thead", {}, el("tr", {},
                el("th", {}, "Услуга"),
                el("th", {}, "Казус"),
                el("th", {}, "Сума"))),
            el("tbody", {}, el("tr", {},
                el("td", {}, service.description || "Правна услуга #" + service.id),
                el("td", {}, service.caseTypeName),
                el("td", {}, invoice.amount + " лв.")))),
        el("div", { class: "invoice-total" },
            el("span", {}, "Общо"),
            el("strong", {}, invoice.amount + " лв.")),
        el("p", { class: "invoice-disclaimer" },
            "Демонстрационна фактура за MVP. Не представлява счетоводен или данъчен документ."),
        el("div", { class: "form-actions" },
            el("button", {
                class: "small ghost",
                onclick: () => openServiceInvoices(service, host)
            }, "Назад")));
    host.innerHTML = "";
    host.appendChild(preview);
}

function localDateValue(date) {
    const pad = value => String(value).padStart(2, "0");
    return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate());
}

async function openInvoiceFormForService(service, host, invoice) {
    const issueDate = new Date();
    const dueDate = new Date(issueDate);
    dueDate.setDate(dueDate.getDate() + 14);
    await openForm("invoices", host, invoice, {
        defaults: {
            legalServiceId: service.id,
            issueDate: localDateValue(issueDate),
            dueDate: localDateValue(dueDate),
            status: "DRAFT"
        },
        lockedFields: ["legalServiceId"],
        title: (invoice ? "Редакция на фактура" : "Нова фактура") + " · Услуга #" + service.id,
        onSaved: async () => {
            invalidateOptions();
            await refreshEntityTable("legal-services");
            await openServiceInvoices(service, host);
        },
        onCancel: () => openServiceInvoices(service, host)
    });
}

async function downloadFile(path, filename) {
    try {
        const headers = {};
        if (Session.token) headers["Authorization"] = "Bearer " + Session.token;
        const res = await fetch(path, { headers });
        if (!res.ok) throw new Error("Export failed: " + res.status);
        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        const link = el("a", { href: url, download: filename });
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(url);
    } catch (e) {
        toast(e.message, "err");
    }
}

async function removeRow(key, row, tableHost, formHost) {
    if (!confirm("Сигурен ли си, че искаш да изтриеш този запис?")) return;
    try {
        await api("DELETE", ENTITIES[key].endpoint + "/" + row.id);
        if (OPTION_SOURCES[key]) invalidateOptions();
        toast("Изтрито.", "ok");
        await reloadTable(key, tableHost, formHost);
    } catch (e) {
        toast(e.message, "err");
    }
}

/* ============================ Create / Edit form ============================ */
async function openForm(key, formHost, existing, formOptions = {}) {
    const cfg = ENTITIES[key];

    // preload select options
    for (const f of cfg.fields) {
        if (f.type === "select" && f.source) { try { await loadOptions(f.source); } catch (e) { /* ignore */ } }
    }

    const grid = el("div", { class: "form-grid" });
    const inputs = {};

    for (const f of cfg.fields) {
        const current = existing
            ? existing[f.key]
            : (formOptions.defaults ? formOptions.defaults[f.key] : undefined);
        let input;

        if (f.type === "checkbox") {
            input = el("input", { type: "checkbox" });
            if (current) input.checked = true;
            const wrap = el("label", { class: "checkbox-row" + (f.full ? " full" : "") }, input, document.createTextNode(f.label));
            grid.appendChild(wrap);
            inputs[f.key] = input;
            continue;
        }

        if (f.type === "select") {
            input = el("select");
            if (f.allowEmpty) input.appendChild(el("option", { value: "" }, "— няма —"));
            if (f.options) {
                const enumOptions = (f.optionsByRole && f.optionsByRole[Session.role]) || f.options;
                for (const value of enumOptions) {
                    const opt = el("option", { value }, value);
                    if (current !== undefined && current !== null && String(current) === String(value)) opt.selected = true;
                    input.appendChild(opt);
                }
            } else {
                let opts = optionCache[f.source] || [];
                // Only offer relations the backend will accept (e.g. a lawyer can invoice only own services).
                if (f.ownLawyerOptions && Session.role === "LAWYER" && Session.lawyerId != null) {
                    opts = opts.filter(o => String(o.lawyerId) === String(Session.lawyerId));
                }
                for (const o of opts) {
                    const opt = el("option", { value: o.id }, optionText(f.source, o));
                    if (current !== undefined && current !== null && String(current) === String(o.id)) opt.selected = true;
                    input.appendChild(opt);
                }
            }
            // Lock relation to the logged-in user's own profile (e.g. a lawyer can only book
            // appointments for themselves) so we never offer a choice the backend would reject.
            const lockToSelf = (f.selfRole && f.selfRole === Session.role)
                || (!existing && f.selfRoleOnCreate && f.selfRoleOnCreate === Session.role);
            if (lockToSelf) {
                const selfId = Session[f.key];
                if (selfId != null) input.value = String(selfId);
                input.disabled = true;
                input.title = "Автоматично зададено към вашия профил";
            }
            if ((formOptions.lockedFields || []).includes(f.key)) {
                input.disabled = true;
                input.title = "Фактурата е свързана с избраната услуга";
            }
        } else if (f.type === "textarea") {
            input = el("textarea", { rows: "3" });
            if (current !== undefined && current !== null) input.value = current;
        } else {
            const attrs = { type: f.type === "number" ? "number" : f.type };
            if (f.step) attrs.step = f.step;
            if (current !== undefined && current !== null) {
                attrs.value = f.type === "datetime-local" ? String(current).slice(0, 16) : current;
            }
            input = el("input", attrs);
        }

        inputs[f.key] = input;
        grid.appendChild(el("label", { class: f.full ? "full" : "" }, document.createTextNode(f.label), input));
    }

    let accountControls = null;
    const hasAccount = Boolean(existing && existing.username);
    const isAdmin = Session.role === "ADMIN";
    // New/account-less profiles: anyone who can manage the profile may provision an account.
    // Existing accounts: only an admin may edit the username/password.
    if (cfg.accountProvisioning && (!hasAccount || isAdmin)) {
        const enabled = el("input", { type: "checkbox" });
        enabled.checked = !hasAccount;
        const username = el("input", {
            type: "text", autocomplete: "off", minlength: "3", maxlength: "50",
            value: hasAccount ? existing.username : "", required: hasAccount ? null : "required"
        });
        const password = el("input", {
            type: "password", autocomplete: "new-password", minlength: "8", maxlength: "100",
            required: hasAccount ? null : "required"
        });
        const usernameLabel = el("label", {}, document.createTextNode("Потребителско име"), username);
        const passwordLabel = el("label", {},
            document.createTextNode(hasAccount ? "Нова парола (минимум 8 символа)" : "Начална парола (минимум 8 символа)"),
            password);
        usernameLabel.classList.toggle("hidden", !enabled.checked);
        passwordLabel.classList.toggle("hidden", !enabled.checked);

        enabled.addEventListener("change", () => {
            usernameLabel.classList.toggle("hidden", !enabled.checked);
            passwordLabel.classList.toggle("hidden", !enabled.checked);
            username.required = enabled.checked;
            password.required = enabled.checked;
        });

        const toggleLabel = hasAccount ? "Промени login акаунт" : (existing ? "Добави login акаунт" : "Създай login акаунт");
        grid.appendChild(el("label", { class: "checkbox-row full" },
            enabled, document.createTextNode(toggleLabel)));
        grid.appendChild(usernameLabel);
        grid.appendChild(passwordLabel);
        accountControls = { enabled, username, password };
    }

    const title = el("h3", {}, formOptions.title || ((existing ? "Редакция на " : "Нов запис · ") + cfg.title));
    const saveBtn = el("button", { class: "small" }, existing ? "Запази" : "Създай");
    const cancelBtn = el("button", { class: "small ghost", type: "button" }, "Отказ");

    const form = el("form", { class: "panel" }, title, grid,
        el("div", { class: "form-actions" }, saveBtn, cancelBtn));

    form.addEventListener("submit", async ev => {
        ev.preventDefault();
        const payload = collectPayload(cfg, inputs);
        if (payload === null) return;
        if (accountControls && accountControls.enabled.checked) {
            payload.account = {
                username: accountControls.username.value.trim(),
                password: accountControls.password.value
            };
        }
        try {
            const saved = existing
                ? await api("PUT", cfg.endpoint + "/" + existing.id, payload)
                : await api("POST", cfg.endpoint, payload);
            if (OPTION_SOURCES[key]) invalidateOptions();
            toast(existing ? "Запазено." : "Създадено.", "ok");
            if (formOptions.onSaved) {
                await formOptions.onSaved(saved);
            } else {
                formHost.innerHTML = "";
                await refreshEntityTable(key);
            }
        } catch (e) {
            toast(e.message, "err");
        }
    });
    cancelBtn.addEventListener("click", () => {
        if (formOptions.onCancel) formOptions.onCancel();
        else formHost.innerHTML = "";
    });

    formHost.innerHTML = "";
    formHost.appendChild(form);
}

function collectPayload(cfg, inputs) {
    const payload = {};
    for (const f of cfg.fields) {
        const input = inputs[f.key];
        let value;
        if (f.type === "checkbox") value = input.checked;
        else if (f.type === "number") value = input.value === "" ? null : Number(input.value);
        else if (f.type === "select") value = input.value === "" ? null : (f.options ? input.value : Number(input.value));
        else value = input.value.trim() === "" ? null : input.value.trim();

        if (f.required && (value === null || value === "")) {
            toast("Полето \"" + f.label + "\" е задължително.", "err");
            return null;
        }
        payload[f.key] = value;
    }
    return payload;
}

/* ============================ Reports ============================ */
const SIMPLE_REPORTS = [
    { title: "Най-чест вид казус", sub: "most-common-case-type", path: "/api/reports/most-common-case-type", render: "labeledOne" },
    { title: "Разпределение по вид казус", sub: "case-type-distribution", path: "/api/reports/case-type-distribution", render: "labeledTable", cols: ["Казус", "Брой"] },
    { title: "Клиенти на адвокат", sub: "clients-per-lawyer", path: "/api/reports/clients-per-lawyer", render: "labeledTable", cols: ["Адвокат", "Брой клиенти"] },
    { title: "Общо платено от клиенти", sub: "total-paid-by-clients", path: "/api/reports/total-paid-by-clients", render: "scalar", suffix: " лв." },
    { title: "Приход по адвокат", sub: "revenue-per-lawyer", path: "/api/reports/revenue-per-lawyer", render: "labeledTable", cols: ["Адвокат", "Приход (лв.)"] },
    { title: "Брой услуги по адвокат", sub: "service-count-per-lawyer", path: "/api/reports/service-count-per-lawyer", render: "labeledTable", cols: ["Адвокат", "Брой услуги"] },
    { title: "Документи по адвокат", sub: "documents-per-lawyer", path: "/api/reports/documents-per-lawyer", render: "labeledTable", cols: ["Адвокат", "Брой документи"] },
    { title: "Месец с най-много документи", sub: "month-with-most-documents", path: "/api/reports/month-with-most-documents", render: "labeledOne" }
];

async function renderReports() {
    const content = $("#content");
    content.innerHTML = "";
    content.appendChild(el("div", { class: "panel-head" }, el("h2", {}, "Справки")));

    const grid = el("div", { class: "reports-grid" });
    content.appendChild(grid);

    // parameterized reports
    grid.appendChild(reportClientsByCaseType());
    grid.appendChild(reportClientsByLeadLawyer());
    grid.appendChild(reportServicesByPeriod());
    grid.appendChild(reportClientHistory());

    // simple reports
    for (const r of SIMPLE_REPORTS) {
        const card = el("div", { class: "report-card" },
            el("h3", {}, r.title),
            el("p", { class: "sub" }, "GET /api/reports/" + r.sub));
        const host = el("div", {}, el("span", { class: "muted" }, "Зареждане…"));
        card.appendChild(host);
        grid.appendChild(card);
        loadSimpleReport(r, host);
    }
}

async function loadSimpleReport(r, host) {
    try {
        const data = await api("GET", r.path);
        host.innerHTML = "";
        host.appendChild(renderReportData(r, data));
    } catch (e) {
        host.innerHTML = "";
        host.appendChild(el("span", { class: "muted" }, e.message));
    }
}

function renderReportData(r, data) {
    if (r.render === "scalar") {
        return el("div", { class: "stat-big" }, (data === null ? "0" : String(data)) + (r.suffix || ""));
    }
    if (r.render === "labeledOne") {
        if (!data) return el("span", { class: "muted" }, "Няма данни.");
        return el("div", {}, el("span", { class: "stat-big" }, data.label), el("span", { class: "muted" }, "  ·  " + data.count));
    }
    // labeledTable
    if (!data || !data.length) return el("span", { class: "muted" }, "Няма данни.");
    const head = el("tr", {}, ...r.cols.map(c => el("th", {}, c)));
    const body = el("tbody", {}, ...data.map(row =>
        el("tr", {}, el("td", {}, String(row.label)), el("td", {}, String(row.count ?? row.amount)))));
    return el("table", {}, el("thead", {}, head), body);
}

function reportClientsByCaseType() {
    const card = el("div", { class: "report-card" },
        el("h3", {}, "Клиенти по вид казус"),
        el("p", { class: "sub" }, "GET /api/reports/clients-by-case-type/{id}"));
    const select = el("select");
    const host = el("div");
    const form = el("div", { class: "report-form" },
        el("label", {}, document.createTextNode("Казус"), select),
        el("button", { class: "small", onclick: run }, "Покажи"));
    card.appendChild(form);
    card.appendChild(host);

    loadOptions("case-types").then(opts => {
        for (const o of opts) select.appendChild(el("option", { value: o.id }, o.name));
    }).catch(() => {});

    async function run() {
        if (!select.value) return;
        try {
            const data = await api("GET", "/api/reports/clients-by-case-type/" + select.value);
            host.innerHTML = "";
            if (!data.length) { host.appendChild(el("span", { class: "muted" }, "Няма клиенти.")); return; }
            host.appendChild(el("table", {},
                el("thead", {}, el("tr", {}, el("th", {}, "Клиент"), el("th", {}, "Водещ адвокат"))),
                el("tbody", {}, ...data.map(c => el("tr", {},
                    el("td", {}, c.fullName), el("td", {}, c.leadLawyerName || "—"))))));
        } catch (e) { toast(e.message, "err"); }
    }
    return card;
}

function reportClientsByLeadLawyer() {
    const card = el("div", { class: "report-card" },
        el("h3", {}, "Клиенти на личен адвокат"),
        el("p", { class: "sub" }, "GET /api/reports/clients-by-lead-lawyer/{id}"));
    const select = el("select");
    const host = el("div");
    const form = el("div", { class: "report-form" },
        el("label", {}, document.createTextNode("Адвокат"), select),
        el("button", { class: "small", onclick: run }, "Покажи"));
    card.appendChild(form);
    card.appendChild(host);

    loadOptions("lawyers").then(opts => {
        for (const o of opts) select.appendChild(el("option", { value: o.id }, o.fullName));
    }).catch(() => {});

    async function run() {
        if (!select.value) return;
        try {
            const data = await api("GET", "/api/reports/clients-by-lead-lawyer/" + select.value);
            host.innerHTML = "";
            if (!data.length) { host.appendChild(el("span", { class: "muted" }, "Няма клиенти.")); return; }
            host.appendChild(el("table", {},
                el("thead", {}, el("tr", {}, el("th", {}, "Клиент"), el("th", {}, "ЕГН / идент."))),
                el("tbody", {}, ...data.map(c => el("tr", {},
                    el("td", {}, c.fullName), el("td", {}, c.identifier || "—"))))));
        } catch (e) { toast(e.message, "err"); }
    }
    return card;
}

function reportServicesByPeriod() {
    const card = el("div", { class: "report-card" },
        el("h3", {}, "Услуги по период"),
        el("p", { class: "sub" }, "GET /api/reports/services-by-period"));
    const from = el("input", { type: "date" });
    const to = el("input", { type: "date" });
    const host = el("div");
    const form = el("div", { class: "report-form" },
        el("label", {}, document.createTextNode("От"), from),
        el("label", {}, document.createTextNode("До"), to),
        el("button", { class: "small", onclick: run }, "Покажи"));
    card.appendChild(form);
    card.appendChild(host);

    async function run() {
        if (!from.value || !to.value) { toast("Избери период.", "err"); return; }
        try {
            const data = await api("GET", "/api/reports/services-by-period?from=" + from.value + "&to=" + to.value);
            host.innerHTML = "";
            if (!data.length) { host.appendChild(el("span", { class: "muted" }, "Няма услуги.")); return; }
            host.appendChild(el("table", {},
                el("thead", {}, el("tr", {}, el("th", {}, "Дата"), el("th", {}, "Адвокат"), el("th", {}, "Клиент"), el("th", {}, "Хонорар"))),
                el("tbody", {}, ...data.map(s => el("tr", {},
                    el("td", {}, s.date), el("td", {}, s.lawyerName), el("td", {}, s.clientName), el("td", {}, String(s.fee)))))));
        } catch (e) { toast(e.message, "err"); }
    }
    return card;
}

function reportClientHistory() {
    const card = el("div", { class: "report-card" },
        el("h3", {}, "История по клиент"),
        el("p", { class: "sub" }, "GET /api/reports/client-history/{id}"));
    const select = el("select");
    const host = el("div");
    const form = el("div", { class: "report-form" },
        el("label", {}, document.createTextNode("Клиент"), select),
        el("button", { class: "small", onclick: run }, "Покажи"));
    card.appendChild(form);
    card.appendChild(host);

    loadOptions("clients").then(opts => {
        for (const o of opts) select.appendChild(el("option", { value: o.id }, o.fullName));
    }).catch(() => {});

    async function run() {
        if (!select.value) return;
        try {
            const data = await api("GET", "/api/reports/client-history/" + select.value);
            host.innerHTML = "";
            if (!data.length) { host.appendChild(el("span", { class: "muted" }, "Няма услуги.")); return; }
            host.appendChild(el("table", {},
                el("thead", {}, el("tr", {}, el("th", {}, "Дата"), el("th", {}, "Казус"), el("th", {}, "Хонорар"), el("th", {}, "Платец"))),
                el("tbody", {}, ...data.map(s => el("tr", {},
                    el("td", {}, s.date), el("td", {}, s.caseTypeName), el("td", {}, String(s.fee)),
                    el("td", {}, s.payer === "NBPP" ? "НБПП" : "Клиент"))))));
        } catch (e) { toast(e.message, "err"); }
    }
    return card;
}

/* ============================ Document drafts ============================ */
async function openDocumentDraftForm(host) {
    await Promise.all([loadOptions("clients"), loadOptions("lawyers"), loadOptions("case-types")]);

    const client = selectFrom("clients");
    const lawyer = selectFrom("lawyers");
    if (Session.role === "LAWYER" && Session.lawyerId != null) {
        lawyer.value = String(Session.lawyerId);
        lawyer.disabled = true;
        lawyer.title = "Автоматично зададено към вашия профил";
    }
    const caseType = selectFrom("case-types");
    const templates = {
        POWER_OF_ATTORNEY: "Пълномощно",
        CLAIM_STATEMENT: "Искова молба",
        CONSULTATION_SUMMARY: "Резюме на консултация",
        LEGAL_AID_REQUEST: "Искане за правна помощ"
    };
    const template = el("select", {},
        ...Object.entries(templates).map(([value, label]) => el("option", { value }, label)));
    const facts = el("textarea", { rows: "5", placeholder: "Факти, документи, срокове, желания резултат..." });
    const title = el("input", { type: "text", required: "required" });
    const issueDate = el("input", { type: "date", value: localDateValue(new Date()), required: "required" });
    const validityDays = el("input", { type: "number", min: "1", step: "1", value: "30", required: "required" });
    const output = el("textarea", { class: "draft-output", rows: "15" });
    const result = el("section", { class: "draft-result hidden" },
        el("div", { class: "form-grid" },
            el("label", { class: "full" }, document.createTextNode("Заглавие"), title),
            el("label", {}, document.createTextNode("Дата на издаване"), issueDate),
            el("label", {}, document.createTextNode("Валидност (дни)"), validityDays),
            el("label", { class: "full draft-label" }, document.createTextNode("Съдържание"), output)),
        el("div", { class: "form-actions" },
            el("button", { class: "small", type: "button", onclick: saveDocument }, "Запази като документ")));

    const form = el("form", { class: "document-draft-workspace" },
        el("h3", {}, "Нова чернова на документ"),
        el("p", { class: "panel-note" }, "Генерираният текст е помощен. Прегледайте и редактирайте съдържанието преди запис."),
        el("div", { class: "form-grid" },
            el("label", {}, document.createTextNode("Клиент"), client),
            el("label", {}, document.createTextNode("Адвокат"), lawyer),
            el("label", {}, document.createTextNode("Казус"), caseType),
            el("label", {}, document.createTextNode("Шаблон"), template),
            el("label", { class: "full" }, document.createTextNode("Факти"), facts)
        ),
        el("div", { class: "form-actions" },
            el("button", { class: "small" }, "Генерирай"),
            el("button", {
                class: "small ghost",
                type: "button",
                onclick: () => { host.innerHTML = ""; }
            }, "Отказ")),
        result
    );

    form.addEventListener("submit", async ev => {
        ev.preventDefault();
        try {
            const draft = await api("POST", "/api/document-drafts", {
                clientId: Number(client.value),
                lawyerId: Number(lawyer.value),
                caseTypeId: Number(caseType.value),
                templateType: template.value,
                facts: facts.value.trim()
            });
            title.value = draft.title;
            output.value = draft.content;
            result.classList.remove("hidden");
            toast("Черновата е генерирана.", "ok");
        } catch (e) {
            toast(e.message, "err");
        }
    });

    async function saveDocument() {
        if (!title.value.trim() || !output.value.trim() || !issueDate.value || Number(validityDays.value) < 1) {
            toast("Попълнете заглавие, съдържание, дата и валидност.", "err");
            return;
        }
        try {
            await api("POST", "/api/documents", {
                title: title.value.trim(),
                content: output.value.trim(),
                clientId: Number(client.value),
                lawyerId: Number(lawyer.value),
                issueDate: issueDate.value,
                validityDays: Number(validityDays.value)
            });
            host.innerHTML = "";
            await refreshEntityTable("documents");
            toast("Черновата е запазена като документ.", "ok");
        } catch (e) {
            toast(e.message, "err");
        }
    }

    host.innerHTML = "";
    host.appendChild(form);
}

function selectFrom(source) {
    const select = el("select", { "data-option-source": source });
    for (const row of optionCache[source] || []) {
        select.appendChild(el("option", { value: row.id }, optionText(source, row)));
    }
    return select;
}

/* ============================ Chats ============================ */
function chatRoleLabel(role) {
    if (role === "ADMIN") return "Администратор";
    if (role === "LAWYER") return "Адвокат";
    return "Доверител";
}

let chatRenderSeq = 0;
async function renderChats(preferredConversationId = null) {
    const renderId = ++chatRenderSeq;
    const newChatHost = el("div");
    const head = el("div", { class: "panel-head" },
        el("h2", {}, "Чатове"),
        el("button", { class: "small", onclick: () => openNewChatForm(newChatHost) }, "Нов разговор"));

    let conversations;
    try {
        conversations = await api("GET", "/api/chats/conversations");
    } catch (e) {
        if (renderId !== chatRenderSeq) return;
        const content = $("#content");
        content.innerHTML = "";
        content.appendChild(el("div", { class: "panel" }, el("p", { class: "muted" }, e.message)));
        return;
    }
    if (renderId !== chatRenderSeq) return; // a newer render started — abandon this one

    let activeId = preferredConversationId || ChatState.activeConversationId;
    if (!conversations.some(c => String(c.id) === String(activeId))) {
        activeId = conversations.length ? conversations[0].id : null;
    }
    ChatState.activeConversationId = activeId;

    const list = el("div", { class: "chat-list" });
    if (!conversations.length) {
        list.appendChild(el("div", { class: "chat-list-empty" },
            el("strong", {}, "Няма разговори"),
            el("span", {}, "Започнете нов разговор.")));
    }
    for (const conversation of conversations) {
        const last = conversation.lastMessage;
        const preview = last ? last.content : "Нов разговор";
        const button = el("button", {
            class: "chat-thread" + (String(conversation.id) === String(activeId) ? " active" : ""),
            "data-search": (conversation.counterpartName + " " + preview).toLowerCase(),
            onclick: () => renderChats(conversation.id)
        },
        el("span", { class: "chat-avatar" }, initials(conversation.counterpartName)),
        el("span", { class: "chat-thread-main" },
            el("span", { class: "chat-thread-title" }, conversation.counterpartName),
            el("span", { class: "chat-thread-preview" }, preview)),
        el("span", { class: "chat-thread-meta" },
            el("time", {}, formatChatTime(conversation.lastActivityAt)),
            conversation.unreadCount
                ? el("span", { class: "chat-unread" }, conversation.unreadCount > 99 ? "99+" : String(conversation.unreadCount))
                : null));
        list.appendChild(button);
    }

    // Search/filter over conversations — shown whenever there is at least one chat.
    if (conversations.length >= 1) {
        const filterInput = el("input", {
            type: "search",
            class: "chat-search",
            placeholder: "Търси разговор по име…",
            style: "margin-bottom:8px",
            value: ChatState.listFilter || ""
        });
        const noMatch = el("div", { class: "chat-list-empty hidden" }, el("span", {}, "Няма съвпадение."));
        const applyChatFilter = () => {
            const term = filterInput.value.trim().toLowerCase();
            ChatState.listFilter = term;
            let visible = 0;
            list.querySelectorAll(".chat-thread").forEach(node => {
                const match = !term || (node.dataset.search || "").includes(term);
                node.classList.toggle("hidden", !match);
                if (match) visible++;
            });
            noMatch.classList.toggle("hidden", visible !== 0);
        };
        filterInput.addEventListener("input", applyChatFilter);
        list.prepend(noMatch);
        list.prepend(filterInput);
        applyChatFilter();
    } else {
        ChatState.listFilter = "";
    }

    const detail = el("section", { class: "chat-detail" });
    const layout = el("div", { class: "chat-layout panel" }, list, detail);
    if (activeId) {
        const active = conversations.find(c => String(c.id) === String(activeId));
        await renderChatDetail(active, detail);
        if (renderId !== chatRenderSeq) return; // a newer render won — don't paint stale UI
    } else {
        detail.appendChild(el("div", { class: "chat-empty" },
            el("strong", {}, "Изберете или започнете разговор"),
            el("span", {}, "Съобщенията се пазят като неизменяема история.")));
    }

    // Atomic swap after all awaits: clear + insert in one step, so two concurrent renders
    // (e.g. fired by rapid socket events) can never stack two chat panels.
    const content = $("#content");
    content.innerHTML = "";
    content.append(head, newChatHost, layout);
}

async function openNewChatForm(host) {
    host.innerHTML = "";
    let contacts;
    let conversations;
    try {
        [contacts, conversations] = await Promise.all([
            api("GET", "/api/chats/contacts"),
            api("GET", "/api/chats/conversations")
        ]);
    } catch (e) {
        toast(e.message, "err");
        return;
    }
    const existingCounterpartIds = new Set(conversations.map(conversation => Number(conversation.counterpartUserId)));
    const availableContacts = contacts.filter(contact => !existingCounterpartIds.has(Number(contact.userId)));
    const contactList = el("div", { class: "chat-contact-picker" });
    const panel = el("section", { class: "panel chat-new-panel" },
        el("div", { class: "panel-head compact" },
            el("div", {},
                el("h3", {}, "Нов разговор"),
                el("p", { class: "muted" }, "Изберете потребител с login акаунт.")),
            el("button", { class: "small ghost", type: "button", onclick: () => { host.innerHTML = ""; } }, "Отказ")),
        contactList);

    for (const contact of availableContacts) {
        const roleLabel = chatRoleLabel(contact.role);
        const button = el("button", {
            class: "chat-contact-option",
            type: "button",
            onclick: async () => {
                const buttons = contactList.querySelectorAll("button");
                buttons.forEach(item => { item.disabled = true; });
                try {
                    const conversation = await api("POST", "/api/chats/conversations", {
                        counterpartUserId: Number(contact.userId)
                    });
                    ChatState.activeConversationId = conversation.id;
                    await renderChats(conversation.id);
                    toast("Разговорът с " + contact.displayName + " е създаден.", "ok");
                } catch (e) {
                    buttons.forEach(item => { item.disabled = false; });
                    toast(e.message, "err");
                }
            }
        },
        el("span", { class: "chat-avatar" }, initials(contact.displayName)),
        el("span", { class: "chat-contact-main" },
            el("strong", {}, contact.displayName),
            el("small", {}, roleLabel)),
        el("span", { class: "chat-contact-action" }, "Започни"));
        contactList.appendChild(button);
    }

    if (!availableContacts.length) {
        const message = contacts.length
            ? "Вече имате разговор с всички налични потребители."
            : "Няма налични потребители с login акаунт.";
        contactList.appendChild(el("p", { class: "muted chat-contact-empty" }, message));
    }
    host.appendChild(panel);
}

async function renderChatDetail(conversation, detail) {
    detail.innerHTML = "";
    const mobileBack = el("button", { class: "small ghost chat-back", onclick: () => {
        ChatState.activeConversationId = null;
        detail.parentElement.classList.remove("show-detail");
    } }, "Назад");
    const header = el("header", { class: "chat-detail-head" },
        mobileBack,
        el("span", { class: "chat-avatar" }, initials(conversation.counterpartName)),
        el("span", {},
            el("strong", {}, conversation.counterpartName),
            el("small", {}, chatRoleLabel(conversation.counterpartRole))));
    const messagesHost = el("div", { class: "chat-messages" });
    const composer = el("form", { class: "chat-composer" });
    const input = el("textarea", {
        rows: "2", maxlength: "2000", placeholder: "Напишете съобщение...", required: "required"
    });
    const send = el("button", { type: "submit", title: "Изпрати" }, "Изпрати");
    composer.append(input, send);
    detail.append(header, messagesHost, composer);
    detail.parentElement.classList.add("show-detail");

    if (conversation.unreadCount > 0) {
        await api("POST", "/api/chats/conversations/" + conversation.id + "/read");
        conversation.unreadCount = 0;
        await refreshChatUnread();
    }
    const messages = await api("GET", "/api/chats/conversations/" + conversation.id + "/messages?limit=50");
    renderMessagePage(messagesHost, messages, true);
    if (messages.length === 50) {
        const older = el("button", { class: "small ghost chat-older" }, "По-стари съобщения");
        older.addEventListener("click", async () => {
            const first = messagesHost.querySelector("[data-message-id]");
            if (!first) return;
            const page = await api("GET", "/api/chats/conversations/" + conversation.id
                + "/messages?limit=50&beforeId=" + first.dataset.messageId);
            renderMessagePage(messagesHost, page, false);
            if (page.length < 50) older.remove();
        });
        messagesHost.prepend(older);
    }
    messagesHost.scrollTop = messagesHost.scrollHeight;

    composer.addEventListener("submit", async event => {
        event.preventDefault();
        const content = input.value.trim();
        if (!content) return;
        send.disabled = true;
        try {
            await api("POST", "/api/chats/conversations/" + conversation.id + "/messages", { content });
            input.value = "";
            await renderChats(conversation.id);
        } catch (e) {
            toast(e.message, "err");
            send.disabled = false;
        }
    });
    input.addEventListener("keydown", event => {
        if (event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            composer.requestSubmit();
        }
    });
}

function renderMessagePage(host, messages, append) {
    const fragment = document.createDocumentFragment();
    for (const message of messages) fragment.appendChild(renderChatMessage(message));
    if (append) host.appendChild(fragment);
    else {
        const anchor = host.querySelector("[data-message-id]");
        host.insertBefore(fragment, anchor);
    }
}

function renderChatMessage(message) {
    return el("article", {
        class: "chat-message " + (message.own ? "own" : "received"),
        "data-message-id": message.id
    },
    el("div", { class: "chat-bubble" }, message.content),
    el("div", { class: "chat-message-meta" },
        formatChatTime(message.sentAt),
        message.own ? (message.readAt ? " · Прочетено" : " · Изпратено") : ""));
}

function initials(name) {
    return String(name || "?").split(/\s+/).slice(0, 2).map(part => part.charAt(0)).join("").toUpperCase();
}

function formatChatTime(value) {
    if (!value) return "";
    const date = new Date(value);
    const today = new Date();
    return date.toDateString() === today.toDateString()
        ? date.toLocaleTimeString("bg-BG", { hour: "2-digit", minute: "2-digit" })
        : date.toLocaleDateString("bg-BG", { day: "2-digit", month: "2-digit" });
}

/* ============================ Search ============================ */
let searchDebounceTimer;
let searchRequestSequence = 0;

async function renderSearch() {
    const content = $("#content");
    content.innerHTML = "";
    content.appendChild(el("div", { class: "panel-head" }, el("h2", {}, "Търсене")));

    const query = el("input", { type: "search", placeholder: "razvod, неплатени фактури, искова молб, NBPP..." });
    const searchTypes = [
        { value: "all", label: "Всичко" },
        { value: "clients", label: "Доверители", roles: ["ADMIN", "LAWYER"] },
        { value: "lawyers", label: "Адвокати", roles: ["ADMIN"] },
        { value: "case-types", label: "Казуси", roles: ["ADMIN", "LAWYER"] },
        { value: "legal-services", label: "Услуги" },
        { value: "documents", label: "Документи" },
        { value: "appointments", label: "Срещи" },
        { value: "invoices", label: "Фактури" }
    ];
    const type = el("select", {},
        ...searchTypes
            .filter(option => !option.roles || option.roles.includes(Session.role))
            .map(option => el("option", { value: option.value }, option.label)));
    const host = el("div", { class: "search-results" });

    const form = el("form", { class: "panel search-panel" },
        el("p", { class: "smart-note" }, "Резултатите се показват автоматично след втория въведен символ."),
        el("div", { class: "search-form" },
            el("label", {}, document.createTextNode("Заявка"), query),
            el("label", {}, document.createTextNode("Тип"), type),
            el("button", {}, "Търси")
        )
    );

    form.addEventListener("submit", async ev => {
        ev.preventDefault();
        clearTimeout(searchDebounceTimer);
        await triggerLiveSearch(query, type, host, true);
    });

    content.appendChild(form);
    content.appendChild(host);
    renderSearchPrompt(host);

    query.addEventListener("input", () => {
        clearTimeout(searchDebounceTimer);
        const q = query.value.trim();
        const requestId = ++searchRequestSequence;
        if (q.length < 2) {
            renderSearchPrompt(host);
            return;
        }
        searchDebounceTimer = setTimeout(() => runSearch(q, type.value, host, requestId), 220);
    });

    type.addEventListener("change", () => {
        clearTimeout(searchDebounceTimer);
        triggerLiveSearch(query, type, host, false);
    });

    query.focus();
}

async function triggerLiveSearch(queryInput, typeInput, host, showValidation) {
    const q = queryInput.value.trim();
    if (q.length < 2) {
        renderSearchPrompt(host);
        if (showValidation) toast("Въведи поне 2 символа.", "err");
        return;
    }
    const requestId = ++searchRequestSequence;
    await runSearch(q, typeInput.value, host, requestId);
}

function renderSearchPrompt(host) {
    host.innerHTML = "";
    host.appendChild(el("div", { class: "search-empty" },
        el("span", { class: "search-empty-icon" }, "⌕"),
        el("p", {}, "Напиши поне 2 символа, например „Ма“.")));
}

async function runSearch(query, type, host, requestId) {
    host.innerHTML = "";
    host.appendChild(el("p", { class: "muted" }, "Търсене..."));
    try {
        const params = new URLSearchParams({ q: query, type, limit: "25" });
        const data = await api("GET", "/api/search?" + params.toString());
        if (requestId !== searchRequestSequence) return;
        renderSearchResults(data, host);
    } catch (e) {
        if (requestId !== searchRequestSequence) return;
        host.innerHTML = "";
        host.appendChild(el("div", { class: "panel" }, el("p", { class: "muted" }, e.message)));
    }
}

function renderSearchResults(data, host) {
    host.innerHTML = "";
    const summary = el("div", { class: "search-summary" },
        el("span", {}, "Резултати: " + data.results.length + " от " + data.total),
        el("span", { class: "muted" }, "за \"" + data.query + "\""));
    host.appendChild(summary);

    if (data.interpretedTerms && data.interpretedTerms.length) {
        host.appendChild(el("div", { class: "search-terms" },
            el("span", { class: "muted" }, "Разчетено като:"),
            ...data.interpretedTerms.map(term => el("span", { class: "term-chip" }, term))));
    }

    if (!data.results.length) {
        host.appendChild(el("div", { class: "panel" }, el("p", { class: "muted" }, "Няма намерени записи.")));
        return;
    }

    for (const item of data.results) {
        const card = el("div", { class: "search-result" },
            el("div", { class: "search-result-main" },
                el("div", { class: "search-meta" },
                    el("span", { class: "badge status" }, item.entityLabel),
                    el("span", { class: "score" }, "score " + item.score),
                    el("span", { class: "score" }, item.reason || "smart match")
                ),
                el("h3", {}, item.title),
                el("p", { class: "muted" }, item.subtitle),
                el("p", {}, item.snippet),
                item.matchedTerms && item.matchedTerms.length
                    ? el("div", { class: "matched-terms" }, ...item.matchedTerms.map(term => el("span", { class: "term-chip" }, term)))
                    : null
            ),
            el("button", { class: "small ghost", onclick: () => openSearchResult(item) }, "Отвори")
        );
        host.appendChild(card);
    }
}

async function openSearchResult(item) {
    if (item.route !== "invoices") {
        await navigate(item.route);
        return;
    }
    try {
        const invoice = await api("GET", "/api/invoices/" + item.id);
        const service = await api("GET", "/api/legal-services/" + invoice.legalServiceId);
        await navigate("legal-services");
        const host = document.querySelector('[data-entity-form="legal-services"]');
        if (host) await openServiceInvoices(service, host);
    } catch (e) {
        toast(e.message, "err");
    }
}

/* ============================ Audit log ============================ */
const AuditState = { page: 0, size: 50, filters: {} };

async function renderAudit() {
    const content = $("#content");
    content.innerHTML = "";
    content.appendChild(el("div", { class: "panel-head" }, el("h2", {}, "Audit log")));

    const actor = el("input", { type: "search", placeholder: "Потребител", value: AuditState.filters.actor || "" });
    const role = auditSelect(["", "ADMIN", "LAWYER", "CLIENT"], AuditState.filters.role);
    const action = auditSelect(["", "CREATE", "UPDATE", "DELETE", "RESTORE", "ACCOUNT_CREATED",
        "ACCOUNT_DEACTIVATED", "ACCOUNT_REACTIVATED", "CHAT_CONVERSATION_CREATED",
        "CHAT_MESSAGE_SENT", "CHAT_MESSAGES_READ", "LOGIN_SUCCESS", "LOGIN_FAILURE", "ACCESS_DENIED"],
        AuditState.filters.action);
    const resource = el("input", { type: "search", placeholder: "напр. documents", value: AuditState.filters.resource || "" });
    const outcome = auditSelect(["", "SUCCESS", "FAILURE", "DENIED"], AuditState.filters.outcome);
    const from = el("input", { type: "datetime-local", value: AuditState.filters.from || "" });
    const to = el("input", { type: "datetime-local", value: AuditState.filters.to || "" });
    const form = el("form", { class: "panel audit-filters" },
        el("label", {}, document.createTextNode("Потребител"), actor),
        el("label", {}, document.createTextNode("Роля"), role),
        el("label", {}, document.createTextNode("Действие"), action),
        el("label", {}, document.createTextNode("Ресурс"), resource),
        el("label", {}, document.createTextNode("Резултат"), outcome),
        el("label", {}, document.createTextNode("От"), from),
        el("label", {}, document.createTextNode("До"), to),
        el("div", { class: "form-actions" },
            el("button", { class: "small" }, "Филтрирай"),
            el("button", { class: "small ghost", type: "button", onclick: () => {
                AuditState.filters = {};
                AuditState.page = 0;
                renderAudit();
            } }, "Изчисти")));
    form.addEventListener("submit", ev => {
        ev.preventDefault();
        AuditState.filters = {
            actor: actor.value.trim(), role: role.value, action: action.value,
            resource: resource.value.trim(), outcome: outcome.value, from: from.value, to: to.value
        };
        AuditState.page = 0;
        renderAudit();
    });
    content.appendChild(form);

    const params = new URLSearchParams({ page: AuditState.page, size: AuditState.size });
    for (const [key, value] of Object.entries(AuditState.filters)) {
        if (!value) continue;
        params.set(key, (key === "from" || key === "to") ? new Date(value).toISOString() : value);
    }
    const data = await api("GET", "/api/audit-events?" + params.toString());
    const table = el("table", {},
        el("thead", {}, el("tr", {},
            ...["Време", "Потребител", "Действие", "Ресурс", "Резултат", ""].map(label => el("th", {}, label)))),
        el("tbody", {}, ...data.content.map(event => el("tr", {},
            el("td", {}, new Date(event.occurredAt).toLocaleString("bg-BG")),
            el("td", {}, (event.actorUsername || "system") + (event.actorRole ? " · " + event.actorRole : "")),
            el("td", {}, el("span", { class: "badge status" }, event.action)),
            el("td", {}, event.resourceType + (event.resourceId == null ? "" : " #" + event.resourceId)),
            el("td", {}, el("span", { class: "badge " + (event.outcome === "SUCCESS" ? "yes" : "no") }, event.outcome)),
            el("td", { class: "actions" },
                el("button", { class: "small ghost", onclick: () => showAuditDetail(event.id) }, "Детайли"),
                event.restorable
                    ? el("button", { class: "small", onclick: () => restoreAuditEvent(event.id) }, "Възстанови")
                    : null)))));
    content.appendChild(el("div", { class: "panel audit-table" }, table,
        el("div", { class: "audit-pagination" },
            el("button", { class: "small ghost", disabled: data.first ? "disabled" : null,
                onclick: () => { AuditState.page--; renderAudit(); } }, "Назад"),
            el("span", { class: "muted" }, "Страница " + (data.number + 1) + " от " + Math.max(1, data.totalPages)
                + " · " + data.totalElements + " събития"),
            el("button", { class: "small ghost", disabled: data.last ? "disabled" : null,
                onclick: () => { AuditState.page++; renderAudit(); } }, "Напред"))));
}

function auditSelect(values, selected) {
    return el("select", {}, ...values.map(value => {
        const option = el("option", { value }, value || "Всички");
        if (value === selected) option.selected = true;
        return option;
    }));
}

async function showAuditDetail(id) {
    const event = await api("GET", "/api/audit-events/" + id);
    const content = $("#content");
    const existing = document.querySelector(".audit-detail");
    if (existing) existing.remove();
    const detail = el("div", { class: "panel audit-detail" },
        el("div", { class: "panel-head" },
            el("h3", {}, event.action + " · " + event.resourceType + (event.resourceId == null ? "" : " #" + event.resourceId)),
            event.restorable ? el("button", { class: "small", onclick: () => restoreAuditEvent(event.id) }, "Възстанови") : null),
        event.metadata ? el("div", {}, el("h4", {}, "Метаданни"), el("pre", {}, auditPretty(event.metadata))) : null,
        el("div", { class: "audit-snapshots" },
            el("div", {}, el("h4", {}, "Преди"), el("pre", {}, auditPretty(event.beforeState))),
            el("div", {}, el("h4", {}, "След"), el("pre", {}, auditPretty(event.afterState)))));
    content.insertBefore(detail, content.children[2] || null);
}

function auditPretty(value) {
    if (!value) return "—";
    try { return JSON.stringify(JSON.parse(value), null, 2); } catch (e) { return value; }
}

async function restoreAuditEvent(id) {
    if (!confirm("Да бъде ли възстановена тази промяна?")) return;
    try {
        await api("POST", "/api/audit-events/" + id + "/restore", {});
        toast("Промяната е възстановена.", "ok");
        await renderAudit();
    } catch (e) {
        toast(e.message, "err");
    }
}

/* ============================ Navigation ============================ */
const TABS = [
    { key: "search", label: "Търсене" },
    { key: "chats", label: "Чатове", roles: ["CLIENT", "LAWYER", "ADMIN"] },
    { key: "clients", label: "Доверители", roles: ["ADMIN", "LAWYER"] },
    { key: "lawyers", label: "Адвокати", roles: ["ADMIN"] },
    { key: "case-types", label: "Казуси", roles: ["ADMIN", "LAWYER"] },
    { key: "legal-services", label: "Услуги" },
    { key: "documents", label: "Документи" },
    { key: "appointments", label: "Срещи" },
    { key: "reports", label: "Справки", roles: ["ADMIN", "LAWYER"] },
    { key: "audit", label: "Audit log", roles: ["ADMIN"] }
];

const ICONS = {
    search: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 5 5"/></svg>`,
    chats: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M5 18.5 3.5 21v-5A8 8 0 1 1 7 18.5z"/><path d="M8 10h8M8 14h5"/></svg>`,
    clients: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="9" cy="8" r="3.2"/><path d="M3.5 19c.5-3 2.8-4.6 5.5-4.6S14 16 14.5 19"/><path d="M16 5.2a3 3 0 0 1 0 5.6M18 19c-.3-1.9-1.2-3.2-2.5-4"/></svg>`,
    lawyers: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.2v17M6.5 20.2h11M5 7.2h14"/><path d="M5 7.2 2.7 12a2.3 2.3 0 0 0 4.6 0L5 7.2zM19 7.2 16.7 12a2.3 2.3 0 0 0 4.6 0L19 7.2z"/><path d="M12 4 5 7.2M12 4l7 3.2"/></svg>`,
    "case-types": `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M4 4h7l9 9-7 7-9-9V4z"/><circle cx="8" cy="8" r="1.3"/></svg>`,
    "legal-services": `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="7.5" width="18" height="12" rx="2"/><path d="M8 7.5V6a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v1.5M3 13h18"/></svg>`,
    documents: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4M9 12.5h6M9 16h6"/></svg>`,
    appointments: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="5" width="16" height="15" rx="2"/><path d="M8 3v4M16 3v4M4 10h16M8 14h3M8 17h6"/></svg>`,
    reports: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20V10M10 20V4M16 20v-7M21 20H3"/></svg>`,
    audit: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3 5 6v5c0 4.8 2.8 8.2 7 10 4.2-1.8 7-5.2 7-10V6z"/><path d="M9 11.5 11 13.5 15.5 9"/></svg>`
};

function buildNav() {
    const nav = $("#nav");
    nav.innerHTML = "";
    for (const tab of visibleTabs()) {
        const btn = el("button", { onclick: () => navigate(tab.key) });
        btn.innerHTML = (ICONS[tab.key] || "") + "<span>" + tab.label + "</span>";
        if (tab.key === "chats") {
            btn.appendChild(el("span", {
                class: "nav-unread" + (ChatState.unreadCount ? "" : " hidden"),
                "data-chat-unread": "true"
            }, ChatState.unreadCount > 99 ? "99+" : String(ChatState.unreadCount)));
        }
        nav.appendChild(btn);
    }
}

function visibleTabs() {
    return TABS.filter(tab => !tab.roles || tab.roles.includes(Session.role));
}

async function navigate(key) {
    const tabs = visibleTabs();
    if (!tabs.some(tab => tab.key === key)) key = "search";
    activeTabKey = key;
    document.querySelectorAll("#nav button").forEach((b, i) => {
        b.classList.toggle("active", tabs[i].key === key);
    });
    invalidateOptions();
    try {
        if (key === "search") await renderSearch();
        else if (key === "chats") await renderChats();
        else if (key === "reports") await renderReports();
        else if (key === "audit") await renderAudit();
        else await renderEntity(key);
    } catch (e) {
        toast(e.message, "err");
    }
}

/* ============================ Auth flow ============================ */
function showApp() {
    $("#login-view").classList.add("hidden");
    $("#app-view").classList.remove("hidden");
    const userLabel = $("#user-label");
    userLabel.textContent = "Влязъл като ";
    userLabel.appendChild(el("b", {}, Session.user));
    userLabel.appendChild(document.createTextNode(" · " + Session.role));
    buildNav();
    connectAppSocket();
    connectChatSocket();
    refreshChatUnread();
    navigate("search");
}

function showLogin() {
    $("#app-view").classList.add("hidden");
    $("#login-view").classList.remove("hidden");
}

function logout() {
    closeAppSocket();
    closeChatSocket();
    Session.clear();
    invalidateOptions();
    showLogin();
}

$("#login-form").addEventListener("submit", async ev => {
    ev.preventDefault();
    $("#login-error").textContent = "";
    try {
        const auth = await api("POST", "/api/auth/login", {
            username: $("#login-username").value,
            password: $("#login-password").value
        });
        Session.save(auth);
        showApp();
    } catch (e) {
        $("#login-error").textContent = e.message;
    }
});

$("#logout-btn").addEventListener("click", logout);

/* ============================ Boot ============================ */
if (Session.token) showApp();
else showLogin();
