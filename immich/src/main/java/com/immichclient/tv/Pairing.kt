package com.immichclient.tv

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Translatable message: string resource + arguments, formatted in either the TV's or the phone's language. */
class LocalizedText(@StringRes val id: Int, vararg val args: Any) {
    fun format(res: Resources): String = res.getString(id, *args)
    /** In the app language (for the TV screen). */
    override fun toString() = format(Language.ctx.resources)
}

/**
 * Pair with an Immich server via the phone. The TV shows a QR code with http://<box-ip>:<port>/<code>;
 * while the pairing screen is open, a tiny HTTP server (own ServerSocket) serves a single mobile page in the
 * phone's language (Accept-Language) if supported, otherwise in the app language.
 * The phone sends back server address + e-mail + password (or an existing key); the app handles the pairing itself
 * (ping, version, login, create API key, logout). The password is never stored or logged. Without the code from
 * the QR, the server returns 403; it stops as soon as pairing succeeds or the screen closes.
 */
object Pairing {
    private const val TAG = "Photos"
    private val PORTS = 8791..8799
    /** Exact permissions the app needs (read-only); see x-immich-permission in the OpenAPI spec. */
    val PERMISSIONS = listOf("asset.read", "asset.view", "album.read", "memory.read", "person.read", "map.read", "user.read", "folder.read")

    sealed class Status {
        data object Waiting : Status()
        class Busy(val step: LocalizedText) : Status()
        class Done(val name: String, val server: String, val warning: LocalizedText?) : Status()
        class Error(val message: LocalizedText) : Status()
    }

    var status by mutableStateOf<Status>(Status.Waiting)
        private set
    /** URL for the QR code, null while there is no network or no port could be opened. */
    var url by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private var code = ""
    private var test = false
    private var app: Context? = null

    /** Standalone client without key interceptor: short timeouts, since each step is reported to the phone. */
    private val bare = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    // ---- mini server ----

    fun start(c: Context, test: Boolean) {
        stop()
        app = c.applicationContext
        this.test = test
        status = Status.Waiting
        url = null
        code = newCode()
        val ip = localIp()
        val ss = PORTS.firstNotNullOfOrNull { p -> runCatching { ServerSocket(p) }.getOrNull() }
        if (ip == null || ss == null) {
            Log.w(TAG, "pairing: no network ($ip) or no free port")
            url = null
            status = Status.Error(LocalizedText(if (ip == null) R.string.err_no_network else R.string.err_no_port))
            return
        }
        server = ss
        url = "http://$ip:${ss.localPort}/$code"
        Log.i(TAG, "pairing: waiting at $url")
        thread(name = "Pairing", isDaemon = true) {
            while (true) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break // closed by stop()
                runCatching { handle(s) }.onFailure { e -> Log.w(TAG, "pairing: request", e) }
                runCatching { s.close() }
            }
            Log.i(TAG, "pairing: server stopped")
        }
    }

    /** Close the server; [url] stays (after "Done" the screen hides the QR, but keeps showing the address). */
    fun stop() {
        server?.let { runCatching { it.close() } }
        server = null
    }

    private fun handle(s: Socket) {
        s.soTimeout = 10_000
        // local network only (the code already protects it; this is an extra lock)
        val addr = s.inetAddress
        if (!(addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress)) { respond(s.getOutputStream(), 403, "text/plain", Language.s(R.string.no_access)); return }
        val input = s.getInputStream().buffered()
        val out = s.getOutputStream()
        val first = readLine(input) ?: return
        val parts = first.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?').trimEnd('/')
        var length = 0
        var acceptLanguage: String? = null
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            if (line.startsWith("Accept-Language:", ignoreCase = true)) acceptLanguage = line.substringAfter(':').trim()
        }
        val body = ByteArray(length.coerceAtMost(64 * 1024))
        var bytesRead = 0
        while (bytesRead < body.size) { val n = input.read(body, bytesRead, body.size - bytesRead); if (n < 0) break; bytesRead += n }
        // phone's language (if supported), otherwise the app's language
        val lang = Language.fromAcceptLanguage(acceptLanguage)
        val res = Language.resources(lang)

        when {
            method == "GET" && path == "/$code" -> respond(out, 200, "text/html; charset=utf-8", page(lang, res))
            method == "POST" && path == "/$code/pair" -> {
                val json = runCatching { JSONObject(String(body, 0, bytesRead, Charsets.UTF_8)) }.getOrElse { JSONObject() }
                val r = pair(json, res)
                respond(out, 200, "application/json; charset=utf-8", r.toString())
                if (r.optBoolean("ok")) stop() // done: accept nothing more
            }
            else -> respond(out, 403, "text/html; charset=utf-8", "<!doctype html><meta charset=utf-8><body style=\"background:#0a0a0a;color:#a1a1aa;font-family:sans-serif;padding:40px 20px;text-align:center\">${res.getString(R.string.no_access_html)}</body>")
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; else -> "Error" }
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun newCode(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789" // no l/1/o/0: readable when typed manually
        val r = SecureRandom()
        return String(CharArray(6) { chars[r.nextInt(chars.length)] })
    }

    /** IPv4 address of the active interface (Wi-Fi or Ethernet), not loopback. */
    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { n -> when { n.name.startsWith("eth") -> 0; n.name.startsWith("wlan") -> 1; else -> 2 } }
            .firstNotNullOfOrNull { n -> n.inetAddresses.toList().firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress }
    }.getOrNull()

    // ---- pairing logic ----

    private class PairingException(val text: LocalizedText) : Exception(text.toString())

    /** "192.168.1.10:2283/" -> "http://192.168.1.10:2283"; "https://photos.example.com/api" -> "https://photos.example.com" */
    fun normalize(address: String): String {
        var a = address.trim().trimEnd('/')
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
        if (a.endsWith("/api")) a = a.removeSuffix("/api")
        return a.trimEnd('/')
    }

    private fun setStatus(s: Status) = main.post { status = s }

    /** Processes the form from the phone; returns {ok, title, message} in the phone's language [res]. Runs on the server thread. */
    private fun pair(json: JSONObject, res: Resources): JSONObject {
        val c = app ?: return JSONObject().put("ok", false).put("title", res.getString(R.string.failed)).put("message", res.getString(R.string.err_app_inactive))
        val serverIn = json.optString("server").trim()
        val keyIn = json.optString("key").trim()
        val email = json.optString("email").trim()
        val password = json.optString("password")
        if (serverIn.isEmpty()) return errorJson(LocalizedText(R.string.err_enter_address), res)
        val base = normalize(serverIn)
        return try {
            val warning = checkServer(base)
            val (key, name) = if (keyIn.isNotEmpty()) {
                setStatus(Status.Busy(LocalizedText(R.string.status_checking_key_perms)))
                keyIn to checkPermissions(base, keyIn, res)
            } else {
                if (email.isEmpty() || password.isEmpty()) throw PairingException(LocalizedText(R.string.err_enter_email))
                createKey(base, email, password)
            }
            Immich.setKey(c, key, null, base)
            main.post { Immich.selectAccount(c, null) }
            Log.i(TAG, "pairing: succeeded with $base" + if (test) " (test)" else "")
            setStatus(Status.Done(name, base, warning))
            JSONObject().put("ok", true).put("title", res.getString(R.string.pair_done_title))
                .put("message", res.getString(R.string.connected_message, name, base) + (warning?.let { "\n\n${it.format(res)}" } ?: ""))
        } catch (e: PairingException) {
            Log.i(TAG, "pairing: ${e.message}")
            errorJson(e.text, res)
        } catch (e: Exception) {
            Log.w(TAG, "pairing", e)
            errorJson(LocalizedText(R.string.err_something_wrong, "${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()), res)
        }
    }

    private fun errorJson(message: LocalizedText, res: Resources): JSONObject {
        setStatus(Status.Error(message))
        return JSONObject().put("ok", false).put("title", res.getString(R.string.pair_failed)).put("message", message.format(res))
    }

    private fun request(url: String, bearer: String? = null, key: String? = null, body: JSONObject? = null): Request =
        Request.Builder().url(url).apply {
            header("Accept", "application/json")
            bearer?.let { header("Authorization", "Bearer $it") }
            key?.let { header("x-api-key", it) }
            if (body != null) post(body.toString().toRequestBody("application/json".toMediaType()))
        }.build()

    private class Response(val code: Int, val body: String)

    private fun call(req: Request): Response = try {
        bare.newCall(req).execute().use { Response(it.code, it.body?.string().orEmpty()) }
    } catch (e: javax.net.ssl.SSLException) {
        throw PairingException(LocalizedText(R.string.err_ssl, req.url.host))
    } catch (e: java.net.UnknownHostException) {
        throw PairingException(LocalizedText(R.string.err_host, req.url.host))
    } catch (e: java.io.IOException) {
        throw PairingException(LocalizedText(R.string.err_connection, req.url.host, req.url.port))
    }

    /** Ping + version. Returns a warning (or null) if the server is older than what the app was tested against. */
    private fun checkServer(base: String): LocalizedText? {
        setStatus(Status.Busy(LocalizedText(R.string.status_looking_for_server, base)))
        val ping = call(request("$base/api/server/ping"))
        val pong = runCatching { JSONObject(ping.body).optString("res") }.getOrNull()
        if (ping.code != 200 || pong != "pong") throw PairingException(
            if (ping.code == 200) LocalizedText(R.string.err_no_pong, base) else LocalizedText(R.string.err_no_immich, base, ping.code)
        )
        val v = runCatching { JSONObject(call(request("$base/api/server/version")).body) }.getOrNull()
        val major = v?.optInt("major", -1) ?: -1
        val version = v?.let { "${it.optInt("major")}.${it.optInt("minor")}.${it.optInt("patch")}" } ?: "?"
        Log.i(TAG, "pairing: Immich $version at $base")
        // The app uses the columnar timeline (/timeline/bucket) and /memories as in Immich 2 and 3; tested with 3.3.
        if (major in 0..1) throw PairingException(LocalizedText(R.string.err_too_old, version))
        return if (major == 2) LocalizedText(R.string.warn_version, version) else null
    }

    /** Log in, create key with exact read permissions, log out. Returns (key, name). */
    private fun createKey(base: String, email: String, password: String): Pair<String, String> {
        setStatus(Status.Busy(LocalizedText(R.string.status_signing_in, email)))
        val login = call(request("$base/api/auth/login", body = JSONObject().put("email", email).put("password", password)))
        if (login.code == 401) throw PairingException(LocalizedText(R.string.err_login))
        if (login.code == 400) throw PairingException(LocalizedText(R.string.err_login_address))
        if (login.code !in 200..299) throw PairingException(LocalizedText(R.string.err_login_http, login.code))
        val lo = JSONObject(login.body)
        val token = lo.optString("accessToken")
        if (token.isEmpty()) throw PairingException(LocalizedText(R.string.err_no_token))
        val name = lo.optString("name").ifBlank { email }
        try {
            setStatus(Status.Busy(LocalizedText(R.string.status_creating_key)))
            // name of the key in Immich, in the app language
            val keyName = Language.s(R.string.key_name, deviceName()) + if (test) " (test)" else ""
            val body = JSONObject().put("name", keyName).put("permissions", JSONArray(PERMISSIONS))
            val create = call(request("$base/api/api-keys", bearer = token, body = body))
            if (create.code == 403) throw PairingException(LocalizedText(R.string.err_no_keys_allowed))
            if (create.code !in 200..299) throw PairingException(LocalizedText(R.string.err_key_http, create.code, create.body.take(120)))
            val key = JSONObject(create.body).optString("secret")
            if (key.isEmpty()) throw PairingException(LocalizedText(R.string.err_no_key_returned))
            Log.i(TAG, "pairing: key '$keyName' created")
            // immediately verify that the key works
            setStatus(Status.Busy(LocalizedText(R.string.status_checking_key)))
            val me = call(request("$base/api/users/me", key = key))
            if (me.code != 200) throw PairingException(LocalizedText(R.string.err_key_not_working, me.code))
            return key to name
        } finally {
            // always close the login session; the app only uses the API key afterwards
            runCatching { call(request("$base/api/auth/logout", bearer = token, body = JSONObject())) }
        }
    }

    /** Existing key: test each permission by calling its endpoint. Returns the user's name. */
    private fun checkPermissions(base: String, key: String, res: Resources): String {
        val missing = mutableListOf<String>()
        var name = ""
        fun tryEndpoint(perm: String, path: String): JSONObject? {
            val a = call(request("$base/api$path", key = key))
            when (a.code) {
                401 -> throw PairingException(LocalizedText(R.string.err_key_rejected, base))
                403 -> { missing += perm; return null }
                in 200..299 -> return if (a.body.startsWith("{")) JSONObject(a.body) else JSONObject().put("list", a.body)
                else -> throw PairingException(LocalizedText(R.string.err_unexpected, a.code, path))
            }
        }
        tryEndpoint("user.read", "/users/me")?.let { name = it.optString("name") }
        val buckets = tryEndpoint("asset.read", "/timeline/buckets?visibility=timeline&order=desc")
        tryEndpoint("album.read", "/albums")
        tryEndpoint("memory.read", "/memories?for=${LocalDate.now()}")
        tryEndpoint("person.read", "/people?size=1")
        tryEndpoint("map.read", "/map/markers?fileCreatedAfter=2999-01-01T00:00:00.000Z") // empty response, still checks permission
        // asset.view: request a real thumbnail; cannot be tested without photos on the server
        val firstMonth = runCatching { JSONArray(buckets?.optString("list").orEmpty()).getJSONObject(0).getString("timeBucket") }.getOrNull()
        if (firstMonth != null) {
            val bucket = tryEndpoint("asset.read", "/timeline/bucket?visibility=timeline&order=desc&timeBucket=$firstMonth")
            val id = bucket?.optJSONArray("id")?.optString(0)
            if (!id.isNullOrEmpty()) tryEndpoint("asset.view", "/assets/$id/thumbnail?size=thumbnail")
        }
        if (missing.isNotEmpty()) throw PairingException(LocalizedText(R.string.err_permissions, missing.distinct().joinToString(", "), PERMISSIONS.joinToString(", ")))
        return name.ifBlank { res.getString(R.string.unknown_user) }
    }

    private fun deviceName(): String {
        val c = app
        val name = c?.let { runCatching { Settings.Global.getString(it.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull() }
        return name?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    // ---- QR ----

    /** QR code as bitmap (black on white), one module per pixel; the view scales without blurring. */
    fun qr(text: String): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
        val b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        b.setPixels(px, 0, m.width, 0, 0, m.width, m.height)
        return b
    }

    // ---- phone page ----

    private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Page in language [lang]: all texts from string resources; JavaScript texts are passed as JSON. */
    private fun page(lang: String, res: Resources): String {
        fun t(id: Int) = html(res.getString(id))
        val js = JSONObject()
            .put("busyTitle", res.getString(R.string.html_connecting_title)).put("busy", res.getString(R.string.html_connecting))
            .put("noConnTitle", res.getString(R.string.html_no_connection_title)).put("noConn", res.getString(R.string.html_no_connection))
        return PAGE_HTML
            .replace("%LANG%", lang)
            .replace("%TITLE%", t(R.string.html_title)).replace("%HEADING%", t(R.string.html_heading)).replace("%SUB%", t(R.string.html_sub))
            .replace("%ADDRESS%", t(R.string.html_address)).replace("%ADDR_EX%", t(R.string.html_address_example)).replace("%ADDR_EX_SHORT%", t(R.string.html_address_example_short))
            .replace("%ADDR_HINT%", t(R.string.html_address_hint)).replace("%EMAIL%", t(R.string.html_email)).replace("%PW%", t(R.string.html_password))
            .replace("%PW_HINT%", t(R.string.html_password_hint)).replace("%CONNECT%", t(R.string.html_connect))
            .replace("%KEY_HEADING%", t(R.string.html_key_heading)).replace("%KEY%", t(R.string.html_key))
            .replace("%KEY_HINT_BEFORE%", t(R.string.html_key_hint_before)).replace("%KEY_PATH%", t(R.string.html_key_path)).replace("%KEY_HINT_AFTER%", t(R.string.html_key_hint_after))
            .replace("%CONNECT_KEY%", t(R.string.html_connect_key)).replace("%FOOTER%", t(R.string.html_footer))
            .replace("%PERMS%", PERMISSIONS.joinToString("") { "<li>$it</li>" })
            .replace("%JS%", js.toString())
    }

    // No $ in the JavaScript/CSS below (Kotlin string).
    private val PAGE_HTML = """<!doctype html>
<html lang="%LANG%"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="robots" content="noindex"><title>%TITLE%</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;background:#0a0a0a;color:#e5e7eb;font:16px/1.45 -apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;padding:24px 16px calc(32px + env(safe-area-inset-bottom));max-width:520px;margin:0 auto}
.head{display:flex;align-items:center;gap:14px;margin:6px 0 22px}
.head svg{width:44px;height:44px;flex:none}
h1{font-size:22px;margin:0;font-weight:600;line-height:1.2}
.head p{margin:3px 0 0;color:#a1a1aa;font-size:14px}
.card{background:#1b1b1d;border-radius:16px;padding:20px 18px;margin-bottom:14px}
label{display:block;font-size:13px;color:#a1a1aa;margin:16px 0 6px}
form > label:first-child{margin-top:0}
input{width:100%;font:inherit;color:#fff;background:#262629;border:1px solid #3a3a3f;border-radius:10px;padding:12px 14px;outline:none;-webkit-appearance:none}
input:focus{border-color:#adcbfa;box-shadow:0 0 0 3px rgba(173,203,250,.2)}
.hint{font-size:12.5px;color:#71717a;margin:6px 0 0;line-height:1.4}
button{width:100%;margin-top:20px;font:inherit;font-weight:600;color:#0b1a3a;background:#adcbfa;border:0;border-radius:10px;padding:14px;cursor:pointer}
button:disabled{opacity:.55}
details summary{cursor:pointer;color:#adcbfa;font-size:15px;list-style:none;padding:2px 0}
summary::-webkit-details-marker{display:none}
summary::before{content:"▸  "}
details[open] summary::before{content:"▾  "}
details form{margin-top:14px}
.perms{font-size:13px;margin:8px 0 0;padding:0;list-style:none;display:grid;grid-template-columns:1fr 1fr;gap:4px 10px;color:#d4d4d8}
.perms li::before{content:"✓ ";color:#adcbfa}
code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px}
#out{display:none;border-radius:16px;padding:18px;margin:0 0 14px;font-size:15px;white-space:pre-line}
#out.ok{display:block;background:#143420;color:#bbf7d0}
#out.err{display:block;background:#3b1414;color:#fecaca}
#out.busy{display:block;background:#1b1b1d;color:#e5e7eb}
#out b{display:block;font-size:17px;margin-bottom:4px}
.spin{display:inline-block;width:16px;height:16px;border:2px solid #adcbfa;border-top-color:transparent;border-radius:50%;animation:d .8s linear infinite;vertical-align:-3px;margin-right:8px}
@keyframes d{to{transform:rotate(360deg)}}
.footer{color:#52525b;font-size:12px;text-align:center;margin-top:22px;line-height:1.4}
</style></head><body>
<div class="head">
<svg viewBox="0 0 48 48" fill="none"><rect x="4" y="8" width="40" height="32" rx="7" fill="#1b1b1d" stroke="#adcbfa" stroke-width="2.5"/><circle cx="17" cy="19" r="4" fill="#adcbfa"/><path d="M8 36l10-11 7 8 6-6 9 9" stroke="#adcbfa" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round"/></svg>
<div><h1>%HEADING%</h1><p>%SUB%</p></div>
</div>
<div id="out"></div>
<form id="f1" class="card" autocomplete="on">
<label for="server">%ADDRESS%</label>
<input id="server" name="server" inputmode="url" autocapitalize="none" autocorrect="off" spellcheck="false" placeholder="%ADDR_EX%" required>
<p class="hint">%ADDR_HINT%</p>
<label for="email">%EMAIL%</label>
<input id="email" name="email" type="email" autocomplete="username" autocapitalize="none" required>
<label for="pw">%PW%</label>
<input id="pw" name="pw" type="password" autocomplete="current-password" required>
<p class="hint">%PW_HINT%</p>
<button type="submit">%CONNECT%</button>
</form>
<div class="card"><details><summary>%KEY_HEADING%</summary>
<form id="f2">
<label for="server2">%ADDRESS%</label>
<input id="server2" inputmode="url" autocapitalize="none" autocorrect="off" spellcheck="false" placeholder="%ADDR_EX_SHORT%" required>
<label for="key">%KEY%</label>
<input id="key" autocapitalize="none" autocorrect="off" spellcheck="false" required>
<p class="hint">%KEY_HINT_BEFORE% <b>%KEY_PATH%</b> %KEY_HINT_AFTER%</p>
<ul class="perms">%PERMS%</ul>
<button type="submit">%CONNECT_KEY%</button>
</form></details></div>
<p class="footer">%FOOTER%</p>
<script>
var T=%JS%;
var out=document.getElementById('out');
function show(k,title,text){out.className=k;out.innerHTML='';var b=document.createElement('b');if(k==='busy'){var s=document.createElement('span');s.className='spin';b.appendChild(s);}b.appendChild(document.createTextNode(title));out.appendChild(b);out.appendChild(document.createTextNode(text||''));if(k!=='busy')out.scrollIntoView({behavior:'smooth',block:'start'});}
function send(body,btns){
  for(var i=0;i<btns.length;i++)btns[i].disabled=true;
  show('busy',T.busyTitle,T.busy);
  fetch(location.pathname.replace(/\/+${'$'}/,'')+'/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})
  .then(function(r){return r.json();})
  .then(function(j){show(j.ok?'ok':'err',j.title,j.message);if(j.ok){document.getElementById('f1').style.display='none';document.getElementById('f2').style.display='none';}})
  .catch(function(){show('err',T.noConnTitle,T.noConn);})
  .then(function(){for(var i=0;i<btns.length;i++)btns[i].disabled=false;});
}
var btns=document.querySelectorAll('button');
document.getElementById('f1').addEventListener('submit',function(e){e.preventDefault();send({server:document.getElementById('server').value,email:document.getElementById('email').value,password:document.getElementById('pw').value},btns);});
document.getElementById('f2').addEventListener('submit',function(e){e.preventDefault();send({server:document.getElementById('server2').value,key:document.getElementById('key').value},btns);});
</script>
</body></html>
"""
}
