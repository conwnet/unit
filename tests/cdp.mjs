// Evaluate the active Android WebView after its document is ready, without changing its viewport.
// Usage: node tests/cdp.mjs 9224 '({width: innerWidth, height: innerHeight})'
const [port = '9224', expression = 'location.href', url] = process.argv.slice(2);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function connect() {
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`, { signal: AbortSignal.timeout(1500) })).json();
  const target = targets.find(t => {
    try { const info = JSON.parse(t.description); return info.attached && !info.empty && (!url || t.url === url); }
    catch { return false; }
  });
  if (!target) throw new Error('No active WebView');
  const socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => { socket.close(); reject(new Error('CDP connection timeout')); }, 1500);
    socket.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
    socket.addEventListener('error', error => { clearTimeout(timer); reject(error); }, { once: true });
  });
  return socket;
}

function evaluate(socket, id, code, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { socket.removeEventListener('message', receive); reject(new Error('CDP response timeout')); }, timeoutMs);
    function receive(event) {
      const message = JSON.parse(event.data);
      if (message.id !== id) return;
      clearTimeout(timer);
      socket.removeEventListener('message', receive);
      if (message.error) reject(new Error(message.error.message));
      else resolve(message.result);
    }
    socket.addEventListener('message', receive);
    socket.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression: code, returnByValue: true, awaitPromise: true, allowUnsafeEvalBlockedByCSP: false } }));
  });
}

let active;
const deadline = Date.now() + 12000;
while (Date.now() < deadline) {
  let socket;
  try {
    socket = await connect();
    const ready = await evaluate(socket, 1, 'document.readyState', 1000);
    if (ready.result?.value === 'complete') { active = socket; break; }
  } catch { /* Android may be replacing the renderer or activity during this read. */ }
  socket?.close();
  await delay(250);
}
if (!active) throw new Error('Active WebView did not become ready; check adb forwarding and page load.');
try {
  // Execute the requested expression once; only the readiness check is retried.
  console.log(JSON.stringify(await evaluate(active, 2, expression, 10000)));
} finally { active.close(); }
