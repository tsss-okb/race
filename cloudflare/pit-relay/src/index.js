export class PitRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const key = url.searchParams.get("key") || "";
    const storedKey = await this.state.storage.get("key");

    if (url.pathname === "/ws") {
      if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
        return new Response("Expected WebSocket", { status: 426 });
      }

      const role = url.searchParams.get("role") === "publisher" ? "publisher" : "viewer";
      if (!key) return json({ error: "missing key" }, 401);

      if (role === "publisher") {
        if (storedKey && storedKey !== key) return json({ error: "forbidden" }, 403);
        if (!storedKey) await this.state.storage.put("key", key);
      } else {
        if (!storedKey || storedKey !== key) return json({ error: "forbidden" }, 403);
      }

      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.state.acceptWebSocket(server);
      server.serializeAttachment({ role, key });

      if (role === "viewer") {
        const latest = await this.state.storage.get("latest");
        if (latest) server.send(JSON.stringify(latest));
      }

      return new Response(null, { status: 101, webSocket: client });
    }

    if (request.method === "POST" && url.pathname === "/update") {
      if (!key) return json({ error: "missing key" }, 401);
      if (storedKey && storedKey !== key) return json({ error: "forbidden" }, 403);
      if (!storedKey) await this.state.storage.put("key", key);

      const payload = await request.json().catch(() => null);
      if (!payload || typeof payload !== "object") return json({ error: "bad json" }, 400);

      const record = {
        ...payload,
        relayReceivedAtMs: Date.now(),
      };
      await this.state.storage.put("latest", record);
      return json({ ok: true, receivedAtMs: record.relayReceivedAtMs });
    }

    if (request.method === "GET" && url.pathname === "/state") {
      if (!storedKey || storedKey !== key) return json({ error: "forbidden" }, 403);
      const latest = await this.state.storage.get("latest");
      if (!latest) return json({ error: "no data" }, 404);
      return json(latest, 200, {
        "Cache-Control": "no-store, no-cache, must-revalidate",
      });
    }

    return new Response("Not found", { status: 404 });
  }

  async webSocketMessage(ws, message) {
    const attachment = ws.deserializeAttachment() || {};
    if (attachment.role !== "publisher") return;

    let payload;
    try {
      payload = JSON.parse(typeof message === "string" ? message : new TextDecoder().decode(message));
    } catch (_) {
      return;
    }
    if (!payload || typeof payload !== "object") return;

    const record = {
      ...payload,
      relayReceivedAtMs: Date.now(),
    };

    await this.state.storage.put("latest", record);
    const encoded = JSON.stringify(record);

    for (const peer of this.state.getWebSockets()) {
      try {
        const peerAttachment = peer.deserializeAttachment() || {};
        if (peerAttachment.role === "viewer") peer.send(encoded);
      } catch (_) {}
    }

    try {
      ws.send(JSON.stringify({ type: "ack", relayReceivedAtMs: record.relayReceivedAtMs }));
    } catch (_) {}
  }

  webSocketClose(ws, code, reason) {
    try { ws.close(code, reason); } catch (_) {}
  }

  webSocketError(ws) {
    try { ws.close(1011, "websocket error"); } catch (_) {}
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.split("/").filter(Boolean);

    if (path[0] === "ws" && path[1] === "room" && path[2]) {
      const room = sanitizeRoom(path[2]);
      if (!room) return json({ error: "bad room" }, 400);

      const stub = env.PIT_ROOMS.get(env.PIT_ROOMS.idFromName(room));
      const key = url.searchParams.get("key") || "";
      const role = url.searchParams.get("role") === "publisher" ? "publisher" : "viewer";

      return stub.fetch(
        "https://room/ws?key=" + encodeURIComponent(key) + "&role=" + encodeURIComponent(role),
        { headers: request.headers }
      );
    }

    if (path[0] === "api" && path[1] === "room" && path[2]) {
      const room = sanitizeRoom(path[2]);
      if (!room) return json({ error: "bad room" }, 400);

      const stub = env.PIT_ROOMS.get(env.PIT_ROOMS.idFromName(room));
      const key = url.searchParams.get("key") || "";

      if (request.method === "POST" && path[3] === "update") {
        const body = await request.text();
        return stub.fetch("https://room/update?key=" + encodeURIComponent(key), {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body,
        });
      }

      if (request.method === "GET" && path.length === 3) {
        return stub.fetch("https://room/state?key=" + encodeURIComponent(key));
      }
    }

    if (request.method === "GET" && path[0] === "pit" && path[1]) {
      const room = sanitizeRoom(path[1]);
      const key = url.searchParams.get("key") || "";
      if (!room || !key) return new Response("Bad viewer link", { status: 400 });
      return new Response(viewerHtml(room, key), {
        headers: {
          "Content-Type": "text/html; charset=utf-8",
          "Cache-Control": "no-store",
          "X-Frame-Options": "DENY",
          "Referrer-Policy": "no-referrer",
        },
      });
    }

    if (url.pathname === "/health") {
      return json({ ok: true, service: "RaceLab Pit Relay", transport: "websocket", protocol: 2 });
    }

    return new Response(
      "RaceLab Pit Relay\nOpen /pit/<room>?key=<key>",
      { headers: { "Content-Type": "text/plain; charset=utf-8" } }
    );
  },
};

function sanitizeRoom(value) {
  const room = String(value || "").replace(/[^A-Za-z0-9_-]/g, "").slice(0, 48);
  return room || null;
}

function json(value, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Access-Control-Allow-Origin": "*",
      ...extraHeaders,
    },
  });
}

function viewerHtml(room, key) {
  const roomJs = JSON.stringify(room);
  const keyJs = JSON.stringify(key);
  return `<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>RaceLab Pit Lane · Internet</title>
<style>
:root{color-scheme:dark;--bg:#050607;--panel:#111315;--line:#292d31;--muted:#989da2;--yellow:#f2c300;--green:#58e13e;--red:#ff3b30;--white:#f2f2f2}
*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;background:var(--bg);color:var(--white);font-family:Arial,Helvetica,sans-serif;overflow:hidden}
body{padding:max(10px,env(safe-area-inset-top)) max(12px,env(safe-area-inset-right)) max(10px,env(safe-area-inset-bottom)) max(12px,env(safe-area-inset-left))}
.grid{height:100%;display:grid;grid-template-rows:auto 1fr auto;gap:10px}
.header{display:flex;align-items:center;justify-content:space-between;background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:10px 14px}
.title{font-size:clamp(18px,2.6vw,32px);font-weight:900;letter-spacing:.08em}.status{display:flex;align-items:center;gap:10px;color:var(--muted)}
.dot{width:12px;height:12px;border-radius:50%;background:var(--red);box-shadow:0 0 16px var(--red)}.dot.ok{background:var(--green);box-shadow:0 0 16px var(--green)}
.main{display:grid;grid-template-columns:1.4fr .8fr .8fr;gap:10px;min-height:0}.card{background:linear-gradient(#171a1d,#0b0d0f);border:1px solid var(--line);border-radius:16px;padding:14px;min-width:0}
.label{color:var(--muted);font-size:clamp(11px,1.4vw,18px);font-weight:800;letter-spacing:.08em}.pit{display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center}
.pit.active{border-color:var(--yellow);box-shadow:inset 0 0 0 1px rgba(242,195,0,.35),0 0 26px rgba(242,195,0,.12)}.pitstate{font-size:clamp(20px,3vw,40px);font-weight:900;color:var(--green)}
.pit.active .pitstate{color:var(--yellow)}.bigtime{font-variant-numeric:tabular-nums;font-size:clamp(52px,9vw,138px);line-height:1;font-weight:900;white-space:nowrap;margin:10px 0}
.sub{color:var(--muted);font-size:clamp(12px,1.7vw,22px);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%}.stack{display:grid;grid-template-rows:1fr 1fr;gap:10px;min-height:0}
.metric{display:flex;flex-direction:column;justify-content:center;align-items:center;text-align:center}.value{font-variant-numeric:tabular-nums;font-size:clamp(30px,4.8vw,72px);font-weight:900;white-space:nowrap}
.value.green{color:var(--green)}.value.yellow{color:var(--yellow)}.footer{display:grid;grid-template-columns:repeat(6,1fr);gap:10px}.foot{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:8px 10px;text-align:center;min-width:0}
.foot .v{font-variant-numeric:tabular-nums;font-size:clamp(14px,2.1vw,28px);font-weight:900;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
@media(max-width:700px){.main{grid-template-columns:1fr 1fr}.pit{grid-column:1/-1}.footer{grid-template-columns:repeat(3,1fr)}.footer .foot:nth-child(n+4){display:none}}
</style>
</head>
<body>
<div class="grid">
<div class="header"><div class="title">RACELAB · PIT LANE</div><div class="status"><span id="dot" class="dot"></span><span id="conn">INTERNET…</span></div></div>
<div class="main">
<div id="pitCard" class="card pit"><div id="pitState" class="pitstate">PIT READY</div><div id="pitTime" class="bigtime">00:00.000</div><div id="trigger" class="sub">Ожидание телеметрии</div></div>
<div class="stack"><div class="card metric"><div class="label">LAST</div><div id="last" class="value">—</div></div><div class="card metric"><div class="label">BEST</div><div id="best" class="value green">—</div></div></div>
<div class="stack"><div class="card metric"><div class="label">PIT #</div><div id="count" class="value yellow">0</div></div><div class="card metric"><div class="label">SPEED</div><div><span id="speed" class="value">0</span> <span class="sub">км/ч</span></div></div></div>
</div>
<div class="footer">
<div class="foot"><div class="label">CURRENT LAP</div><div id="lap" class="v">00:00.000</div></div>
<div class="foot"><div class="label">BEST LAP</div><div id="lapBest" class="v">—</div></div>
<div class="foot"><div class="label">DELTA</div><div id="delta" class="v">—</div></div>
<div class="foot"><div class="label">TRACK</div><div id="track" class="v">RaceLab</div></div>
<div class="foot"><div class="label">GPS</div><div id="gps" class="v">0.0 Hz</div></div>
<div class="foot"><div class="label">LATENCY</div><div id="latency" class="v">—</div></div>
</div></div>
<script>
const ROOM=${roomJs}, KEY=${keyJs};
const fmt100=ms=>{if(ms==null)return '—';ms=Math.max(0,Math.round(ms));const m=Math.floor(ms/60000),s=Math.floor((ms%60000)/1000),c=Math.floor((ms%1000)/10);return String(m).padStart(2,'0')+':'+String(s).padStart(2,'0')+'.'+String(c).padStart(2,'0')};
const delta=ms=>ms==null?'—':(ms<0?'−':'+')+(Math.abs(ms)/1000).toFixed(2);

let pitActive=false;
let pitBaseMs=0;
let pitBasePerf=performance.now();
let lastRelayReceivedAt=0;
let pollRunning=false;
let consecutiveErrors=0;

function shownPitMs(){
  return pitActive ? pitBaseMs + (performance.now()-pitBasePerf) : pitBaseMs;
}

function renderClock(){
  document.getElementById('pitTime').textContent=fmt100(shownPitMs());
  const age=lastRelayReceivedAt ? Math.max(0,Date.now()-lastRelayReceivedAt) : 999999;
  const live=age<2500;
  document.getElementById('dot').className=live?'dot ok':'dot';
  document.getElementById('conn').textContent=live?'LIVE':(consecutiveErrors>0?'НЕТ СВЯЗИ':'STALE');
  document.getElementById('latency').textContent=lastRelayReceivedAt ? age+' ms' : '—';
  requestAnimationFrame(renderClock);
}

async function poll(){
  if(pollRunning)return;
  pollRunning=true;
  const controller=new AbortController();
  const timeout=setTimeout(()=>controller.abort(),1200);
  try{
    const r=await fetch('/api/room/'+encodeURIComponent(ROOM)+'?key='+encodeURIComponent(KEY)+'&t='+Date.now(),{
      cache:'no-store',
      signal:controller.signal
    });
    if(!r.ok)throw new Error('HTTP '+r.status);
    const d=await r.json();
    consecutiveErrors=0;
    lastRelayReceivedAt=d.relayReceivedAtMs||Date.now();

    pitActive=!!d.pitActive;
    pitBaseMs=Number(d.pitCurrentMs||0);
    pitBasePerf=performance.now();

    const card=document.getElementById('pitCard');
    card.classList.toggle('active',pitActive);
    document.getElementById('pitState').textContent=pitActive?'PIT ACTIVE':'PIT READY';
    document.getElementById('trigger').textContent=d.pitTrigger||'—';
    document.getElementById('last').textContent=fmt100(d.pitLastMs);
    document.getElementById('best').textContent=fmt100(d.pitBestMs);
    document.getElementById('count').textContent=d.pitCount||0;
    document.getElementById('speed').textContent=Math.round(d.speedKmh||0);
    document.getElementById('lap').textContent=fmt100(d.lapCurrentMs);
    document.getElementById('lapBest').textContent=fmt100(d.lapBestMs);
    const de=document.getElementById('delta');
    de.textContent=delta(d.deltaMs);
    de.style.color=d.deltaMs==null?'#f2f2f2':(d.deltaMs<=0?'#58e13e':'#ff3b30');
    document.getElementById('track').textContent=d.track||'RaceLab';
    document.getElementById('gps').textContent=(d.gpsHz||0).toFixed(1)+' Hz · S'+(d.satellites||0);
  }catch(e){
    consecutiveErrors++;
  }finally{
    clearTimeout(timeout);
    pollRunning=false;
    setTimeout(poll,120);
  }
}
requestAnimationFrame(renderClock);
poll();
</script>
</body></html>`;
}
