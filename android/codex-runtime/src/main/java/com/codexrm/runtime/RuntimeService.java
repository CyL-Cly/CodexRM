package com.codexrm.runtime;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** The only exported IPC is signature-restricted; RPC bodies go through private pipe FDs. */
public final class RuntimeService extends Service {
    private static final String DESCRIPTOR = "com.codexrm.runtime.LocalCodex.v1";
    private Session session;
    private final IBinder endpoint = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            // Recheck at the transaction boundary: a binder handle can be passed to another process.
            enforceCallingPermission("com.codexrm.runtime.BIND_LOCAL_CODEX", "Untrusted Codex runtime client");
            if (!Environment.isExternalStorageManager()) {
                reply.writeNoException(); reply.writeInt(2); return true;
            }
            try {
                openSession(reply);
            } catch (IOException error) {
                Log.e("CodexRuntime", "Runtime startup failed", error);
                reply.writeNoException(); reply.writeInt(3);
            }
            return true;
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // A bound-top process alone is still frozen by some Android vendors.
        // The calling UI starts this service while visible and stops it when it unbinds.
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("local_codex", "本机 Codex", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, RuntimeActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "local_codex")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("本机 Codex 正在运行").setContentText("CodexRM 已选择本机设备")
            .setContentIntent(open).setOngoing(true).build();
        startForeground(15, notification);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return endpoint; }
    @Override public synchronized boolean onUnbind(Intent intent) { stopSession(); stopSelf(); return false; }
    @Override public synchronized void onDestroy() { stopSession(); super.onDestroy(); }

    private void stopSession() {
        if (session != null) { session.close(); session = null; }
    }

    private synchronized void openSession(Parcel reply) throws IOException {
        stopSession();
        File bin = new File(getApplicationInfo().nativeLibraryDir);
        File codex = new File(bin, "libcodex_android.so");
        File agent = new File(bin, "libcodexrm_local_agent.so");
        if (!codex.canExecute() || !agent.canExecute()) throw new IOException("Missing executable runtime files");
        File dataDir = new File(getFilesDir(), "agent");
        File codexHome = new File(getFilesDir(), "codex");
        File workspace = new File(Environment.getExternalStorageDirectory(), "CodexRM");
        for (File dir : new File[]{dataDir, codexHome, workspace}) {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create runtime directory");
        }
        ProcessBuilder builder = new ProcessBuilder(agent.getAbsolutePath(), codex.getAbsolutePath(),
            dataDir.getAbsolutePath(), workspace.getAbsolutePath()).directory(workspace);
        Map<String, String> env = builder.environment();
        env.put("HOME", getFilesDir().getAbsolutePath());
        env.put("CODEX_HOME", codexHome.getAbsolutePath());
        env.put("XDG_CONFIG_HOME", getFilesDir().getAbsolutePath());
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        env.put("LD_LIBRARY_PATH", bin.getAbsolutePath());
        env.put("PATH", "/system/bin:/system/xbin");
        env.put("SHELL", "/system/bin/sh");
        File certificates = new File("/apex/com.android.conscrypt/cacerts");
        env.put("SSL_CERT_DIR", certificates.isDirectory() ? certificates.getPath() : "/system/etc/security/cacerts");
        Process proc = builder.start();
        ParcelFileDescriptor[] input = null, output = null;
        try {
            input = ParcelFileDescriptor.createPipe();
            output = ParcelFileDescriptor.createPipe();
            Session next = new Session(proc, input[0], output[1]);
            session = next;
            next.start();
            reply.writeNoException();
            reply.writeInt(0);
            output[0].writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            input[1].writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
        } catch (IOException | RuntimeException error) {
            proc.destroyForcibly();
            if (input != null) for (ParcelFileDescriptor fd : input) closeQuietly(fd);
            if (output != null) for (ParcelFileDescriptor fd : output) closeQuietly(fd);
            throw error;
        }
    }

    private static void closeQuietly(java.io.Closeable stream) { try { stream.close(); } catch (IOException ignored) {} }

    private static final class Session {
        private final Process process;
        private final ParcelFileDescriptor in, out;
        private final AtomicBoolean closed = new AtomicBoolean();
        Session(Process process, ParcelFileDescriptor in, ParcelFileDescriptor out) { this.process = process; this.in = in; this.out = out; }
        void start() {
            new Thread(() -> copy(new ParcelFileDescriptor.AutoCloseInputStream(in), process.getOutputStream(), false), "codex-rpc-in").start();
            new Thread(() -> copy(process.getInputStream(), new ParcelFileDescriptor.AutoCloseOutputStream(out), true), "codex-rpc-out").start();
            // Never pipe provider stderr into logcat; it can contain request data.
            new Thread(() -> {
                try (InputStream input = process.getErrorStream()) { byte[] buffer = new byte[4096]; while (input.read(buffer) != -1) {} }
                catch (IOException ignored) {}
            }, "codex-runtime-stderr").start();
        }
        void copy(InputStream source, OutputStream dest, boolean closeOnEnd) {
            try (source; dest) {
                byte[] bytes = new byte[32 * 1024];
                int read;
                while (!closed.get() && (read = source.read(bytes)) != -1) { dest.write(bytes, 0, read); dest.flush(); }
            } catch (IOException ignored) { }
            finally { if (closeOnEnd) close(); }
        }
        void close() {
            if (!closed.compareAndSet(false, true)) return;
            closeQuietly(in); closeQuietly(out);
            // The Go bridge watches stdin EOF, closes Codex, then exits. Only force it as a last resort.
            new Thread(() -> {
                try { if (!process.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); }
                catch (InterruptedException ignored) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
            }, "codex-runtime-cleanup").start();
        }
    }
}
