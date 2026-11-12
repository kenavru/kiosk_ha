package com.example.kiosk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-screen kiosk app wired to Home Assistant.
 *
 * Lockdown:
 * - Registered as a HOME app, so the HOME key returns here instead of
 *   leaving the kiosk. stateNotNeeded makes the system restart it if it
 *   ever dies -- it behaves like the stock launcher.
 * - Lockscreen/keyguard is disabled (DISABLE_KEYGUARD permission) and
 *   the window shows through/over any keyguard that reappears.
 * - BACK and long-press-HOME (APP_SWITCH / recents) are swallowed;
 *   5 quick BACK presses exit (secret unlock combo) and re-arm the keyguard.
 * - Re-launches itself from onPause() if anything else steals the foreground.
 *
 * Home Assistant:
 * - Reads kiosk_config.json from /sdcard, falling back to
 *   /data/local/tmp (2.3 unmounts the sdcard while USB is plugged):
 *     {"url":"http://192.168.1.10:8123",
 *      "token":"<long-lived access token>",
 *      "entities":["sensor.temperature","switch.pump", ...],
 *      "interval":5}          (seconds, optional, default 5)
 * - A worker thread polls GET /api/states and pushes the filtered list
 *   into the page via HA.update(...).
 * - The page calls kiosk.send("<domain>/<service>", "<entity_id>") which
 *   POSTs /api/services/<domain>/<service>.
 */
public class KioskActivity extends Activity {

    /** Key introduced in API 9 (long-press HOME -> recent apps dialog). */
    private static final int KEYCODE_APP_SWITCH_LOCAL = 187;

    private static final String TAG = "kiosk";

    private static final String DASHBOARD_URL = "file:///android_asset/kiosk.html";
    /** Tried in order: sdcard first, then adb-writable fallback
     *  (on 2.3 the sdcard is unmounted from the phone while USB is plugged). */
    private static final String[] CONFIG_PATHS = {
            "/sdcard/kiosk_config.json",
            "/data/local/tmp/kiosk_config.json",
    };

    private static final int    TAPS_TO_EXIT     = 5;
    private static final long   TAP_WINDOW_MS    = 3000;
    private static final int    HTTP_TIMEOUT_MS  = 4000;

    private WebView webView;
    private KeyguardManager.KeyguardLock keyguardLock;
    private boolean exiting = false;

    private long  lastBackPress = 0;
    private int   backPressCount = 0;

    // Home Assistant config (null url => no config, page just shows the clock)
    private String haUrl;
    private String haToken;
    private List<String> wantedEntities =
            java.util.Collections.synchronizedList(new ArrayList<String>());
    private int pollSeconds = 5;

    // Speaker/announcement server (HA pushes audio to this phone)
    private boolean listenEnabled = true;
    private int listenPort = 8090;
    private String ttsEntity = "tts.google_translate_entity";
    private String mediaPlayerEntity = "media_player.kiosk";
    private Thread serverThread;
    private ServerSocket serverSocket;
    private MediaPlayer mediaPlayer;
    private File audioTemp;
    private FileInputStream audioStream;
    private TextToSpeech tts;
    private volatile boolean ttsReady;

    private Thread pollThread;
    private volatile Handler workerHandler;
    private volatile boolean stopPoll = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
              | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
              | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
              | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        setContentView(R.layout.activity_kiosk);
        webView = (WebView) findViewById(R.id.webview);

        // No scrollbars anywhere -- the Metro hub stays clean; the page
        // still scrolls by touch.
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        try { // API 9+, guarded for API 8 devices
            webView.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        } catch (Throwable ignored) {}

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Bridge for the dashboard page (trusted local asset only).
        webView.addJavascriptInterface(this, "kiosk");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (haUrl == null) {
                    view.loadUrl("javascript:HA.noConfig()");
                }
            }
        });

        if (loadConfig()) {
            pollThread = startPolling();
            if (listenEnabled) {
                serverThread = startHttpServer();
            }
        }

        // Phone's own TTS engine for /say (works offline, no HA needed).
        try {
            tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                        int r = tts.setLanguage(new java.util.Locale("pl", "PL"));
                        ttsReady = r != TextToSpeech.LANG_MISSING_DATA
                                && r != TextToSpeech.LANG_NOT_SUPPORTED;
                        Log.i(TAG, "local TTS ready=" + ttsReady + " lang=" + r);
                    } else {
                        Log.w(TAG, "local TTS engine unavailable (" + status + ")");
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "no local TTS: " + e);
        }

        webView.loadUrl(DASHBOARD_URL);
    }

    // ------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------

    private boolean loadConfig() {
        String[] paths = configPaths();
        for (int p = 0; p < paths.length; p++) {
            try {
                byte[] buf = readAll(new FileInputStream(paths[p]));
                JSONObject cfg = new JSONObject(new String(buf, "UTF-8"));
                haUrl   = cfg.getString("url");
                haToken = cfg.getString("token");
                JSONArray ents = cfg.optJSONArray("entities");
                if (ents != null) {
                    for (int i = 0; i < ents.length(); i++) {
                        wantedEntities.add(ents.getString(i));
                    }
                }
                pollSeconds = Math.max(2, cfg.optInt("interval", 5));
                listenEnabled = cfg.optBoolean("listen", true);
                listenPort    = cfg.optInt("listen_port", 8090);
                ttsEntity     = cfg.optString("tts_entity", ttsEntity);
                mediaPlayerEntity = cfg.optString("media_player", mediaPlayerEntity);
                Log.i(TAG, "config loaded from " + paths[p]);
                return true;
            } catch (Exception ignored) {}
        }
        haUrl = null;
        return false;
    }

    /** Web-page saves land in app-private storage, which wins over the
     *  adb-pushed files. */
    private String[] configPaths() {
        String[] all = new String[CONFIG_PATHS.length + 1];
        all[0] = new File(getFilesDir(), "kiosk_config.json").getAbsolutePath();
        System.arraycopy(CONFIG_PATHS, 0, all, 1, CONFIG_PATHS.length);
        return all;
    }

    // ------------------------------------------------------------------
    // Polling / service calls (all HTTP happens off the UI thread)
    // ------------------------------------------------------------------

    private Thread startPolling() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                workerHandler = new Handler(Looper.myLooper());
                Looper.loop(); // service calls get queued here
            }
        }, "kiosk-ha");
        t.start();

        Thread poller = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!stopPoll) {
                    try {
                        final String filtered = pollStates();
                        Log.i(TAG, "HA ok, pushed " + filtered.length() + " bytes to page");
                        webView.post(new Runnable() {
                            public void run() {
                                webView.loadUrl("javascript:HA.update(" + filtered + ")");
                            }
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "HA unreachable: " + e);
                        webView.post(new Runnable() {
                            public void run() {
                                webView.loadUrl("javascript:HA.offline()");
                            }
                        });
                    }
                    try { Thread.sleep(pollSeconds * 1000L); }
                    catch (InterruptedException ignored) { return; }
                }
            }
        }, "kiosk-poll");
        poller.start();
        return poller;
    }

    /** Few selected entities? Fetch just those (tiny requests, allows a
     *  fast interval). Otherwise grab the full state list once. */
    private String pollStates() throws Exception {
        ArrayList<String> ids = new ArrayList<String>();
        synchronized (wantedEntities) { ids.addAll(wantedEntities); }
        if (ids.isEmpty() || ids.size() > 60) {
            return filterStates(new JSONArray(http("GET", haUrl + "/api/states", null)));
        }
        JSONArray arr = new JSONArray();
        for (int i = 0; i < ids.size(); i++) {
            try {
                arr.put(trimState(new JSONObject(
                        http("GET", haUrl + "/api/states/" + ids.get(i), null))));
            } catch (Exception ignored) {}   // entity deleted/renamed since save
        }
        if (arr.length() == 0) throw new IOException("no states fetched");
        return arr.toString();
    }

    /** Keep only configured entities, trimmed to what the page needs. */
    private String filterStates(JSONArray states) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < states.length(); i++) {
            JSONObject s = states.optJSONObject(i);
            if (s == null) continue;
            String id = s.optString("entity_id");
            if (!wantedEntities.isEmpty() && !wantedEntities.contains(id)) continue;
            if (wantedEntities.isEmpty() && !isInteresting(id)) continue;
            out.put(trimState(s));
        }
        return out.toString();
    }

    /** Trim one state object to what the dashboard page needs. */
    private JSONObject trimState(JSONObject s) {
        JSONObject keep = new JSONObject();
        try {
            String id = s.getString("entity_id");
            keep.put("entity_id", id);
            keep.put("state", s.optString("state"));
            JSONObject attrs = s.optJSONObject("attributes");
            if (attrs != null) {
                keep.put("name", attrs.optString("friendly_name", id));
                if (attrs.has("unit_of_measurement")) {
                    keep.put("unit", attrs.getString("unit_of_measurement"));
                }
            }
        } catch (Exception ignored) {}
        return keep;
    }

    /** Without an explicit entity list, default to sensors and switches. */
    private boolean isInteresting(String id) {
        return id.startsWith("sensor.") || id.startsWith("switch.")
            || id.startsWith("binary_sensor.") || id.startsWith("light.");
    }

    private String http(String method, String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(HTTP_TIMEOUT_MS);
        c.setReadTimeout(HTTP_TIMEOUT_MS);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + haToken);
        c.setRequestProperty("Accept-Encoding", "gzip");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        if (c.getResponseCode() >= 400) {
            throw new java.io.IOException("HTTP " + c.getResponseCode());
        }
        InputStream in = c.getInputStream();
        if ("gzip".equals(c.getContentEncoding())) {
            in = new java.util.zip.GZIPInputStream(in);
        }
        return new String(readAll(in), "UTF-8");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
        in.close();
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // Announcement server: HA -> phone (LAN speaker).
    //   GET/POST /ping                 -> liveness, {"ok":true}
    //   GET/POST /play?url=<mp3|wav>   -> stream & play URL (HA tts_proxy, files...)
    //   GET/POST /say?text=...         -> calls HA tts.speak, plays the result
    //                                    (needs a TTS entity in HA: tts_entity in config)
    //   GET/POST /stop                 -> stop playback
    // text/url may also come in a form-encoded POST body.
    // ------------------------------------------------------------------

    private Thread startHttpServer() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(listenPort);
                    Log.i(TAG, "announcement server on :" + listenPort);
                    while (!stopPoll) {
                        Socket s = serverSocket.accept();
                        try {
                            handleConnection(s);
                        } catch (Exception e) {
                            Log.w(TAG, "conn: " + e);
                        }
                        try { s.close(); } catch (IOException ignored) {}
                    }
                } catch (IOException e) {
                    Log.e(TAG, "server failed: " + e);
                }
            }
        }, "kiosk-http");
        t.start();
        return t;
    }

    private void handleConnection(Socket s) throws Exception {
        BufferedInputStream in = new BufferedInputStream(s.getInputStream());
        String req = readHttpLine(in);
        if (req == null) return;
        String[] parts = req.split(" ");
        if (parts.length < 2) return;

        int contentLength = 0;
        String line;
        while ((line = readHttpLine(in)) != null && line.length() > 0) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("content-length")) {
                try { contentLength = Integer.parseInt(line.substring(c + 1).trim()); }
                catch (NumberFormatException ignored) {}
            }
        }
        String body = "";
        if (contentLength > 0) {
            byte[] buf = new byte[contentLength];
            int off = 0;
            while (off < contentLength) {
                int n = in.read(buf, off, contentLength - off);
                if (n < 0) break;
                off += n;
            }
            body = new String(buf, 0, off, "UTF-8");
        }

        String path = parts[1];
        String q = "";
        int qm = path.indexOf('?');
        if (qm >= 0) { q = path.substring(qm + 1); path = path.substring(0, qm); }
        Map<String, String> params = parseQuery(q);
        if (body.indexOf('=') > 0) params.putAll(parseQuery(body));

        String reply;
        String ctype = "application/json";
        if (path.equals("/ping")) {
            reply = "{\"ok\":true,\"app\":\"kiosk\"}";
        } else if (path.equals("/") || path.equals("/config")) {
            ctype = "text/html; charset=utf-8";
            reply = configPageHtml();
        } else if (path.equals("/entities")) {
            reply = entityListJson();
        } else if (path.equals("/save")) {
            reply = saveConfig(body);
        } else if (path.equals("/play")) {
            String url = params.get("url");
            if (url == null && q.length() > 0 && q.indexOf('=') < 0) {
                try { url = URLDecoder.decode(q, "UTF-8"); }  // UMP-style /play?<encoded>
                catch (Exception e) { url = q; }
            }
            if (url == null) reply = "{\"ok\":false,\"error\":\"missing url\"}";
            else if (playMediaSourceTts(url)) reply = "{\"ok\":true,\"tts\":true}";
            else { playUrl(normalizeMediaUrl(url)); reply = "{\"ok\":true,\"playing\":true}"; }
        } else if (path.equals("/say")) {
            final String text = params.get("text");
            if (text == null || text.length() == 0) {
                reply = "{\"ok\":false,\"error\":\"missing text\"}";
            } else if (ttsReady) {
                speakLocal(text);
                reply = "{\"ok\":true,\"speaking\":\"local\"}";
            } else {
                speakText(text);   // HA universal-player fallback
                reply = "{\"ok\":true,\"queued\":\"ha\"}";
            }
        } else if (path.equals("/stop")) {
            stopPlayback();
            reply = "{\"ok\":true}";
        } else if (path.equals("/beep")) {
            beep();
            reply = "{\"ok\":true,\"beep\":true}";
        } else {
            reply = "{\"ok\":false,\"error\":\"unknown path\"}";
        }

        byte[] outBytes = reply.getBytes("UTF-8");
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: " + ctype + "\r\n"
                + "Content-Length: " + outBytes.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        os.write(outBytes);
        os.flush();
    }

    // ------------------------------------------------------------------
    // Web config page (browser on any LAN machine):
    //   GET  /          -> tile picker page
    //   GET  /entities  -> all HA entities + current selection (JSON)
    //   POST /save      -> {"entities":[...]} ; rewrites config, applies live
    // ------------------------------------------------------------------

    private String cachedConfigHtml;

    private String configPageHtml() {
        if (cachedConfigHtml == null) {
            try {
                cachedConfigHtml =
                    new String(readAll(getAssets().open("config.html")), "UTF-8");
            } catch (Exception e) {
                cachedConfigHtml = "<h1>config.html missing from assets</h1>";
            }
        }
        return cachedConfigHtml;
    }

    private String entityListJson() {
        try {
            JSONArray states = new JSONArray(http("GET", haUrl + "/api/states", null));
            JSONArray ents = new JSONArray();
            for (int i = 0; i < states.length(); i++) {
                JSONObject s = states.optJSONObject(i);
                if (s == null) continue;
                JSONObject e = new JSONObject();
                e.put("id", s.getString("entity_id"));
                e.put("state", s.optString("state"));
                JSONObject a = s.optJSONObject("attributes");
                e.put("name", a != null ? a.optString("friendly_name", "") : "");
                ents.put(e);
            }
            JSONArray sel;
            synchronized (wantedEntities) { sel = new JSONArray(wantedEntities); }
            return "{\"entities\":" + ents + ",\"selected\":" + sel + "}";
        } catch (Exception e) {
            return "{\"error\":\"HA unreachable: " + e + "\"}";
        }
    }

    private String saveConfig(String body) {
        try {
            if (haUrl == null) return "{\"ok\":false,\"error\":\"no HA config loaded yet\"}";
            if (body == null || !body.trim().startsWith("{"))
                return "{\"ok\":false,\"error\":\"POST a JSON body {\\\"entities\\\":[...]}\"}";
            JSONObject cfg = new JSONObject(body);
            if (cfg.has("interval"))
                pollSeconds = Math.max(2, Math.min(300, cfg.getInt("interval")));
            JSONArray ents = cfg.getJSONArray("entities");
            ArrayList<String> next = new ArrayList<String>();
            for (int i = 0; i < ents.length(); i++) next.add(ents.getString(i));

            JSONObject out = new JSONObject();
            out.put("url", haUrl);
            out.put("token", haToken);
            out.put("interval", pollSeconds);
            out.put("listen", listenEnabled);
            out.put("listen_port", listenPort);
            out.put("tts_entity", ttsEntity);
            out.put("media_player", mediaPlayerEntity);
            out.put("entities", new JSONArray(next));

            File f = new File(getFilesDir(), "kiosk_config.json");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(out.toString(1).getBytes("UTF-8"));
            fos.close();

            synchronized (wantedEntities) {
                wantedEntities.clear();
                wantedEntities.addAll(next);
            }
            Log.i(TAG, "config saved via web page: " + next.size() + " entities -> " + f);
            return "{\"ok\":true,\"entities\":" + next.size() + "}";
        } catch (Exception e) {
            Log.w(TAG, "saveConfig failed: " + e);
            return "{\"ok\":false,\"error\":\"" + e + "\"}";
        }
    }

    private static String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, prev = -1;
        while ((c = in.read()) >= 0) {
            if (prev == '\r' && c == '\n') {
                byte[] arr = b.toByteArray();
                return new String(arr, 0, arr.length - 1, "UTF-8");
            }
            b.write(c);
            prev = c;
        }
        return b.size() > 0 ? new String(b.toByteArray(), "UTF-8") : null;
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<String, String>();
        if (q == null || q.length() == 0) return m;
        String[] pairs = q.split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            if (eq > 0) {
                try {
                    m.put(URLDecoder.decode(pairs[i].substring(0, eq), "UTF-8"),
                          URLDecoder.decode(pairs[i].substring(eq + 1), "UTF-8"));
                } catch (Exception ignored) {}
            }
        }
        return m;
    }

    /**
     * Play audio through the phone speaker. Runs on the worker thread:
     * download first with our own HttpURLConnection (auth header + gzip),
     * then feed MediaPlayer a local file -- 2.3's stagefright HTTP stack
     * is flaky and cannot send the HA API token anyway.
     */
    private void playUrl(final String url) {
        Log.i(TAG, "playUrl: " + url);
        workerHandler.post(new Runnable() {
            public void run() {
                File tmp = null;
                try {
                    releasePlayer();
                    raiseMusicVolume();

                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(HTTP_TIMEOUT_MS);
                    c.setReadTimeout(HTTP_TIMEOUT_MS);
                    c.setRequestProperty("Authorization", "Bearer " + haToken);
                    int code = c.getResponseCode();
                    if (code >= 400) {
                        Log.w(TAG, "audio download HTTP " + code + " for " + url);
                        return;
                    }
                    InputStream in = c.getInputStream();
                    if ("gzip".equals(c.getContentEncoding())) {
                        in = new java.util.zip.GZIPInputStream(in);
                    }
                    tmp = File.createTempFile("announce", ".audio", getCacheDir());
                    FileOutputStream fo = new FileOutputStream(tmp);
                    byte[] buf = new byte[8192];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        total += n;
                    }
                    fo.close();
                    in.close();
                    Log.i(TAG, "audio: " + total + " bytes");
                    playTempFile(tmp);
                    tmp = null;
                } catch (Exception e) {
                    Log.w(TAG, "playUrl failed: " + e);
                    if (tmp != null) { try { tmp.delete(); } catch (Exception ignored) {} }
                }
            }
        });
    }

    /** Set up volume on the worker thread before any playback. */
    private void raiseMusicVolume() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.setStreamVolume(AudioManager.STREAM_MUSIC,
                (int) (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.9f), 0);
    }

    /** Worker thread: play a local temp file. FD-based because on 2.3
     *  MediaPlayer opens path names inside mediaserver (uid "media"),
     *  which cannot see our private cache dir. */
    private void playTempFile(File f) throws IOException {
        audioTemp = f;
        audioStream = new FileInputStream(audioTemp);
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setDataSource(audioStream.getFD());
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) { stopPlayback(); }
        });
        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Log.w(TAG, "media error " + what + "/" + extra);
                stopPlayback();
                return true;
            }
        });
        mediaPlayer.prepare();
        Log.i(TAG, "playing");
        mediaPlayer.start();
    }

    /** Worker thread: synthesize a 1 s test tone and play it (no network). */
    private void beep() {
        workerHandler.post(new Runnable() {
            public void run() {
                File tmp = null;
                try {
                    releasePlayer();
                    raiseMusicVolume();
                    int rate = 8000;
                    short[] pcm = new short[rate];
                    for (int i = 0; i < pcm.length; i++) {
                        pcm[i] = (short) (12000 * Math.sin(2 * Math.PI * 880 * i / rate));
                    }
                    tmp = File.createTempFile("beep", ".wav", getCacheDir());
                    FileOutputStream fo = new FileOutputStream(tmp);
                    int dataLen = pcm.length * 2;
                    fo.write("RIFF".getBytes("US-ASCII"));
                    wLe32(fo, 36 + dataLen);
                    fo.write("WAVEfmt ".getBytes("US-ASCII"));
                    wLe32(fo, 16);
                    wLe16(fo, 1);  wLe16(fo, 1);
                    wLe32(fo, rate);
                    wLe32(fo, rate * 2);
                    wLe16(fo, 2);  wLe16(fo, 16);
                    fo.write("data".getBytes("US-ASCII"));
                    wLe32(fo, dataLen);
                    for (int i = 0; i < pcm.length; i++) wLe16(fo, pcm[i]);
                    fo.close();
                    playTempFile(tmp);
                    tmp = null;
                } catch (Exception e) {
                    Log.w(TAG, "beep failed: " + e);
                    if (tmp != null) { try { tmp.delete(); } catch (Exception ignored) {} }
                }
            }
        });
    }

    private static void wLe32(OutputStream o, int v) throws IOException {
        o.write((v) & 0xff); o.write((v >>> 8) & 0xff);
        o.write((v >>> 16) & 0xff); o.write((v >>> 24) & 0xff);
    }

    private static void wLe16(OutputStream o, int v) throws IOException {
        o.write((v) & 0xff); o.write((v >>> 8) & 0xff);
    }

    /**
     * Accept absolute URLs, root-relative paths (/api/tts_proxy/x.mp3 as
     * HA hands them to media players) and rewrite URLs pointing at HA with
     * a hostname the phone cannot resolve back to the configured host.
     */
    private String normalizeMediaUrl(String url) {
        try {
            int tp = url.indexOf("/api/tts_proxy/");
            if (tp > 0) {                       // absolute but maybe wrong host
                url = url.substring(tp);
            }
            if (url.startsWith("/")) {
                url = haUrl + url;
            } else if (url.startsWith("http") && haUrl != null) {
                String base = new URL(haUrl).getHost();
                String host = new URL(url).getHost();
                if (!host.equals(base) && url.matches("http://[^/]+/api/.*")) {
                    url = haUrl + url.substring(url.indexOf("/", 7));
                }
            }
        } catch (Exception ignored) {}
        return url;
    }

    /** Phone's built-in TTS (queue-flush). Reflection because 2.3's
     *  speak(CharSequence,int,HashMap,String) disappeared from the SDK
     *  (it became a Bundle in API 21). */
    private void speakLocal(final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    java.lang.reflect.Method m = tts.getClass().getMethod("speak",
                            CharSequence.class, int.class,
                            HashMap.class, String.class);
                    m.invoke(tts, text, TextToSpeech.QUEUE_FLUSH, null, "kiosk");
                } catch (Exception e) {
                    Log.w(TAG, "speakLocal failed: " + e);
                }
            }
        });
    }

    /** Ask HA to synthesize the text and play the result on the phone.
     *  Uses the tts_get_url REST view: HA generates the file and hands
     *  back a direct /api/tts_proxy/<token>.mp3 URL which the proxy view
     *  serves WITHOUT auth. (tts.speak -> universal player only pushes an
     *  unresolvable media-source:// id, so we never rely on that.) */
    private void speakText(final String text) {
        workerHandler.post(new Runnable() {
            public void run() { speakHa(ttsEntity, text, true); }
        });
    }

    private void speakHa(String engine, String message, boolean cache) {
        try {
            String r = http("POST", haUrl + "/api/tts_get_url",
                            "{\"engine_id\":\"" + engine + "\""
                            + ",\"message\":\"" + jsonEscape(message) + "\""
                            + ",\"cache\":" + cache + "}");
            String path = new JSONObject(r).optString("path");
            if (path.length() == 0) {
                Log.w(TAG, "tts_get_url gave no path: " + r);
                return;
            }
            Log.i(TAG, "tts generated: " + path);
            playUrl(haUrl + path);
        } catch (Exception e) {
            Log.w(TAG, "speakHa failed: " + e);
        }
    }

    /** HA 2026 hands media players "media-source://tts/<engine>?message=..."
     *  instead of a URL. If the universal player pushes such an id, rebuild
     *  the audio through tts_get_url. Returns true if handled. */
    private boolean playMediaSourceTts(String url) {
        if (!url.startsWith("media-source://tts/")) return false;
        String rest = url.substring("media-source://tts/".length());
        int q = rest.indexOf('?');
        final String engine = q < 0 ? rest : rest.substring(0, q);
        String messageTmp = null;
        boolean cacheTmp = true;
        if (q >= 0) for (String kv : rest.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            String k = kv.substring(0, eq), v;
            try { v = java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8"); }
            catch (Exception e) { v = kv.substring(eq + 1); }
            if (k.equals("message")) messageTmp = v;
            else if (k.equals("cache")) cacheTmp = "true".equals(v);
        }
        if (messageTmp == null || messageTmp.length() == 0) return false;
        final String message = messageTmp;
        final boolean cache = cacheTmp;
        Log.i(TAG, "media-source tts: engine=" + engine + " msg=" + message);
        workerHandler.post(new Runnable() {
            public void run() { speakHa(engine, message, cache); }
        });
        return true;
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    /** Entry point from any thread: release the player on the worker thread. */
    private void stopPlayback() {
        if (workerHandler == null) return;
        workerHandler.post(new Runnable() {
            public void run() { releasePlayer(); }
        });
    }

    /** Only ever called on the worker thread -- releasing via a posted
     *  Runnable from within a play task would release the NEW player. */
    private void releasePlayer() {
        if (mediaPlayer != null) {
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (audioStream != null) {
            try { audioStream.close(); } catch (Exception ignored) {}
            audioStream = null;
        }
        if (audioTemp != null) {
            try { audioTemp.delete(); } catch (Exception ignored) {}
            audioTemp = null;
        }
    }

    // ------------------------------------------------------------------
    // JavaScript bridge: kiosk.send("switch/turn_on", "switch.pump")
    // ------------------------------------------------------------------

    @SuppressLint("JavascriptInterface")
    public void send(final String domainService, final String entityId) {
        if (haUrl == null || workerHandler == null) return;
        workerHandler.post(new Runnable() {
            public void run() {
                try {
                    http("POST", haUrl + "/api/services/" + domainService,
                         "{\"entity_id\":\"" + entityId + "\"}");
                    Log.i(TAG, "called " + domainService + " on " + entityId);
                } catch (Exception e) {
                    Log.w(TAG, "service call failed: " + e);
                }
            }
        });
        burstRefresh(entityId);
    }

    /** Right after a tap, re-fetch just that entity a few times so the
     *  tile follows the physical device within ~1 s instead of waiting
     *  for the next full poll. */
    private void burstRefresh(final String entityId) {
        if (!wantedEntities.contains(entityId)) return;
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 6; i++) {
                    try { Thread.sleep(700); } catch (Exception e) { return; }
                    try {
                        final String s = trimState(new JSONObject(http(
                                "GET", haUrl + "/api/states/" + entityId, null))).toString();
                        webView.post(new Runnable() {
                            public void run() {
                                webView.loadUrl("javascript:HA.patch(" + s + ")");
                            }
                        });
                    } catch (Exception ignored) {}
                }
            }
        }, "kiosk-burst");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Kill the keyguard every time we come back to the front, so
        // pressing power can never land on a lockscreen.
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguardLock == null) {
            keyguardLock = km.newKeyguardLock("kiosk");
        }
        keyguardLock.disableKeyguard();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Long-press HOME (recent apps dialog) -- swallow it too.
        if (keyCode == KEYCODE_APP_SWITCH_LOCAL) {
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastBackPress > TAP_WINDOW_MS) {
                backPressCount = 0;
            }
            lastBackPress = now;
            backPressCount++;

            if (backPressCount >= TAPS_TO_EXIT) {
                exiting = true;
                if (keyguardLock != null) {
                    keyguardLock.reenableKeyguard(); // give a normal phone back
                }
                finish();
            } else {
                Toast.makeText(this,
                        "Press BACK " + (TAPS_TO_EXIT - backPressCount)
                                + " more times to exit",
                        Toast.LENGTH_SHORT).show();
            }
            return true; // swallow BACK
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Something else came to the front -- pull ourselves back.
        if (!exiting) {
            startActivity(new Intent(this, KioskActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
    }

    @Override
    protected void onDestroy() {
        stopPoll = true;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (tts != null) {
            try { tts.shutdown(); } catch (Exception ignored) {}
        }
        if (pollThread != null) pollThread.interrupt();
        if (mediaPlayer != null) {
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (audioTemp != null) {
            try { audioTemp.delete(); } catch (Exception ignored) {}
            audioTemp = null;
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
