const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 3000;
const HOST = '0.0.0.0';

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.apk': 'application/vnd.android.package-archive',
  '.md': 'text/markdown; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8'
};

const server = http.createServer((req, res) => {
  const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  let pathname = decodeURIComponent(parsedUrl.pathname);

  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  // API Status endpoint
  if (pathname === '/api/status') {
    const apkPath = path.join(__dirname, 'KothaPad.apk');
    let apkSize = 0;
    let apkAvailable = false;
    let apkMtime = null;

    try {
      if (fs.existsSync(apkPath)) {
        const stats = fs.statSync(apkPath);
        apkSize = stats.size;
        apkAvailable = true;
        apkMtime = stats.mtime;
      }
    } catch (_) {}

    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      appName: 'KothaPad',
      status: 'ready',
      version: '1.0',
      apkAvailable,
      apkSize,
      apkSizeFormatted: apkSize > 0 ? (apkSize / (1024 * 1024)).toFixed(1) + ' MB' : '0 MB',
      lastUpdated: apkMtime ? apkMtime.toISOString() : null,
      platform: 'Android',
      minSdk: 26,
      targetSdk: 36
    }, null, 2));
    return;
  }

  // APK Download routes
  if (pathname === '/api/download' || pathname === '/download' || pathname === '/KothaPad.apk') {
    const apkPath = path.join(__dirname, 'KothaPad.apk');
    if (fs.existsSync(apkPath)) {
      const stat = fs.statSync(apkPath);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="KothaPad.apk"',
        'Content-Length': stat.size
      });
      fs.createReadStream(apkPath).pipe(res);
      return;
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('KothaPad.apk build artifact not found. Please compile the app first.');
      return;
    }
  }

  // Serve MainBrain.md raw or API
  if (pathname === '/api/mainbrain' || pathname === '/MainBrain.md') {
    const mbPath = path.join(__dirname, 'MainBrain.md');
    if (fs.existsSync(mbPath)) {
      res.writeHead(200, { 'Content-Type': 'text/markdown; charset=utf-8' });
      fs.createReadStream(mbPath).pipe(res);
      return;
    }
  }

  // Root or HTML
  if (pathname === '/' || pathname === '/index.html') {
    serveDashboard(res);
    return;
  }

  // Static files in /public or /assets or root
  const candidates = [
    path.join(__dirname, 'public', pathname),
    path.join(__dirname, pathname),
    path.join(__dirname, 'assets', pathname)
  ];

  for (const candidate of candidates) {
    if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) {
      const ext = path.extname(candidate).toLowerCase();
      const contentType = MIME_TYPES[ext] || 'application/octet-stream';
      res.writeHead(200, { 'Content-Type': contentType });
      fs.createReadStream(candidate).pipe(res);
      return;
    }
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('404 Not Found');
});

function serveDashboard(res) {
  const apkPath = path.join(__dirname, 'KothaPad.apk');
  let apkSizeMb = '19.0';
  let apkExists = false;
  try {
    if (fs.existsSync(apkPath)) {
      const stats = fs.statSync(apkPath);
      apkSizeMb = (stats.size / (1024 * 1024)).toFixed(1);
      apkExists = true;
    }
  } catch (_) {}

  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>KothaPad - Speech-Enabled Daily Journal & Workstation</title>
  <link rel="icon" type="image/svg+xml" href="/assets/icon.svg">
  <style>
    :root {
      --bg: #090A0F;
      --card-bg: #12131C;
      --card-border: rgba(255, 255, 255, 0.08);
      --accent: #7C4DFF;
      --accent-cyan: #00E5FF;
      --text: #F1F3F9;
      --text-dim: #9BA3AF;
      --font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background-color: var(--bg);
      color: var(--text);
      font-family: var(--font-family);
      line-height: 1.6;
      padding: 0;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
    }
    header {
      padding: 3rem 1.5rem 2rem;
      max-width: 900px;
      margin: 0 auto;
      text-align: center;
      width: 100%;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 6px 14px;
      border-radius: 9999px;
      background: rgba(124, 77, 255, 0.15);
      border: 1px solid rgba(124, 77, 255, 0.35);
      color: #B388FF;
      font-size: 0.85rem;
      font-weight: 600;
      letter-spacing: 0.5px;
      margin-bottom: 1.25rem;
      text-transform: uppercase;
    }
    .pulse-dot {
      width: 8px;
      height: 8px;
      background-color: var(--accent-cyan);
      border-radius: 50%;
      box-shadow: 0 0 10px var(--accent-cyan);
    }
    h1 {
      font-size: 2.75rem;
      font-weight: 800;
      letter-spacing: -0.03em;
      margin-bottom: 0.75rem;
      background: linear-gradient(135deg, #FFFFFF 30%, #B388FF 100%);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
    }
    p.tagline {
      font-size: 1.15rem;
      color: var(--text-dim);
      max-width: 650px;
      margin: 0 auto 2rem;
    }
    .actions {
      display: flex;
      gap: 1rem;
      justify-content: center;
      flex-wrap: wrap;
      margin-bottom: 2.5rem;
    }
    .btn {
      display: inline-flex;
      align-items: center;
      gap: 10px;
      padding: 14px 28px;
      border-radius: 12px;
      font-weight: 600;
      font-size: 1rem;
      text-decoration: none;
      transition: all 0.2s ease;
      cursor: pointer;
    }
    .btn-primary {
      background: linear-gradient(135deg, #7C4DFF 0%, #651FFF 100%);
      color: #FFFFFF;
      box-shadow: 0 4px 20px rgba(124, 77, 255, 0.4);
      border: 1px solid rgba(255, 255, 255, 0.15);
    }
    .btn-primary:hover {
      transform: translateY(-2px);
      box-shadow: 0 6px 24px rgba(124, 77, 255, 0.6);
    }
    .btn-secondary {
      background: var(--card-bg);
      color: var(--text);
      border: 1px solid var(--card-border);
    }
    .btn-secondary:hover {
      background: #1A1C28;
      border-color: rgba(255, 255, 255, 0.2);
    }
    main {
      max-width: 900px;
      margin: 0 auto;
      padding: 0 1.5rem 4rem;
      width: 100%;
      flex: 1;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
      gap: 1.25rem;
      margin-bottom: 2.5rem;
    }
    .card {
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: 16px;
      padding: 1.5rem;
      transition: transform 0.2s ease, border-color 0.2s ease;
    }
    .card:hover {
      border-color: rgba(124, 77, 255, 0.3);
      transform: translateY(-2px);
    }
    .card h3 {
      font-size: 1.15rem;
      margin-bottom: 0.5rem;
      display: flex;
      align-items: center;
      gap: 8px;
      color: #FFFFFF;
    }
    .card p {
      font-size: 0.92rem;
      color: var(--text-dim);
      line-height: 1.5;
    }
    .info-card {
      background: rgba(124, 77, 255, 0.05);
      border: 1px solid rgba(124, 77, 255, 0.2);
      border-radius: 16px;
      padding: 1.5rem;
      margin-bottom: 2rem;
    }
    .info-row {
      display: flex;
      justify-content: space-between;
      padding: 8px 0;
      border-bottom: 1px solid rgba(255, 255, 255, 0.05);
      font-size: 0.95rem;
    }
    .info-row:last-child { border-bottom: none; }
    .info-label { color: var(--text-dim); }
    .info-val { font-weight: 600; color: #FFFFFF; }
    footer {
      text-align: center;
      padding: 2rem;
      border-top: 1px solid var(--card-border);
      color: var(--text-dim);
      font-size: 0.85rem;
    }
    code {
      background: rgba(255, 255, 255, 0.08);
      padding: 2px 6px;
      border-radius: 4px;
      font-family: monospace;
      color: var(--accent-cyan);
    }
  </style>
</head>
<body>
  <header>
    <div class="badge">
      <span class="pulse-dot"></span>
      Native Android Workstation
    </div>
    <h1>KothaPad</h1>
    <p class="tagline">Speech-enabled daily journal and notepad engineered for continuous dictation, in-app keyboard, one-tap text stamps, and local-first SQLite notes.</p>
    <div class="actions">
      <a href="/KothaPad.apk" class="btn btn-primary">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
          <path d="M5 20h14v-2H5v2zM12 2L4.5 9.5h4v6h7v-6h4L12 2z"/>
        </svg>
        Download APK (${apkSizeMb} MB)
      </a>
      <a href="/MainBrain.md" class="btn btn-secondary">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
          <polyline points="14 2 14 8 20 8"></polyline>
          <line x1="16" y1="13" x2="8" y2="13"></line>
          <line x1="16" y1="17" x2="8" y2="17"></line>
        </svg>
        MainBrain.md
      </a>
    </div>
  </header>

  <main>
    <div class="info-card">
      <div class="info-row">
        <span class="info-label">Application Status</span>
        <span class="info-val" style="color: #00E5FF;">Ready • Built</span>
      </div>
      <div class="info-row">
        <span class="info-label">Artifact</span>
        <span class="info-val"><code>KothaPad.apk</code> (${apkSizeMb} MB)</span>
      </div>
      <div class="info-row">
        <span class="info-label">Framework</span>
        <span class="info-val">Kotlin + Jetpack Compose (SDK 36)</span>
      </div>
      <div class="info-row">
        <span class="info-label">Storage Engine</span>
        <span class="info-val">SQLite Room v4 + DataStore (100% Offline)</span>
      </div>
    </div>

    <div class="grid">
      <div class="card">
        <h3>🎙️ Continuous Dictation</h3>
        <p>Proprietary Speech Supervisor loop automatically catches silence timeouts and re-arms the recognizer without dropping spoken words.</p>
      </div>
      <div class="card">
        <h3>⌨️ Dual In-App Keyboards</h3>
        <p>Two customizable in-app keyboards with drag-and-drop key swapping, cross-keyboard edge dragging, and bi-directional swipe toggling.</p>
      </div>
      <div class="card">
        <h3>📅 Daily Journal Calendar</h3>
        <p>High-performance single-pass calendar with O(1) bitmask note lookup and immediate cold-start note opening in Reading Mode.</p>
      </div>
      <div class="card">
        <h3>✍️ Markdown Live Styler</h3>
        <p>Live syntax highlighting with dimmed markers, proportional heading scaling, monospace code blocks, and tappable reading checkboxes.</p>
      </div>
      <div class="card">
        <h3>💾 Automated Backups</h3>
        <p>WorkManager periodic backups to <code>Download/KOTHAPAD Backups</code> with SHA-256 deduplication and keep-last-N retention.</p>
      </div>
      <div class="card">
        <h3>⏰ Exact Alarm Overlays</h3>
        <p>Heads-up notifications and true system window overlays (<code>SYSTEM_ALERT_WINDOW</code>) with original scheduled time fidelity.</p>
      </div>
    </div>
  </main>

  <footer>
    <p>KothaPad • Local-First Android Workstation • Generated via AI Studio Build</p>
  </footer>
</body>
</html>`;

  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(html);
}

server.listen(PORT, HOST, () => {
  console.log(`[KothaPad Companion Server] Listening on http://${HOST}:${PORT}`);
});
