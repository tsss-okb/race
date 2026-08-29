package ru.racelab.phone.pitlane

import android.os.SystemClock
import org.json.JSONObject
import ru.racelab.phone.data.RaceRuntime
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object PitLaneServer {
    const val PORT = 8787

    private val running = AtomicBoolean(false)
    private val clients = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = Thread({
            try {
                ServerSocket(PORT).also { server ->
                    server.reuseAddress = true
                    serverSocket = server
                    RaceRuntime.setPitLaneServerStatus(true, urls())
                    while (running.get()) {
                        val socket = runCatching { server.accept() }.getOrNull() ?: break
                        clients.execute { handle(socket) }
                    }
                }
            } catch (_: Throwable) {
                RaceRuntime.setPitLaneServerStatus(false, emptyList())
            } finally {
                running.set(false)
                runCatching { serverSocket?.close() }
                serverSocket = null
                RaceRuntime.setPitLaneServerStatus(false, emptyList())
            }
        }, "RaceLab-PitLane-Server").apply {
            isDaemon = true
            start()
        }
    }

    fun refreshStatus() {
        if (running.get()) RaceRuntime.setPitLaneServerStatus(true, urls())
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        RaceRuntime.setPitLaneServerStatus(false, emptyList())
    }

    fun urls(): List<String> {
        val out = linkedSetOf<String>()
        runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (network in interfaces) {
                if (!network.isUp || network.isLoopback) continue
                for (address in Collections.list(network.inetAddresses)) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val host = address.hostAddress ?: continue
                        if (host.isNotBlank()) out += "http://$host:$PORT"
                    }
                }
            }
        }
        return out.toList()
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 2_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1)?.substringBefore("?") ?: "/"

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
            }

            when {
                method != "GET" -> respond(client, 405, "text/plain; charset=utf-8", "Method Not Allowed")
                path == "/api/pit" -> respond(client, 200, "application/json; charset=utf-8", pitJson())
                path == "/health" -> respond(client, 200, "text/plain; charset=utf-8", "ok")
                else -> respond(client, 200, "text/html; charset=utf-8", pageHtml())
            }
        }
    }

    private fun pitJson(): String {
        val state = RaceRuntime.state.value
        val now = SystemClock.elapsedRealtime()
        val currentPit = if (state.pitTimerActive) RaceRuntime.pitElapsedMs(now) else (state.pitLastMs ?: 0L)
        return JSONObject()
            .put("serverTimeMs", System.currentTimeMillis())
            .put("pitActive", state.pitTimerActive)
            .put("pitCurrentMs", currentPit)
            .put("pitLastMs", state.pitLastMs ?: JSONObject.NULL)
            .put("pitBestMs", state.pitBestMs ?: JSONObject.NULL)
            .put("pitCount", state.pitStopCount)
            .put("pitTrigger", state.pitLastTrigger)
            .put("sessionActive", state.sessionActive)
            .put("armed", state.armed)
            .put("lapCurrentMs", state.lapElapsedMs)
            .put("lapBestMs", state.bestLapMs ?: JSONObject.NULL)
            .put("deltaMs", state.deltaMs ?: JSONObject.NULL)
            .put("speedKmh", state.speedKmh)
            .put("track", state.currentTrackName ?: "RaceLab")
            .put("gpsHz", state.gpsHz)
            .put("satellites", state.satellites)
            .toString()
    }

    private fun respond(socket: Socket, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        val out = socket.getOutputStream()
        out.write(header)
        out.write(bytes)
        out.flush()
    }

    private fun pageHtml(): String = """
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>RaceLab Pit Lane</title>
<style>
:root{color-scheme:dark;--bg:#050607;--panel:#111315;--line:#292d31;--muted:#989da2;--yellow:#f2c300;--green:#58e13e;--red:#ff3b30;--white:#f2f2f2}
*{box-sizing:border-box}
html,body{margin:0;width:100%;height:100%;background:var(--bg);color:var(--white);font-family:Arial,Helvetica,sans-serif;overflow:hidden}
body{padding:max(10px,env(safe-area-inset-top)) max(12px,env(safe-area-inset-right)) max(10px,env(safe-area-inset-bottom)) max(12px,env(safe-area-inset-left))}
.grid{height:100%;display:grid;grid-template-rows:auto 1fr auto;gap:10px}
.header{display:flex;align-items:center;justify-content:space-between;background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:10px 14px}
.title{font-size:clamp(18px,2.6vw,32px);font-weight:900;letter-spacing:.08em}
.status{display:flex;align-items:center;gap:10px;font-size:clamp(12px,1.6vw,20px);color:var(--muted)}
.dot{width:12px;height:12px;border-radius:50%;background:var(--red);box-shadow:0 0 16px var(--red)}
.dot.ok{background:var(--green);box-shadow:0 0 16px var(--green)}
.main{display:grid;grid-template-columns:1.4fr .8fr .8fr;gap:10px;min-height:0}
.card{background:linear-gradient(#171a1d,#0b0d0f);border:1px solid var(--line);border-radius:16px;padding:14px;min-width:0}
.label{color:var(--muted);font-size:clamp(11px,1.4vw,18px);font-weight:800;letter-spacing:.08em}
.pit{display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center}
.pit.active{border-color:var(--yellow);box-shadow:inset 0 0 0 1px rgba(242,195,0,.35),0 0 26px rgba(242,195,0,.12)}
.pitstate{font-size:clamp(20px,3vw,40px);font-weight:900;color:var(--green);letter-spacing:.08em}
.pit.active .pitstate{color:var(--yellow)}
.bigtime{font-variant-numeric:tabular-nums;font-size:clamp(52px,9vw,138px);line-height:1;font-weight:900;white-space:nowrap;margin:10px 0}
.sub{color:var(--muted);font-size:clamp(12px,1.7vw,22px);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%}
.stack{display:grid;grid-template-rows:1fr 1fr;gap:10px;min-height:0}
.metric{display:flex;flex-direction:column;justify-content:center;align-items:center;text-align:center}
.value{font-variant-numeric:tabular-nums;font-size:clamp(30px,4.8vw,72px);font-weight:900;white-space:nowrap}
.value.green{color:var(--green)} .value.yellow{color:var(--yellow)} .value.red{color:var(--red)}
.footer{display:grid;grid-template-columns:repeat(5,1fr);gap:10px}
.foot{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:8px 10px;text-align:center;min-width:0}
.foot .v{font-variant-numeric:tabular-nums;font-size:clamp(16px,2.4vw,32px);font-weight:900;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
@media(max-width:700px){.main{grid-template-columns:1fr 1fr}.pit{grid-column:1/-1}.footer{grid-template-columns:repeat(3,1fr)}.footer .foot:nth-child(n+4){display:none}}
</style>
</head>
<body>
<div class="grid">
  <div class="header">
    <div class="title">RACELAB · PIT LANE</div>
    <div class="status"><span id="dot" class="dot"></span><span id="conn">ПОДКЛЮЧЕНИЕ…</span></div>
  </div>
  <div class="main">
    <div id="pitCard" class="card pit">
      <div id="pitState" class="pitstate">PIT READY</div>
      <div id="pitTime" class="bigtime">00:00.000</div>
      <div id="trigger" class="sub">Ожидание команды</div>
    </div>
    <div class="stack">
      <div class="card metric"><div class="label">LAST</div><div id="last" class="value">—</div></div>
      <div class="card metric"><div class="label">BEST</div><div id="best" class="value green">—</div></div>
    </div>
    <div class="stack">
      <div class="card metric"><div class="label">PIT #</div><div id="count" class="value yellow">0</div></div>
      <div class="card metric"><div class="label">SPEED</div><div><span id="speed" class="value">0</span> <span class="sub">км/ч</span></div></div>
    </div>
  </div>
  <div class="footer">
    <div class="foot"><div class="label">CURRENT LAP</div><div id="lap" class="v">00:00.000</div></div>
    <div class="foot"><div class="label">BEST LAP</div><div id="lapBest" class="v">—</div></div>
    <div class="foot"><div class="label">DELTA</div><div id="delta" class="v">—</div></div>
    <div class="foot"><div class="label">TRACK</div><div id="track" class="v">RaceLab</div></div>
    <div class="foot"><div class="label">GPS</div><div id="gps" class="v">0.0 Hz</div></div>
  </div>
</div>
<script>
const fmt100=ms=>{if(ms==null)return '—';ms=Math.max(0,Math.round(ms));const m=Math.floor(ms/60000),s=Math.floor((ms%60000)/1000),c=Math.floor((ms%1000)/10);return String(m).padStart(2,'0')+':'+String(s).padStart(2,'0')+'.'+String(c).padStart(2,'0')};
const delta=ms=>ms==null?'—':(ms<0?'−':'+')+(Math.abs(ms)/1000).toFixed(2);
let pitActive=false,pitBaseMs=0,pitBasePerf=performance.now(),lastOk=0,pollRunning=false;

function shownPitMs(){return pitActive?pitBaseMs+(performance.now()-pitBasePerf):pitBaseMs;}
function renderClock(){
  document.getElementById('pitTime').textContent=fmt100(shownPitMs());
  const live=Date.now()-lastOk<2000;
  document.getElementById('dot').className=live?'dot ok':'dot';
  document.getElementById('conn').textContent=live?'LIVE':'НЕТ СВЯЗИ';
  requestAnimationFrame(renderClock);
}

async function poll(){
  if(pollRunning)return;
  pollRunning=true;
  const controller=new AbortController();
  const timeout=setTimeout(()=>controller.abort(),1000);
  try{
    const r=await fetch('/api/pit?t='+Date.now(),{cache:'no-store',signal:controller.signal});
    if(!r.ok)throw new Error();
    const d=await r.json();
    lastOk=Date.now();
    pitActive=!!d.pitActive;
    pitBaseMs=Number(d.pitCurrentMs||0);
    pitBasePerf=performance.now();
    const card=document.getElementById('pitCard');
    card.classList.toggle('active',pitActive);
    document.getElementById('pitState').textContent=pitActive?'PIT ACTIVE':'PIT READY';
    document.getElementById('trigger').textContent=d.pitTrigger||'—';
    document.getElementById('last').textContent=fmt100(d.pitLastMs);
    document.getElementById('best').textContent=fmt100(d.pitBestMs);
    document.getElementById('count').textContent=d.pitCount;
    document.getElementById('speed').textContent=Math.round(d.speedKmh||0);
    document.getElementById('lap').textContent=fmt100(d.lapCurrentMs);
    document.getElementById('lapBest').textContent=fmt100(d.lapBestMs);
    const de=document.getElementById('delta');
    de.textContent=delta(d.deltaMs);
    de.style.color=d.deltaMs==null?'#f2f2f2':(d.deltaMs<=0?'#58e13e':'#ff3b30');
    document.getElementById('track').textContent=d.track||'RaceLab';
    document.getElementById('gps').textContent=(d.gpsHz||0).toFixed(1)+' Hz · S'+(d.satellites||0);
  }catch(e){}finally{
    clearTimeout(timeout);
    pollRunning=false;
    setTimeout(poll,100);
  }
}
requestAnimationFrame(renderClock);
poll();
</script>
</body>
</html>
""".trimIndent()
}
