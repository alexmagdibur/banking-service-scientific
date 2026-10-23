'use strict';

const API = '/api';
const REQUEST_TIMEOUT_MS = 8000;
const MAX_AMOUNT_MINOR = 1000000;
const PENDING_STORAGE_KEY = 'bank.pendingTransfer';

const $ = (id) => document.getElementById(id);

let accounts = [];
let busy = false;
// Transfer request whose result the client does not know yet: {key, fromAccountId, toAccountId, amountMinor}.
// It is kept (also in localStorage) so that a retry reuses the same Idempotency-Key and cannot debit twice.
let pending = loadPending();

// ---------- small helpers ----------

function loadPending() {
    try {
        const raw = localStorage.getItem(PENDING_STORAGE_KEY);
        return raw ? JSON.parse(raw) : null;
    } catch (e) {
        return null;
    }
}

function savePending(value) {
    pending = value;
    try {
        if (value) {
            localStorage.setItem(PENDING_STORAGE_KEY, JSON.stringify(value));
        } else {
            localStorage.removeItem(PENDING_STORAGE_KEY);
        }
    } catch (e) {
        // Storage can be unavailable; the in-memory value still protects the current page.
    }
}

function newKey() {
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** Rubles typed by the user -> integer kopecks, using only integer arithmetic (no float rounding). */
function parseRubles(text) {
    const normalized = text.replace(/\s/g, '').replace(',', '.');
    const match = /^(\d{1,9})(?:\.(\d{1,2}))?$/.exec(normalized);
    if (!match) {
        return null;
    }
    return Number(match[1]) * 100 + Number((match[2] || '').padEnd(2, '0'));
}

/** Integer kopecks -> "1 234,05 ₽". */
function formatMinor(minor) {
    const rubles = Math.floor(minor / 100);
    const kopecks = String(minor % 100).padStart(2, '0');
    return `${rubles.toLocaleString('ru-RU')},${kopecks} ₽`;
}

function formatTime(iso) {
    const date = new Date(iso);
    return Number.isNaN(date.getTime()) ? iso : date.toLocaleString('ru-RU');
}

function accountLabel(id) {
    const account = accounts.find((a) => a.id === id);
    return account ? `${account.displayName} (${id})` : String(id);
}

function cell(text, className) {
    const td = document.createElement('td');
    td.textContent = text;
    if (className) {
        td.className = className;
    }
    return td;
}

function emptyRow(columns, text) {
    const tr = document.createElement('tr');
    const td = cell(text, 'empty');
    td.colSpan = columns;
    tr.appendChild(td);
    return tr;
}

function setMessage(kind, text) {
    const box = $('message');
    box.className = `message ${kind}`;
    box.textContent = text;
    box.hidden = false;
}

function clearMessage() {
    $('message').hidden = true;
}

// ---------- HTTP ----------

class Unreachable extends Error {
    constructor(reason) {
        super(reason);
        this.reason = reason; // 'timeout' | 'network'
    }
}

/** Returns {status, data}; throws Unreachable when no response was received (timeout or network failure). */
async function api(path, options = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
        const response = await fetch(API + path, {...options, signal: controller.signal});
        let data = null;
        try {
            data = await response.json();
        } catch (e) {
            // empty or non-JSON body
        }
        return {status: response.status, data};
    } catch (e) {
        throw new Unreachable(e.name === 'AbortError' ? 'timeout' : 'network');
    } finally {
        clearTimeout(timer);
    }
}

const ERROR_TEXTS = {
    INSUFFICIENT_FUNDS: 'Недостаточно средств на счёте отправителя. Перевод не выполнен, балансы не изменились.',
    ACCOUNT_NOT_FOUND: 'Такого счёта нет. Перевод не выполнен.',
    IDEMPOTENCY_KEY_CONFLICT: 'Ключ этого запроса уже использован для другого перевода. Перевод не выполнен.',
};

function describeError(status, data) {
    if (data && ERROR_TEXTS[data.code]) {
        return ERROR_TEXTS[data.code];
    }
    if (status === 400) {
        return `Некорректный запрос: ${data && data.message ? data.message : 'проверьте введённые данные'}. Перевод не выполнен.`;
    }
    if (status === 404) {
        return 'Не найдено. Перевод не выполнен.';
    }
    if (status === 409) {
        return 'Конфликт: запрос отклонён сервером. Перевод не выполнен.';
    }
    return `Сервер ответил кодом ${status}.`;
}

// ---------- data loading and rendering ----------

async function loadAccounts() {
    const {status, data} = await api('/accounts');
    if (status !== 200 || !Array.isArray(data)) {
        throw new Error(`HTTP ${status}`);
    }
    accounts = data;
    renderAccounts();
}

async function loadTransfers() {
    const {status, data} = await api('/transfers?limit=20');
    if (status !== 200 || !Array.isArray(data)) {
        throw new Error(`HTTP ${status}`);
    }
    renderTransfers(data);
}

async function refreshAll() {
    const results = await Promise.allSettled([loadAccounts(), loadTransfers()]);
    // The transfer history shows account names, so draw it again once the accounts are known.
    if (results[0].status === 'fulfilled' && results[1].status === 'fulfilled') {
        await loadTransfers();
    }
    const failed = results.find((r) => r.status === 'rejected');
    if (failed) {
        setMessage('error', 'Не удалось загрузить данные с сервера. Показано последнее известное состояние; нажмите «Обновить».');
    }
}

function renderAccounts() {
    const body = $('accounts-body');
    body.replaceChildren();
    if (accounts.length === 0) {
        body.appendChild(emptyRow(3, 'Счетов нет'));
    }
    for (const account of accounts) {
        const tr = document.createElement('tr');
        tr.append(cell(account.id), cell(account.displayName), cell(formatMinor(account.balanceMinor), 'num'));
        body.appendChild(tr);
    }
    fillSelect($('from-account'), 0);
    fillSelect($('to-account'), 1);
}

function fillSelect(select, defaultIndex) {
    const previous = select.value;
    select.replaceChildren();
    for (const account of accounts) {
        const option = document.createElement('option');
        option.value = String(account.id);
        option.textContent = `${account.displayName} (${account.id})`;
        select.appendChild(option);
    }
    if (previous && accounts.some((a) => String(a.id) === previous)) {
        select.value = previous;
    } else if (accounts[defaultIndex]) {
        select.value = String(accounts[defaultIndex].id);
    }
}

function renderTransfers(transfers) {
    const body = $('transfers-body');
    body.replaceChildren();
    if (transfers.length === 0) {
        body.appendChild(emptyRow(5, 'Переводов пока нет'));
    }
    for (const t of transfers) {
        const tr = document.createElement('tr');
        tr.append(cell(formatTime(t.createdAt)), cell(accountLabel(t.fromAccountId)), cell(accountLabel(t.toAccountId)),
            cell(formatMinor(t.amountMinor), 'num'), cell(t.status));
        body.appendChild(tr);
    }
}

function renderPending() {
    const box = $('pending');
    if (!pending || busy) {
        box.hidden = true;
        return;
    }
    $('pending-text').textContent =
        `Результат перевода неизвестен: ${accountLabel(pending.fromAccountId)} → ${accountLabel(pending.toAccountId)}, ` +
        `${formatMinor(pending.amountMinor)} (ключ …${pending.key.slice(-6)}). ` +
        'Перевод мог выполниться или нет. «Проверить результат» повторит тот же запрос с тем же ключом: ' +
        'если перевод уже выполнен, второго списания не будет. Если изменить данные формы и нажать «Перевести», ' +
        'прежний запрос будет забыт.';
    box.hidden = false;
}

function setBusy(value) {
    busy = value;
    $('submit-transfer').disabled = value;
    $('check-result').disabled = value;
    $('forget-pending').disabled = value;
    renderPending();
}

// ---------- transfer ----------

function readForm() {
    const fromAccountId = Number($('from-account').value);
    const toAccountId = Number($('to-account').value);
    if (!fromAccountId || !toAccountId) {
        return {error: 'Выберите счета отправителя и получателя.'};
    }
    if (fromAccountId === toAccountId) {
        return {error: 'Счета отправителя и получателя должны различаться.'};
    }
    const amountMinor = parseRubles($('amount').value);
    if (amountMinor === null) {
        return {error: 'Введите сумму в рублях, например 100 или 100,50 (не больше двух знаков после запятой).'};
    }
    if (amountMinor < 1) {
        return {error: 'Сумма должна быть больше нуля.'};
    }
    if (amountMinor > MAX_AMOUNT_MINOR) {
        return {error: `Максимальная сумма одного перевода — ${formatMinor(MAX_AMOUNT_MINOR)}.`};
    }
    return {request: {fromAccountId, toAccountId, amountMinor}};
}

function sameRequest(a, b) {
    return a.fromAccountId === b.fromAccountId && a.toAccountId === b.toAccountId && a.amountMinor === b.amountMinor;
}

/**
 * Sends the pending transfer. The record is stored before the request and removed only after a definite
 * answer (2xx or 4xx), so a timeout, a network failure or a 5xx leaves it for a safe retry with the same key.
 */
async function sendPending(mode) {
    const request = pending;
    setBusy(true);
    clearMessage();
    try {
        const {status, data} = await api('/transfers', {
            method: 'POST',
            headers: {'Content-Type': 'application/json', 'Idempotency-Key': request.key},
            body: JSON.stringify({
                fromAccountId: request.fromAccountId,
                toAccountId: request.toAccountId,
                amountMinor: request.amountMinor,
            }),
        });
        if (status === 201 || status === 200) {
            savePending(null);
            setMessage('ok', successText(mode, status, request));
            $('amount').value = '';
        } else if (status >= 500) {
            setMessage('warn', 'Сервер не смог обработать запрос (ошибка ' + status + '). ' +
                'Результат перевода неизвестен — воспользуйтесь кнопкой «Проверить результат».');
        } else {
            savePending(null);
            setMessage('error', describeError(status, data));
        }
    } catch (e) {
        const reason = e.reason === 'timeout' ? 'Сервер не ответил за отведённое время.' : 'Нет связи с сервером.';
        setMessage('warn', `${reason} Результат перевода неизвестен: он мог выполниться или нет. ` +
            'Не повторяйте перевод с новым запросом — нажмите «Проверить результат».');
    } finally {
        setBusy(false);
        await refreshAll().catch(() => {});
    }
}

function successText(mode, status, request) {
    const what = `${accountLabel(request.fromAccountId)} → ${accountLabel(request.toAccountId)}, ${formatMinor(request.amountMinor)}`;
    if (mode === 'check') {
        return status === 200
            ? `Перевод был выполнен ранее, повторного списания нет: ${what}.`
            : `Прежняя попытка до сервера не дошла; перевод выполнен сейчас: ${what}.`;
    }
    return status === 200
        ? `Этот перевод уже был выполнен ранее (тот же ключ), повторного списания нет: ${what}.`
        : `Перевод выполнен: ${what}.`;
}

$('transfer-form').addEventListener('submit', (event) => {
    event.preventDefault();
    if (busy) {
        return;
    }
    const {request, error} = readForm();
    if (error) {
        setMessage('error', error);
        return;
    }
    // Same data as the unresolved request = manual retry with the same key; anything else is a new intent.
    if (!pending || !sameRequest(pending, request)) {
        savePending({key: newKey(), ...request});
    }
    sendPending('submit');
});

$('check-result').addEventListener('click', () => {
    if (!busy && pending) {
        sendPending('check');
    }
});

$('forget-pending').addEventListener('click', () => {
    if (busy || !pending) {
        return;
    }
    if (window.confirm('Перевод мог уже выполниться. Проверьте историю переводов. Забыть неподтверждённый запрос?')) {
        savePending(null);
        renderPending();
        clearMessage();
    }
});

$('refresh-accounts').addEventListener('click', () => {
    clearMessage();
    refreshAll();
});
$('refresh-transfers').addEventListener('click', () => {
    clearMessage();
    refreshAll();
});

// ---------- start ----------

renderPending();
refreshAll();
