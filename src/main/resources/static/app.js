'use strict';

(() => {
  const $ = (id) => document.getElementById(id);
  const storageKey = 'relay.accessToken';
  const state = {
    token: sessionStorage.getItem(storageKey), user: null, mode: 'login', socket: null,
    socketReady: false, epoch: 0, retry: 0, retryTimer: null, toastTimer: null,
    messages: new Map(), notifications: new Map(), historyLoading: false,
    hasOlder: false, users: [], usersOffset: 0, usersPageSize: 20, page: 'chat',
  };
  const dateFormat = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short' });
  const timeFormat = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' });
  const fullDateFormat = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function date(value, formatter = fullDateFormat) {
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? '' : formatter.format(parsed);
  }
  function toast(message, error = false) {
    clearTimeout(state.toastTimer);
    $('toast').textContent = message;
    $('toast').className = error ? 'error' : '';
    $('toast').hidden = false;
    state.toastTimer = setTimeout(() => { $('toast').hidden = true; }, error ? 6000 : 4000);
  }
  function emptyState(title, description, symbol = '↗') {
    const block = element('div', 'empty-state');
    const icon = element('span', '', symbol);
    icon.setAttribute('aria-hidden', 'true');
    block.append(icon, element('h3', '', title), element('p', '', description));
    return block;
  }

  async function api(path, options = {}) {
    const token = state.token;
    const headers = { Accept: 'application/json', ...options.headers };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(path, { ...options, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body) });
    const raw = await response.text();
    let data;
    try { data = raw ? JSON.parse(raw) : null; } catch { data = null; }
    if (!response.ok) {
      if (response.status === 401 && token && !path.startsWith('/api/auth/')) logout('Сеанс завершён. Войдите снова.');
      const detail = typeof data?.message === 'string' ? data.message : typeof data?.error === 'string' ? data.error : null;
      const fallback = { 400: 'Проверьте введённые данные.', 401: 'Неверный логин или пароль.', 403: 'Недостаточно прав для этого действия.', 404: 'Запись не найдена.', 409: 'Такое имя пользователя уже занято.', 429: 'Слишком много запросов. Попробуйте чуть позже.' };
      const error = new Error(detail || fallback[response.status] || 'Сервер не смог выполнить запрос. Попробуйте позже.');
      error.status = response.status;
      throw error;
    }
    return data;
  }

  function setMode(mode) {
    state.mode = mode;
    const register = mode === 'register';
    $('login-tab').setAttribute('aria-selected', String(!register));
    $('register-tab').setAttribute('aria-selected', String(register));
    $('auth-heading').textContent = register ? 'Ваше новое пространство' : 'С возвращением';
    $('auth-description').textContent = register ? 'Создайте аккаунт и начните разговор.' : 'Войдите, чтобы присоединиться к разговору.';
    $('password').autocomplete = register ? 'new-password' : 'current-password';
    $('auth-submit').replaceChildren(document.createTextNode(register ? 'Создать аккаунт ' : 'Войти в пространство '), element('span', '', '↗'));
    $('auth-error').hidden = true;
  }
  function updateProfile(user) {
    state.user = user;
    $('profile-name').textContent = user.username;
    $('profile-avatar').textContent = user.username.slice(0, 2).toUpperCase();
    $('profile-role').textContent = user.role;
    $('profile-role').className = `role-badge ${user.role === 'admin' ? 'admin' : ''}`;
    $('admin-nav').hidden = user.role !== 'admin';
    if (user.role !== 'admin' && state.page === 'admin') showPage('chat');
  }
  function startSession(token, user) {
    state.epoch += 1;
    state.token = token;
    sessionStorage.setItem(storageKey, token);
    updateProfile(user);
    $('auth-view').hidden = true;
    $('workspace').hidden = false;
    $('password').value = '';
    state.retry = 0;
    showPage('chat');
    connect();
  }
  function logout(message) {
    state.epoch += 1;
    state.token = null;
    state.user = null;
    sessionStorage.removeItem(storageKey);
    clearTimeout(state.retryTimer);
    state.socketReady = false;
    const oldSocket = state.socket;
    state.socket = null;
    if (oldSocket && oldSocket.readyState < WebSocket.CLOSING) oldSocket.close(1000, 'Logout');
    state.messages.clear();
    state.notifications.clear();
    state.users = [];
    state.usersOffset = 0;
    state.historyLoading = false;
    state.hasOlder = false;
    $('message-text').value = '';
    updateComposer();
    renderMessages(true);
    renderNotifications();
    $('workspace').hidden = true;
    $('auth-view').hidden = false;
    $('auth-error').hidden = true;
    if (message) toast(message, true);
  }
  function connection(status, text) {
    $('connection').className = `connection ${status}`;
    $('connection').lastElementChild.textContent = text;
  }
  function connect() {
    if (!state.token || !state.user) return;
    clearTimeout(state.retryTimer);
    const epoch = state.epoch;
    connection('connecting', state.retry ? 'Восстанавливаем связь' : 'Подключение');
    const socket = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`);
    state.socket = socket;
    state.socketReady = false;
    updateComposer();
    socket.addEventListener('open', () => {
      if (epoch !== state.epoch || socket !== state.socket) return;
      socket.send(JSON.stringify({ type: 'auth', token: state.token }));
    });
    socket.addEventListener('message', (event) => {
      if (epoch !== state.epoch || socket !== state.socket) return;
      let frame;
      try { frame = JSON.parse(event.data); } catch { return; }
      if (frame.type === 'ready') {
        state.socketReady = true;
        state.retry = 0;
        connection('', 'На связи');
        updateComposer();
        // Register the socket first, then merge persisted history by id to avoid a delivery gap.
        Promise.allSettled([loadHistory(false), loadNotifications()]).then((results) => {
          if (epoch !== state.epoch) return;
          results.forEach((result) => { if (result.status === 'rejected') toast(result.reason.message, true); });
        });
      } else if (frame.type === 'message' && frame.message) {
        state.messages.set(String(frame.message.id), frame.message);
        renderMessages();
      } else if (frame.type === 'notification' && frame.notification) {
        state.notifications.set(String(frame.notification.id), frame.notification);
        renderNotifications();
        toast(`Новое оповещение: ${frame.notification.title}`);
      } else if (frame.type === 'error') {
        toast('Не удалось отправить сообщение. Проверьте текст и попробуйте ещё раз.', true);
      }
    });
    socket.addEventListener('close', (event) => {
      if (epoch !== state.epoch || socket !== state.socket || !state.token) return;
      state.socketReady = false;
      updateComposer();
      if (event.code === 1008) { logout('Сеанс завершён. Войдите снова.'); return; }
      connection('offline', 'Нет соединения');
      state.retry += 1;
      state.retryTimer = setTimeout(connect, Math.min(1000 * 2 ** (state.retry - 1), 15000));
    });
    socket.addEventListener('error', () => { if (epoch === state.epoch) connection('offline', 'Нет соединения'); });
  }

  function showPage(page) {
    if (page === 'admin' && state.user?.role !== 'admin') return;
    state.page = page;
    const labels = { chat: ['Общий чат', 'На одной волне', 'Разговоры, которые объединяют команду.'], notifications: ['Оповещения', 'Ничего не пропустить', 'Важные сообщения, адресованные вам.'], admin: ['Управление', 'Команда под контролем', 'Роли участников и оповещения для команды.'] };
    const [label, heading, description] = labels[page];
    $('page-label').textContent = label;
    $('page-heading').replaceChildren(document.createTextNode(heading), element('span', 'heading-dot', '.'));
    $('page-description').textContent = description;
    ['chat', 'notifications', 'admin'].forEach((key) => {
      $(`${key}-panel`).hidden = key !== page;
      $(`${key}-nav`).classList.toggle('active', key === page);
    });
    if (page === 'notifications') loadNotifications().catch((error) => toast(error.message, true));
    if (page === 'admin') loadUsers().catch((error) => toast(error.message, true));
    if (page === 'chat') $('messages').scrollTop = $('messages').scrollHeight;
  }

  async function loadHistory(older) {
    if (state.historyLoading || !state.user) return;
    state.historyLoading = true;
    const epoch = state.epoch;
    const sorted = [...state.messages.values()].sort((a, b) => a.id - b.id);
    const before = older && sorted.length ? `&before=${encodeURIComponent(sorted[0].id)}` : '';
    $('older-messages').disabled = true;
    try {
      let items = await api(`/api/messages?limit=50${before}`);
      if (epoch !== state.epoch) return;
      items.forEach((message) => state.messages.set(String(message.id), message));
      if (older || sorted.length === 0) state.hasOlder = items.length === 50;
      // A reconnect can span more than one history page; fill the gap before the last known message.
      const lastKnownId = sorted.at(-1)?.id;
      while (!older && lastKnownId !== undefined && items.length === 50) {
        const oldest = items.reduce((result, item) => item.id < result.id ? item : result);
        if (oldest.id <= lastKnownId) break;
        items = await api(`/api/messages?limit=50&before=${encodeURIComponent(oldest.id)}`);
        if (epoch !== state.epoch) return;
        items.forEach((message) => state.messages.set(String(message.id), message));
      }
      renderMessages(!older && sorted.length === 0, older);
    } finally {
      if (epoch === state.epoch) {
        state.historyLoading = false;
        $('older-messages').hidden = !state.hasOlder;
        $('older-messages').disabled = false;
      }
    }
  }
  function renderMessages(forceBottom = false, prepend = false) {
    const container = $('messages');
    const previousHeight = container.scrollHeight;
    const previousTop = container.scrollTop;
    const wasAtBottom = previousHeight - previousTop - container.clientHeight < 100;
    const messages = [...state.messages.values()].sort((a, b) => a.id - b.id);
    const fragment = document.createDocumentFragment();
    let previousDay = '';
    for (const message of messages) {
      const day = date(message.createdAt, dateFormat);
      if (day !== previousDay) { fragment.append(element('div', 'date-divider', day)); previousDay = day; }
      const item = element('div', `message${message.userId === state.user?.id ? ' own' : ''}`);
      item.dataset.messageId = String(message.id);
      const avatar = element('span', 'avatar', message.username.slice(0, 2).toUpperCase());
      avatar.setAttribute('aria-hidden', 'true');
      const content = element('div', 'message-content');
      const meta = element('div', 'message-meta');
      const timestamp = element('time', 'message-time', date(message.createdAt, timeFormat));
      timestamp.dateTime = message.createdAt;
      timestamp.title = date(message.createdAt);
      meta.append(element('span', 'message-name', message.username), timestamp);
      content.append(meta, element('p', 'message-text', message.text));
      item.append(avatar, content);
      fragment.append(item);
    }
    if (!messages.length) fragment.append(emptyState('Разговор начинается здесь', 'Отправьте первое сообщение команде.'));
    container.replaceChildren(fragment);
    if (prepend) container.scrollTop = previousTop + container.scrollHeight - previousHeight;
    else if (forceBottom || wasAtBottom) container.scrollTop = container.scrollHeight;
    else container.scrollTop = previousTop;
  }
  function updateComposer() {
    const input = $('message-text');
    $('send-message').disabled = !state.socketReady || !input.value.trim();
    $('message-counter').textContent = `${input.value.length} / 2000`;
    input.style.height = 'auto';
    input.style.height = `${Math.min(Math.max(input.scrollHeight, 40), 120)}px`;
  }

  async function loadNotifications() {
    const epoch = state.epoch;
    const items = await api('/api/notifications?limit=100');
    if (epoch !== state.epoch) return;
    // Merge instead of replacing: an event can arrive while the request is in flight.
    items.forEach((notification) => {
      const existing = state.notifications.get(String(notification.id));
      state.notifications.set(String(notification.id), existing?.read ? { ...notification, read: true } : notification);
    });
    renderNotifications();
  }
  function notificationElement(notification) {
    const item = element('article', `notification-item${notification.read ? '' : ' unread'}`);
    item.append(element('h3', 'notification-title', notification.title), element('p', 'notification-text', notification.message));
    const bottom = element('div', 'notification-bottom');
    const timestamp = element('time', 'notification-time', date(notification.createdAt));
    timestamp.dateTime = notification.createdAt;
    bottom.append(timestamp);
    if (notification.read) bottom.append(element('span', 'notification-read', 'Прочитано ✓'));
    else {
      const button = element('button', 'mark-read', 'Прочитано ✓');
      button.type = 'button';
      button.setAttribute('aria-label', `Отметить «${notification.title}» прочитанным`);
      button.addEventListener('click', async () => {
        button.disabled = true;
        const epoch = state.epoch;
        try {
          const updated = await api(`/api/notifications/${encodeURIComponent(notification.id)}/read`, { method: 'PATCH' });
          if (epoch !== state.epoch) return;
          state.notifications.set(String(updated.id), updated);
          renderNotifications();
        } catch (error) { toast(error.message, true); button.disabled = false; }
      });
      bottom.append(button);
    }
    item.append(bottom);
    return item;
  }
  function renderNotifications() {
    const items = [...state.notifications.values()].sort((a, b) => b.id - a.id);
    const unread = items.filter((item) => !item.read).length;
    $('nav-unread').textContent = String(unread);
    $('nav-unread').hidden = unread === 0;
    $('unread-total').textContent = `${unread} новых`;
    for (const [id, limit] of [['notification-preview', 3], ['notification-full-list', 100]]) {
      const fragment = document.createDocumentFragment();
      items.slice(0, limit).forEach((item) => fragment.append(notificationElement(item)));
      if (!items.length) fragment.append(emptyState('Пока ничего нового', 'Оповещения от администратора появятся здесь.', '✳'));
      $(id).replaceChildren(fragment);
    }
  }

  async function loadUsers() {
    if (state.user?.role !== 'admin') return;
    const epoch = state.epoch;
    const users = await api(`/api/admin/users?limit=${state.usersPageSize}&offset=${state.usersOffset}`);
    if (epoch !== state.epoch) return;
    state.users = users;
    const rows = document.createDocumentFragment();
    const target = $('notification-target');
    const selectedTarget = target.value;
    target.replaceChildren(new Option('Все участники', ''));
    for (const user of users) {
      target.add(new Option(`${user.username} · #${user.id}`, String(user.id)));
      const row = element('tr');
      const userCell = element('td');
      const identity = element('div', 'table-user');
      identity.append(element('span', 'avatar', user.username.slice(0, 2).toUpperCase()), element('span', '', user.username));
      userCell.append(identity);
      const roleCell = element('td');
      const select = element('select', 'role-select');
      select.setAttribute('aria-label', `Роль пользователя ${user.username}`);
      select.append(new Option('user', 'user'), new Option('admin', 'admin'));
      select.value = user.role;
      select.disabled = user.id === state.user.id;
      select.addEventListener('change', async () => {
        const oldRole = user.role;
        select.disabled = true;
        try {
          const updated = await api(`/api/admin/users/${encodeURIComponent(user.id)}/role`, { method: 'PATCH', body: { role: select.value } });
          if (epoch !== state.epoch) return;
          user.role = updated.role;
          toast(`Роль ${user.username} изменена на ${updated.role}.`);
        } catch (error) { select.value = oldRole; toast(error.message, true); }
        finally { select.disabled = user.id === state.user?.id; }
      });
      roleCell.append(select);
      const createdCell = element('td', 'user-date', date(user.createdAt, dateFormat));
      const noteCell = element('td', 'table-note', user.id === state.user.id ? 'Это вы' : `#${user.id}`);
      row.append(userCell, roleCell, createdCell, noteCell);
      rows.append(row);
    }
    if ([...target.options].some((option) => option.value === selectedTarget)) target.value = selectedTarget;
    $('users-list').replaceChildren(rows);
    $('users-page').textContent = `Страница ${state.usersOffset / state.usersPageSize + 1}`;
    $('previous-users').disabled = state.usersOffset === 0;
    $('next-users').disabled = users.length < state.usersPageSize;
  }

  $('login-tab').addEventListener('click', () => setMode('login'));
  $('register-tab').addEventListener('click', () => setMode('register'));
  $('password-toggle').addEventListener('click', () => {
    const hidden = $('password').type === 'password';
    $('password').type = hidden ? 'text' : 'password';
    $('password-toggle').textContent = hidden ? 'Скрыть' : 'Показать';
    $('password-toggle').setAttribute('aria-label', hidden ? 'Скрыть пароль' : 'Показать пароль');
  });
  $('auth-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    $('auth-error').hidden = true;
    $('auth-submit').disabled = true;
    try {
      const data = await api(`/api/auth/${state.mode}`, { method: 'POST', body: { username: $('username').value.trim(), password: $('password').value } });
      startSession(data.accessToken, data.user);
      toast(state.mode === 'register' ? 'Аккаунт создан. Добро пожаловать!' : 'Вы в пространстве. С возвращением!');
    } catch (error) { $('auth-error').textContent = error.message; $('auth-error').hidden = false; }
    finally { $('auth-submit').disabled = false; }
  });
  $('logout').addEventListener('click', () => logout());
  ['chat', 'notifications', 'admin'].forEach((page) => $(`${page}-nav`).addEventListener('click', () => showPage(page)));
  $('all-notifications').addEventListener('click', () => showPage('notifications'));
  $('older-messages').addEventListener('click', () => loadHistory(true).catch((error) => toast(error.message, true)));
  $('message-text').addEventListener('input', updateComposer);
  $('message-text').addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); if (!$('send-message').disabled) $('message-form').requestSubmit(); }
  });
  $('message-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const text = $('message-text').value.trim();
    if (!text || text.length > 2000 || !state.socketReady || state.socket?.readyState !== WebSocket.OPEN) return;
    try {
      state.socket.send(JSON.stringify({ type: 'message', text }));
      $('message-text').value = '';
      updateComposer();
      $('message-text').focus();
    } catch { toast('Сообщение не отправлено. Проверьте соединение и повторите попытку.', true); }
  });
  $('refresh-notifications').addEventListener('click', () => loadNotifications().catch((error) => toast(error.message, true)));
  $('refresh-users').addEventListener('click', () => loadUsers().catch((error) => toast(error.message, true)));
  $('previous-users').addEventListener('click', () => { state.usersOffset = Math.max(0, state.usersOffset - state.usersPageSize); loadUsers().catch((error) => toast(error.message, true)); });
  $('next-users').addEventListener('click', () => { state.usersOffset += state.usersPageSize; loadUsers().catch((error) => toast(error.message, true)); });
  $('notification-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const button = event.currentTarget.querySelector('button[type=submit]');
    const title = $('notification-title').value.trim();
    const message = $('notification-body').value.trim();
    if (!title || !message) { toast('Укажите заголовок и текст оповещения.', true); return; }
    button.disabled = true;
    try {
      const target = $('notification-target').value;
      const result = await api('/api/admin/notifications', { method: 'POST', body: { title, message, userId: target ? Number(target) : null } });
      $('notification-title').value = '';
      $('notification-body').value = '';
      toast(`Оповещение отправлено. Получателей: ${result.delivered}.`);
    } catch (error) { toast(error.message, true); }
    finally { button.disabled = false; }
  });
  window.addEventListener('online', () => {
    if (state.token && (!state.socket || state.socket.readyState === WebSocket.CLOSED)) connect();
  });
  window.addEventListener('beforeunload', () => { state.socket?.close(1000, 'Page closed'); });

  renderNotifications();
  if (state.token) {
    $('auth-submit').disabled = true;
    api('/api/users/me').then((user) => startSession(state.token, user)).catch((error) => {
      if (state.token) logout('Не удалось восстановить сеанс. Войдите снова.');
    }).finally(() => { $('auth-submit').disabled = false; });
  }
})();
