package com.codexrm.p2pprobe;

import android.app.Activity;
import android.os.Bundle;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.widget.TextView;
import java.io.*;
import java.net.*;
import org.json.JSONObject;

public class MainActivity extends Activity {
    static { System.loadLibrary("p2pprobe"); }
    public static native String runProbe(String config);
    private TextView status;
    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().addFlags(128);
        status = new TextView(this);status.setTextSize(18);status.setPadding(24,48,24,24);setContentView(status);
        status.setText("P2P real-network verification\nSignaling: USB loopback\nData: cellular UDP / QUIC only\nPreparing…");
        new Thread(() -> {
            try {
                String config = new String(read(new FileInputStream(new File(getFilesDir(), "config.json"))), java.nio.charset.StandardCharsets.UTF_8);
                JSONObject parsed = new JSONObject(config);
                ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
                NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) throw new IOException("Need cellular default network without VPN: " + caps);
                try (FileOutputStream f = new FileOutputStream(new File(getFilesDir(), "network.txt"))) { f.write(caps.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                HttpURLConnection connection = (HttpURLConnection)new URL(parsed.getString("coordinator") + "/start?role=phone").openConnection();
                connection.setRequestProperty("Authorization", "Bearer " + parsed.getString("secret"));connection.setReadTimeout(10000);
                String actual = new String(read(connection.getInputStream()),java.nio.charset.StandardCharsets.UTF_8);connection.disconnect();
                runOnUiThread(() -> status.append("\nRunning authenticated UDP → QUIC → two-way RPC…"));
                String result = runProbe(actual);
                try (FileOutputStream f = new FileOutputStream(new File(getFilesDir(), "results.json"))) { f.write(result.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                runOnUiThread(() -> status.append("\nCompleted. Results saved."));
            } catch (Throwable e) {
                String error = e.toString();
                try (FileOutputStream f = new FileOutputStream(new File(getFilesDir(), "error.txt"))) { f.write(error.getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception ignored) {}
                runOnUiThread(() -> status.append("\nERROR: " + error));
            }
        }, "p2p-test").start();
    }
    private static byte[] read(InputStream in) throws IOException {try (InputStream stream = in;ByteArrayOutputStream out = new ByteArrayOutputStream()) {byte[] b=new byte[8192];int n;while ((n=stream.read(b))!=-1) out.write(b,0,n);return out.toByteArray();}}
}
