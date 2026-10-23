package com.example.localtest;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    static final int PORT = 8080;
    ExecutorService ex = Executors.newFixedThreadPool(48);
    Handler h = new Handler(Looper.getMainLooper());
    EditText addr;
    TextView statusView, clientsView;
    LinearLayout cfgBox;
    volatile String base = null;

    Runnable tick = new Runnable() {
        @Override public void run() {
            if (base != null) refresh();
            h.postDelayed(this, 8000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = (int) (14 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(p, p, p, p);

        addr = new EditText(this);
        addr.setHint("آدرس سرور مثل 192.168.1.5:8080");
        addr.setText("127.0.0.1:" + PORT);
        addr.setSingleLine(true);
        root.addView(addr);

        LinearLayout row = new LinearLayout(this);
        Button scan = new Button(this); scan.setText("اسکن شبکه");
        Button con = new Button(this); con.setText("اتصال");
        Button add = new Button(this); add.setText("افزودن");
        row.addView(scan); row.addView(con); row.addView(add);
        root.addView(row);

        statusView = new TextView(this);
        statusView.setText("وصل نیستید");
        root.addView(statusView);

        TextView t1 = new TextView(this);
        t1.setText("کانفیگ‌ها (برای کپی لمس کنید):");
        t1.setTextSize(17);
        t1.setPadding(0, p, 0, 0);
        root.addView(t1);
        cfgBox = new LinearLayout(this);
        cfgBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(cfgBox);

        clientsView = new TextView(this);
        clientsView.setPadding(0, p, 0, 0);
        root.addView(clientsView);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        scan.setOnClickListener(v -> scan());
        con.setOnClickListener(v -> connect(addr.getText().toString().trim()));
        add.setOnClickListener(v -> addDialog());
        h.postDelayed(tick, 8000);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        ex.shutdownNow();
        super.onDestroy();
    }

    void status(String s) { runOnUiThread(() -> statusView.setText(s)); }

    String http(String method, String url, String body, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(body.getBytes("UTF-8"));
        }
        InputStream in = c.getInputStream();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        c.disconnect();
        return o.toString("UTF-8");
    }

    String localPrefix() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String s = a.getHostAddress();
                        return s.substring(0, s.lastIndexOf('.') + 1);
                    }
        } catch (Exception e) { }
        return null;
    }

    void scan() {
        status("در حال اسکن شبکه…");
        ex.execute(() -> {
            List<String> ips = new ArrayList<>();
            ips.add("127.0.0.1");
            String prefix = localPrefix();
            if (prefix != null) for (int i = 1; i <= 254; i++) ips.add(prefix + i);
            final AtomicReference<String> found = new AtomicReference<>();
            final CountDownLatch latch = new CountDownLatch(ips.size());
            for (final String ip : ips) {
                ex.execute(() -> {
                    try {
                        if (found.get() == null && "pong".equals(http("GET", "http://" + ip + ":" + PORT + "/ping", null, 500).trim()))
                            found.compareAndSet(null, ip + ":" + PORT);
                    } catch (Exception e) { }
                    latch.countDown();
                });
            }
            try { latch.await(15, TimeUnit.SECONDS); } catch (Exception e) { }
            final String r = found.get();
            if (r == null) status("سروری پیدا نشد. مطمئن شوید سرور ترموکس روشن است.");
            else runOnUiThread(() -> connect(r));
        });
    }

    void connect(String a) {
        if (a.isEmpty()) return;
        addr.setText(a);
        base = "http://" + a;
        status("در حال اتصال…");
        refresh();
    }

    void refresh() {
        final String b = base;
        if (b == null) return;
        ex.execute(() -> {
            try {
                http("GET", b + "/hello?name=" + URLEncoder.encode(Build.MODEL, "UTF-8"), null, 2000);
                final JSONArray cfgs = new JSONArray(http("GET", b + "/configs", null, 3000));
                final JSONArray cl = new JSONArray(http("GET", b + "/clients", null, 3000));
                List<Future<Long>> fs = new ArrayList<>();
                for (int i = 0; i < cfgs.length(); i++) {
                    final String uri = cfgs.getJSONObject(i).optString("uri");
                    Callable<Long> c = () -> tcpPing(uri);
                    fs.add(ex.submit(c));
                }
                final long[] ms = new long[cfgs.length()];
                for (int i = 0; i < ms.length; i++) ms[i] = fs.get(i).get();
                runOnUiThread(() -> show(cfgs, ms, cl));
                status("متصل به " + b.substring(7));
            } catch (Exception e) {
                status("خطا در اتصال به سرور");
            }
        });
    }

    void show(JSONArray cfgs, long[] ms, JSONArray cl) {
        cfgBox.removeAllViews();
        for (int i = 0; i < cfgs.length(); i++) {
            JSONObject o = cfgs.optJSONObject(i);
            if (o == null) continue;
            final String uri = o.optString("uri");
            TextView t = new TextView(this);
            t.setTextSize(16);
            t.setPadding(0, 10, 0, 10);
            String res;
            if (ms[i] >= 0) { res = ms[i] + " ms"; t.setTextColor(Color.rgb(0, 130, 60)); }
            else if (ms[i] == -2) { res = "نامعتبر"; t.setTextColor(Color.GRAY); }
            else { res = "ناموفق"; t.setTextColor(Color.RED); }
            t.setText(o.optString("name") + "  —  " + res);
            t.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("config", uri));
                Toast.makeText(this, "کپی شد", Toast.LENGTH_SHORT).show();
            });
            cfgBox.addView(t);
        }
        StringBuilder sb = new StringBuilder("متصل‌ها به سرور (" + cl.length() + "):");
        for (int i = 0; i < cl.length(); i++) {
            JSONObject o = cl.optJSONObject(i);
            if (o != null) sb.append("\n• ").append(o.optString("name")).append("  ").append(o.optString("ip"));
        }
        clientsView.setText(sb.toString());
    }

    long tcpPing(String uri) {
        String[] hp = hostPort(uri);
        if (hp == null) return -2;
        long t = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 2500);
            return System.currentTimeMillis() - t;
        } catch (Exception e) { return -1; }
    }

    String[] find(String s) {
        Matcher m = Pattern.compile("@(\\[[^\\]]+\\]|[^:/?#@]+):(\\d+)").matcher(s);
        if (m.find()) return new String[]{m.group(1).replaceAll("[\\[\\]]", ""), m.group(2)};
        return null;
    }

    String[] hostPort(String u) {
        try {
            if (u.startsWith("vmess://")) {
                JSONObject o = new JSONObject(new String(Base64.decode(u.substring(8).trim(), Base64.DEFAULT), "UTF-8"));
                return new String[]{o.getString("add"), String.valueOf(o.get("port"))};
            }
            String[] r = find(u);
            if (r != null) return r;
            if (u.startsWith("ss://")) {
                String body = u.substring(5).split("[#?]")[0];
                return find(new String(Base64.decode(body, Base64.DEFAULT), "UTF-8"));
            }
        } catch (Exception e) { }
        return null;
    }

    void addDialog() {
        if (base == null) { Toast.makeText(this, "اول به سرور وصل شوید", Toast.LENGTH_SHORT).show(); return; }
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(40, 20, 40, 0);
        final EditText n = new EditText(this); n.setHint("نام کانفیگ");
        final EditText u = new EditText(this); u.setHint("لینک (vless:// vmess:// ...)");
        l.addView(n); l.addView(u);
        new AlertDialog.Builder(this).setTitle("افزودن به سرور").setView(l)
            .setPositiveButton("افزودن", (d, w) -> {
                final String name = n.getText().toString().trim(), uri = u.getText().toString().trim();
                if (uri.isEmpty()) return;
                ex.execute(() -> {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("name", name.isEmpty() ? "config" : name);
                        o.put("uri", uri);
                        http("POST", base + "/add", o.toString(), 3000);
                        refresh();
                    } catch (Exception e) { status("افزودن ناموفق بود"); }
                });
            }).setNegativeButton("لغو", null).show();
    }
}
