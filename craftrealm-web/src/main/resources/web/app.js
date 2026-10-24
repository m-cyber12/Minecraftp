/*
 * CraftRealm dashboard client.
 *
 * Plain ES2020, no framework, no build step — the file is served straight from the
 * jar. Everything talks to /api/* with a Bearer session token obtained from
 * /api/login.
 */

const state = {
    session: localStorage.getItem('craftrealm.session') || null,
    timer: null,
};

const $ = (id) => document.getElementById(id);

/* ------------------------------------------------------------------ helpers */

async function api(path, options = {}) {
    const headers = Object.assign({'Content-Type': 'application/json'}, options.headers || {});
    if (state.session) {
        headers.Authorization = `Bearer ${state.session}`;
    }
    const response = await fetch(path, Object.assign({}, options, {headers}));
    if (response.status === 401) {
        logout();
        throw new Error('session expired');
    }
    if (!response.ok) {
        throw new Error(`request failed: ${response.status}`);
    }
    return response.json();
}

function el(tag, text, className) {
    const node = document.createElement(tag);
    if (text !== undefined && text !== null) {
        node.textContent = String(text);
    }
    if (className) {
        node.className = className;
    }
    return node;
}

function money(value) {
    return Number(value || 0).toLocaleString(undefined, {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
    });
}

function duration(millis) {
    const total = Math.floor((millis || 0) / 1000);
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    return hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`;
}

/* -------------------------------------------------------------------- login */

async function login() {
    const token = $('token').value.trim();
    $('login-error').textContent = '';
    try {
        const result = await api('/api/login', {
            method: 'POST',
            body: JSON.stringify({token}),
        });
        state.session = result.session;
        localStorage.setItem('craftrealm.session', state.session);
        showDashboard();
        await refreshAll();
        startPolling();
    } catch (error) {
        $('login-error').textContent = 'That token was refused. Check web.tokens in config.yml.';
    }
}

function logout() {
    state.session = null;
    localStorage.removeItem('craftrealm.session');
    stopPolling();
    $('dashboard').classList.add('hidden');
    $('login').classList.remove('hidden');
    $('token').value = '';
}

function showDashboard() {
    $('login').classList.add('hidden');
    $('dashboard').classList.remove('hidden');
}

/* ------------------------------------------------------------------- panels */

async function refreshStatus() {
    const status = await api('/api/status');
    const pill = $('status-pill');
    pill.textContent = `${status.online}/${status.maxPlayers} online · ${status.software}`;
    pill.className = 'pill ' + (status.online > 0 ? 'good' : 'warn');

    $('tps').textContent = Number(status.tps).toFixed(1);
    $('tps').className = 'stat-value ' + (status.tps >= 18 ? 'good' : 'warn');
    $('players-online').textContent = status.online;
    $('uptime').textContent = duration(status.uptimeMs);

    const ratio = status.maxRamMb > 0 ? status.usedRamMb / status.maxRamMb : 0;
    $('ram-bar').style.width = `${Math.min(100, ratio * 100).toFixed(0)}%`;
    $('ram-bar').className = 'pixel-bar-fill ' + (ratio > 0.9 ? 'danger' : ratio > 0.75 ? 'warn' : '');
    $('ram-label').textContent = `${status.usedRamMb} / ${status.maxRamMb} MB`;
}

async function refreshEconomy() {
    const data = await api('/api/economy');
    $('circulation').textContent = money(data.circulation);
    $('accounts').textContent = data.accounts;
    $('inflation').textContent = `${Number(data.inflationPercent || 0).toFixed(1)}%`;
    $('inflation').className = 'stat-value ' +
        (Math.abs(data.inflationPercent || 0) > 15 ? 'warn' : 'good');

    const top = $('top-earners');
    top.innerHTML = '';
    (data.top || []).forEach((entry) => {
        top.appendChild(el('li', `${entry.name} — ${money(entry.balance)}`));
    });
    if (!data.top || data.top.length === 0) {
        top.appendChild(el('li', 'No accounts yet.', 'muted'));
    }

    drawChart(data.history || []);
}

/** Pixel chart: each sample becomes a solid block column, no anti-aliasing. */
function drawChart(history) {
    const canvas = $('supply-chart');
    const ctx = canvas.getContext('2d');
    ctx.imageSmoothingEnabled = false;
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    ctx.fillStyle = '#f3e8cb';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    if (history.length < 2) {
        ctx.fillStyle = '#7d6a4f';
        ctx.font = '14px monospace';
        ctx.fillText('Not enough samples yet', 12, 24);
        return;
    }

    const max = Math.max(...history);
    const min = Math.min(...history);
    const span = Math.max(1e-9, max - min);
    const columns = history.length;
    const width = Math.floor(canvas.width / columns);

    history.forEach((value, index) => {
        const height = Math.max(2, Math.floor(((value - min) / span) * (canvas.height - 8)));
        ctx.fillStyle = index === columns - 1 ? '#e8b339' : '#c77b53';
        ctx.fillRect(index * width, canvas.height - height, Math.max(1, width - 1), height);
    });
}

async function refreshPlayers() {
    const players = await api('/api/players');
    const body = $('players-body');
    body.innerHTML = '';
    players.forEach((player) => {
        const row = document.createElement('tr');
        row.appendChild(el('td', player.name));
        row.appendChild(el('td', player.online ? 'online' : 'offline',
            'tag ' + (player.online ? 'online' : 'offline')));
        row.appendChild(el('td', money(player.balance)));
        row.appendChild(el('td', player.shards));
        row.appendChild(el('td', player.online ? `${player.ping} ms` : '–'));
        row.appendChild(el('td', player.world || '–'));

        const actions = el('td');
        if (player.online) {
            const kick = el('button', 'Kick', 'hotbar-button small danger');
            kick.addEventListener('click', () => punish(player.name, 'KICK', 0));
            const mute = el('button', 'Mute 1h', 'hotbar-button small');
            mute.addEventListener('click', () => punish(player.name, 'MUTE', 60));
            actions.appendChild(kick);
            actions.appendChild(mute);
        }
        row.appendChild(actions);
        body.appendChild(row);
    });
    if (players.length === 0) {
        const row = document.createElement('tr');
        const cell = el('td', 'Nobody online right now.', 'muted');
        cell.colSpan = 7;
        row.appendChild(cell);
        body.appendChild(row);
    }
}

async function punish(target, type, minutes) {
    const reason = window.prompt(`Reason for ${type.toLowerCase()} of ${target}?`, 'dashboard action');
    if (reason === null) {
        return;
    }
    await api('/api/punish', {
        method: 'POST',
        body: JSON.stringify({target, type, reason, durationMinutes: minutes}),
    });
    await refreshAudit();
}

async function refreshReports() {
    const reports = await api('/api/reports');
    const list = $('reports');
    list.innerHTML = '';
    if (reports.length === 0) {
        list.appendChild(el('li', 'Queue is empty. Good sign.', 'muted'));
        return;
    }
    reports.forEach((report) => {
        list.appendChild(el('li',
            `#${report.id} ${report.reporter} → ${report.target}: ${report.reason}`));
    });
}

async function refreshAudit() {
    const entries = await api('/api/audit?limit=40');
    const list = $('audit');
    list.innerHTML = '';
    entries.forEach((entry) => {
        list.appendChild(el('li', `${entry.staff} ${entry.action} ${entry.target || ''} — ${entry.detail || ''}`));
    });
}

async function refreshMarket() {
    const rows = await api('/api/market?limit=15');
    const list = $('market');
    list.innerHTML = '';
    rows.forEach((row) => {
        const trend = row.price > row.basePrice * 1.05 ? '▲'
            : row.price < row.basePrice * 0.95 ? '▼' : '▬';
        list.appendChild(el('li', `${trend} ${row.material}: ${money(row.price)} (base ${money(row.basePrice)})`));
    });
}

async function refreshConsole() {
    const lines = await api('/api/console?limit=40');
    const consoleNode = $('console');
    consoleNode.textContent = lines.join('\n');
    consoleNode.scrollTop = consoleNode.scrollHeight;
}

async function refreshAll() {
    try {
        await Promise.all([
            refreshStatus(),
            refreshEconomy(),
            refreshPlayers(),
            refreshReports(),
            refreshAudit(),
            refreshMarket(),
            refreshConsole(),
        ]);
    } catch (error) {
        console.error(error);
    }
}

/* --------------------------------------------------------------- scheduling */

function startPolling() {
    stopPolling();
    state.timer = window.setInterval(refreshAll, 5000);
}

function stopPolling() {
    if (state.timer) {
        window.clearInterval(state.timer);
        state.timer = null;
    }
}

/* ------------------------------------------------------------------- events */

$('login-button').addEventListener('click', login);
$('token').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
        login();
    }
});
$('logout').addEventListener('click', logout);

$('console-send').addEventListener('click', async () => {
    const command = $('console-input').value.trim();
    if (!command) {
        return;
    }
    await api('/api/console', {method: 'POST', body: JSON.stringify({command})});
    $('console-input').value = '';
    await refreshConsole();
});

$('console-input').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
        $('console-send').click();
    }
});

$('broadcast-send').addEventListener('click', async () => {
    const message = $('broadcast-input').value.trim();
    if (!message) {
        return;
    }
    await api('/api/broadcast', {method: 'POST', body: JSON.stringify({message})});
    $('broadcast-input').value = '';
});

/* -------------------------------------------------------------------- boot */

if (state.session) {
    showDashboard();
    refreshAll().then(startPolling).catch(() => logout());
}
