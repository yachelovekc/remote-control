(() => {
  // Elements
  const viewConnect = document.getElementById('view-connect');
  const viewControl = document.getElementById('view-control');
  const inputSupabaseUrl = document.getElementById('inputSupabaseUrl');
  const inputSupabaseKey = document.getElementById('inputSupabaseKey');
  const inputDeviceId = document.getElementById('inputDeviceId');
  const inputPassword = document.getElementById('inputPassword');
  const inputTurnServer = document.getElementById('inputTurnServer');
  const inputTurnUser = document.getElementById('inputTurnUser');
  const inputTurnPass = document.getElementById('inputTurnPass');
  const btnConnect = document.getElementById('btnConnect');
  const btnDisconnect = document.getElementById('btnDisconnect');

  const statusDot = document.getElementById('statusDot');
  const statusText = document.getElementById('statusText');
  const selectStreamMode = document.getElementById('selectStreamMode');
  const remoteVideo = document.getElementById('remoteVideo');
  const remoteAudio = document.getElementById('remoteAudio');
  const touchOverlay = document.getElementById('touchOverlay');

  const btnKeyBack = document.getElementById('btnKeyBack');
  const btnKeyHome = document.getElementById('btnKeyHome');
  const btnKeyRecents = document.getElementById('btnKeyRecents');
  const btnWake = document.getElementById('btnWake');
  const btnLock = document.getElementById('btnLock');
  const btnOpenFiles = document.getElementById('btnOpenFiles');

  // File Modal Elements
  const modalFiles = document.getElementById('modalFiles');
  const btnCloseFiles = document.getElementById('btnCloseFiles');
  const btnParentDir = document.getElementById('btnParentDir');
  const btnUploadFile = document.getElementById('btnUploadFile');
  const fileUploadInput = document.getElementById('fileUploadInput');
  const lblCurrentPath = document.getElementById('lblCurrentPath');
  const fileListContainer = document.getElementById('fileListContainer');

  // State
  let ws = null;
  let pc = null;
  let dataChannel = null;
  let refCount = 1;
  let heartbeatTimer = null;
  let currentDir = "/sdcard";
  let downloadingFile = null;

  // Restore saved config
  const savedConfig = JSON.parse(localStorage.getItem('remote_phone_cfg') || '{}');
  if (savedConfig.url) inputSupabaseUrl.value = savedConfig.url;
  if (savedConfig.key) inputSupabaseKey.value = savedConfig.key;
  if (savedConfig.deviceId) inputDeviceId.value = savedConfig.deviceId;
  if (savedConfig.password) inputPassword.value = savedConfig.password;
  if (savedConfig.turnServer) inputTurnServer.value = savedConfig.turnServer;
  if (savedConfig.turnUser) inputTurnUser.value = savedConfig.turnUser;
  if (savedConfig.turnPass) inputTurnPass.value = savedConfig.turnPass;

  // Save config on connect
  function saveCurrentConfig() {
    const cfg = {
      url: inputSupabaseUrl.value.trim(),
      key: inputSupabaseKey.value.trim(),
      deviceId: inputDeviceId.value.trim(),
      password: inputPassword.value.trim(),
      turnServer: inputTurnServer.value.trim(),
      turnUser: inputTurnUser.value.trim(),
      turnPass: inputTurnPass.value.trim()
    };
    localStorage.setItem('remote_phone_cfg', JSON.stringify(cfg));
    return cfg;
  }

  // --- Signaling (Supabase Realtime via WebSocket) ---
  btnConnect.addEventListener('click', () => {
    const cfg = saveCurrentConfig();
    if (!cfg.url || !cfg.key || !cfg.deviceId) {
      alert('Пожалуйста, заполните Supabase URL, Key и Device ID');
      return;
    }
    startConnection(cfg);
  });

  btnDisconnect.addEventListener('click', () => {
    cleanup();
    viewControl.classList.add('hidden');
    viewConnect.classList.remove('hidden');
  });

  function startConnection(cfg) {
    viewConnect.classList.add('hidden');
    viewControl.classList.remove('hidden');
    setStatus(false, 'Подключение к сигналингу...');

    const host = cfg.url.replace(/^https?:\/\//, '').replace(/\/$/, '');
    const wsUrl = `wss://${host}/realtime/v1/websocket?apikey=${encodeURIComponent(cfg.key)}&vsn=2.0.0`;

    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
      setStatus(false, 'Связь с облаком установлена. Поиск телефона...');
      joinChannel(cfg.deviceId);
      heartbeatTimer = setInterval(sendHeartbeat, 25000);

      // Initialize WebRTC
      initPeerConnection(cfg);
    };

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.event === 'broadcast' && msg.payload && msg.payload.event === 'signal') {
          handleSignal(msg.payload.payload);
        }
      } catch (err) {
        console.error('Error parsing signaling message', err);
      }
    };

    ws.onerror = (err) => {
      console.error('WebSocket Error', err);
      setStatus(false, 'Ошибка подключения к серверу');
    };

    ws.onclose = () => {
      setStatus(false, 'Связь с сервером разорвана');
      clearInterval(heartbeatTimer);
    };
  }

  function joinChannel(deviceId) {
    const topic = `realtime:remotephone_${deviceId}`;
    const joinMsg = {
      topic: topic,
      event: 'phx_join',
      payload: { config: { broadcast: { self: false } } },
      ref: String(++refCount)
    };
    ws.send(JSON.stringify(joinMsg));
  }

  function sendHeartbeat() {
    if (ws && ws.readyState === WebSocket.OPEN) {
      const hb = {
        topic: 'phoenix',
        event: 'heartbeat',
        payload: {},
        ref: 'hb_' + (++refCount)
      };
      ws.send(JSON.stringify(hb));
    }
  }

  function sendSignal(payload) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    const cfg = saveCurrentConfig();
    payload.password = cfg.password;
    payload.deviceId = cfg.deviceId;

    const msg = {
      topic: `realtime:remotephone_${cfg.deviceId}`,
      event: 'broadcast',
      payload: {
        type: 'broadcast',
        event: 'signal',
        payload: payload
      },
      ref: String(++refCount)
    };
    ws.send(JSON.stringify(msg));
  }

  // --- WebRTC PeerConnection ---
  function initPeerConnection(cfg) {
    const iceServers = [
      { urls: 'stun:stun.l.google.com:19302' },
      { urls: 'stun:stun1.l.google.com:19302' }
    ];

    if (cfg.turnServer) {
      const turnObj = { urls: cfg.turnServer };
      if (cfg.turnUser) turnObj.username = cfg.turnUser;
      if (cfg.turnPass) turnObj.credential = cfg.turnPass;
      iceServers.push(turnObj);
    }

    pc = new RTCPeerConnection({ iceServers });

    // Handle remote media tracks
    pc.ontrack = (event) => {
      if (event.track.kind === 'video') {
        remoteVideo.srcObject = event.streams[0] || new MediaStream([event.track]);
      } else if (event.track.kind === 'audio') {
        remoteAudio.srcObject = event.streams[0] || new MediaStream([event.track]);
      }
    };

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        sendSignal({
          action: 'candidate',
          candidate: event.candidate.candidate,
          sdpMid: event.candidate.sdpMid,
          sdpMLineIndex: event.candidate.sdpMLineIndex
        });
      }
    };

    pc.onconnectionstatechange = () => {
      console.log('ICE Connection state:', pc.connectionState);
      if (pc.connectionState === 'connected') {
        setStatus(true, 'Подключено');
      } else if (pc.connectionState === 'disconnected' || pc.connectionState === 'failed') {
        setStatus(false, 'Соединение потеряно');
      }
    };

    // Create Data Channel for Commands & Files
    dataChannel = pc.createDataChannel('commands', { ordered: true });
    setupDataChannel(dataChannel);

    // Create Offer
    pc.createOffer({ offerToReceiveVideo: true, offerToReceiveAudio: true })
      .then((offer) => pc.setLocalDescription(offer))
      .then(() => {
        sendSignal({
          action: 'offer',
          sdp: pc.localDescription.sdp
        });
      })
      .catch((err) => console.error('Failed to create offer', err));
  }

  function handleSignal(data) {
    if (data.action === 'answer' && pc) {
      pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: data.sdp }))
        .catch((e) => console.error('Error setting remote answer', e));
    } else if (data.action === 'candidate' && pc) {
      pc.addIceCandidate(new RTCIceCandidate({
        candidate: data.candidate,
        sdpMid: data.sdpMid,
        sdpMLineIndex: data.sdpMLineIndex
      })).catch((e) => console.error('Error adding ICE candidate', e));
    }
  }

  function setupDataChannel(dc) {
    dc.onopen = () => {
      console.log('DataChannel opened');
    };
    dc.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        handleDataChannelMessage(msg);
      } catch (e) {
        console.error('Error handling data channel message', e);
      }
    };
  }

  function sendDataCommand(cmdObj) {
    if (dataChannel && dataChannel.readyState === 'open') {
      dataChannel.send(JSON.stringify(cmdObj));
    }
  }

  function setStatus(connected, text) {
    statusText.textContent = text;
    if (connected) {
      statusDot.classList.add('connected');
    } else {
      statusDot.classList.remove('connected');
    }
  }

  function cleanup() {
    if (heartbeatTimer) clearInterval(heartbeatTimer);
    if (dataChannel) dataChannel.close();
    if (pc) pc.close();
    if (ws) ws.close();
    remoteVideo.srcObject = null;
    remoteAudio.srcObject = null;
    pc = null;
    ws = null;
    dataChannel = null;
  }

  // --- Touch & Gesture Translation ---
  let touchStartTime = 0;
  let startX = 0, startY = 0;
  let isTouching = false;

  function getRelativeCoords(e, element) {
    const rect = element.getBoundingClientRect();
    const clientX = e.touches ? e.touches[0].clientX : e.clientX;
    const clientY = e.touches ? e.touches[0].clientY : e.clientY;
    const x = (clientX - rect.left) / rect.width;
    const y = (clientY - rect.top) / rect.height;
    return {
      x: Math.max(0, Math.min(1, x)),
      y: Math.max(0, Math.min(1, y))
    };
  }

  touchOverlay.addEventListener('touchstart', (e) => {
    e.preventDefault();
    touchStartTime = Date.now();
    const c = getRelativeCoords(e, touchOverlay);
    startX = c.x;
    startY = c.y;
    isTouching = true;
  }, { passive: false });

  touchOverlay.addEventListener('touchend', (e) => {
    e.preventDefault();
    if (!isTouching) return;
    isTouching = false;

    const duration = Math.max(50, Date.now() - touchStartTime);
    const endX = startX;
    const endY = startY;

    // Tap if short, swipe if movement occurred
    sendDataCommand({
      type: 'touch',
      action: 'tap',
      x: endX,
      y: endY
    });
  }, { passive: false });

  // Mouse fallback for PC browser
  touchOverlay.addEventListener('mousedown', (e) => {
    touchStartTime = Date.now();
    const c = getRelativeCoords(e, touchOverlay);
    startX = c.x;
    startY = c.y;
    isTouching = true;
  });

  touchOverlay.addEventListener('mouseup', (e) => {
    if (!isTouching) return;
    isTouching = false;
    const c = getRelativeCoords(e, touchOverlay);
    const duration = Math.max(50, Date.now() - touchStartTime);
    const dist = Math.hypot(c.x - startX, c.y - startY);

    if (dist < 0.03) {
      sendDataCommand({ type: 'touch', action: 'tap', x: c.x, y: c.y });
    } else {
      sendDataCommand({ type: 'swipe', x1: startX, y1: startY, x2: c.x, y2: c.y, duration });
    }
  });

  // --- Navigation Controls ---
  btnKeyBack.addEventListener('click', () => sendDataCommand({ type: 'key', code: 'back' }));
  btnKeyHome.addEventListener('click', () => sendDataCommand({ type: 'key', code: 'home' }));
  btnKeyRecents.addEventListener('click', () => sendDataCommand({ type: 'key', code: 'recents' }));
  btnLock.addEventListener('click', () => sendDataCommand({ type: 'key', code: 'lock' }));

  btnWake.addEventListener('click', () => {
    // Send wake via signaling so it wakes even if sleep dropped connection
    sendSignal({ action: 'wake' });
    sendDataCommand({ type: 'key', code: 'wake' });
  });

  selectStreamMode.addEventListener('change', (e) => {
    sendDataCommand({ type: 'switch_stream', target: e.target.value });
  });

  // --- File Manager ---
  btnOpenFiles.addEventListener('click', () => {
    modalFiles.classList.remove('hidden');
    requestFileList(currentDir);
  });

  btnCloseFiles.addEventListener('click', () => {
    modalFiles.classList.add('hidden');
  });

  btnParentDir.addEventListener('click', () => {
    const parts = currentDir.replace(/\/$/, '').split('/');
    if (parts.length > 2) {
      parts.pop();
      currentDir = parts.join('/') || '/';
      requestFileList(currentDir);
    }
  });

  function requestFileList(path) {
    lblCurrentPath.textContent = path;
    fileListContainer.innerHTML = '<div style="padding:20px;text-align:center;color:#888;">Загрузка файлов...</div>';
    sendDataCommand({ type: 'file_list', path: path });
  }

  function handleDataChannelMessage(msg) {
    if (msg.type === 'file_list_response') {
      renderFileList(msg.currentPath, msg.files);
    } else if (msg.type === 'file_download_chunk') {
      handleDownloadChunk(msg);
    } else if (msg.type === 'file_upload_ack') {
      if (window.onUploadChunkAck) window.onUploadChunkAck(msg);
    }
  }

  function renderFileList(path, files) {
    currentDir = path;
    lblCurrentPath.textContent = path;
    fileListContainer.innerHTML = '';

    if (!files || files.length === 0) {
      fileListContainer.innerHTML = '<div style="padding:20px;text-align:center;color:#888;">Папка пуста</div>';
      return;
    }

    files.forEach((file) => {
      const item = document.createElement('div');
      item.className = 'file-item';

      const left = document.createElement('div');
      left.className = 'file-item-left';
      left.innerHTML = `<span>${file.isDir ? '📁' : '📄'}</span><span class="name">${escapeHtml(file.name)}</span>`;

      const right = document.createElement('div');
      right.className = 'file-item-right';

      if (file.isDir) {
        item.addEventListener('click', () => requestFileList(file.path));
      } else {
        const sizeKb = (file.size / 1024).toFixed(1) + ' KB';
        right.innerHTML = `<span>${sizeKb}</span>`;

        const btnDl = document.createElement('button');
        btnDl.className = 'file-btn';
        btnDl.textContent = '⬇ Скачать';
        btnDl.addEventListener('click', (e) => {
          e.stopPropagation();
          startDownload(file.path, file.name);
        });
        right.appendChild(btnDl);

        const btnDel = document.createElement('button');
        btnDel.className = 'file-btn';
        btnDel.textContent = '🗑';
        btnDel.style.color = '#ff6b6b';
        btnDel.addEventListener('click', (e) => {
          e.stopPropagation();
          if (confirm(`Удалить файл ${file.name}?`)) {
            sendDataCommand({ type: 'file_delete', path: file.path });
            setTimeout(() => requestFileList(currentDir), 300);
          }
        });
        right.appendChild(btnDel);
      }

      item.appendChild(left);
      item.appendChild(right);
      fileListContainer.appendChild(item);
    });
  }

  function startDownload(path, fileName) {
    downloadingFile = {
      path: path,
      name: fileName,
      chunks: [],
      offset: 0
    };
    sendDataCommand({ type: 'file_download_chunk', path: path, offset: 0 });
  }

  function handleDownloadChunk(chunk) {
    if (!downloadingFile || downloadingFile.path !== chunk.path) return;

    if (chunk.dataBase64) {
      const binary = atob(chunk.dataBase64);
      const bytes = new Uint8Array(binary.length);
      for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
      }
      downloadingFile.chunks.push(bytes);
      downloadingFile.offset += bytes.length;
    }

    if (chunk.isLast) {
      // Assemble and trigger browser download
      const blob = new Blob(downloadingFile.chunks);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = downloadingFile.name;
      a.click();
      URL.revokeObjectURL(url);
      downloadingFile = null;
      alert(`Файл скачан!`);
    } else {
      // Request next chunk
      sendDataCommand({ type: 'file_download_chunk', path: chunk.path, offset: downloadingFile.offset });
    }
  }

  // Upload file
  btnUploadFile.addEventListener('click', () => fileUploadInput.click());

  fileUploadInput.addEventListener('change', () => {
    const file = fileUploadInput.files[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = async (e) => {
      const arrayBuffer = e.target.result;
      const targetPath = `${currentDir.replace(/\/$/, '')}/${file.name}`;
      await uploadFileInChunks(targetPath, arrayBuffer);
      requestFileList(currentDir);
      fileUploadInput.value = '';
    };
    reader.readAsArrayBuffer(file);
  });

  async function uploadFileInChunks(targetPath, buffer) {
    const chunkSize = 32 * 1024;
    let offset = 0;
    const total = buffer.byteLength;

    while (offset < total) {
      const end = Math.min(offset + chunkSize, total);
      const slice = buffer.slice(offset, end);
      const bytes = new Uint8Array(slice);
      let binary = '';
      for (let i = 0; i < bytes.length; i++) {
        binary += String.fromCharCode(bytes[i]);
      }
      const base64 = btoa(binary);

      await new Promise((resolve) => {
        window.onUploadChunkAck = (ack) => {
          if (ack.offset === offset) {
            window.onUploadChunkAck = null;
            resolve();
          }
        };
        sendDataCommand({
          type: 'file_upload_chunk',
          path: targetPath,
          offset: offset,
          data: base64
        });
      });

      offset = end;
    }
    alert('Файл успешно загружен на второй телефон!');
  }

  function escapeHtml(str) {
    return str.replace(/[&<>'"]/g, (tag) => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      "'": '&#39;',
      '"': '&quot;'
    }[tag] || tag));
  }

  // Register PWA service worker if supported
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
})();
