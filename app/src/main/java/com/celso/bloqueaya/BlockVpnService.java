package com.celso.bloqueaya;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bloqueador de anuncios por DNS: solo pasa por la VPN el tráfico DNS, nada más. */
public class BlockVpnService extends VpnService {
    public static volatile boolean running = false;
    public static volatile long blocked = 0;

    private static final String VPN_ADDR = "10.111.222.1";
    private static final String DNS_FAKE = "10.111.222.2";
    private static final String UPSTREAM = "1.1.1.1";

    private ParcelFileDescriptor tun;
    private Thread worker;
    private ExecutorService pool;
    private final HashSet<String> list = new HashSet<>();

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopAll(); return START_NOT_STICKY; }
        startFg();
        if (!running) start();
        return START_STICKY;
    }

    private void startFg() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("bya", "BloqueaYa", NotificationManager.IMPORTANCE_LOW));
            nb = new Notification.Builder(this, "bya");
        } else nb = new Notification.Builder(this);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = nb.setContentTitle("BloqueaYa activo").setContentText("Bloqueando anuncios")
                .setSmallIcon(R.drawable.ic_shield).setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    private void start() {
        try {
            if (list.isEmpty()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("blocklist.txt")));
                String l; while ((l = r.readLine()) != null) { l = l.trim().toLowerCase(); if (!l.isEmpty()) list.add(l); }
                r.close();
            }
            Builder b = new Builder().setSession("BloqueaYa").setMtu(1500)
                    .addAddress(VPN_ADDR, 32).addRoute(DNS_FAKE, 32).addDnsServer(DNS_FAKE);
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
            tun = b.establish();
            if (tun == null) { stopAll(); return; }
            running = true;
            pool = Executors.newFixedThreadPool(8);
            worker = new Thread(this::loop, "bya-loop");
            worker.start();
        } catch (Exception e) { stopAll(); }
    }

    private void loop() {
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tun.getFileDescriptor())) {
            byte[] buf = new byte[32767];
            while (running) {
                int n = in.read(buf);
                if (n <= 0) { Thread.sleep(5); continue; }
                final byte[] pkt = Arrays.copyOf(buf, n);
                handle(pkt, out);
            }
        } catch (Exception ignored) { }
    }

    private void handle(byte[] p, FileOutputStream out) {
        if ((p[0] >> 4) != 4 || p[9] != 17) return;           // solo IPv4 + UDP
        int ihl = (p[0] & 0x0f) * 4;
        int dport = ((p[ihl + 2] & 0xff) << 8) | (p[ihl + 3] & 0xff);
        if (dport != 53) return;
        int dnsOff = ihl + 8;
        final byte[] q = Arrays.copyOfRange(p, dnsOff, p.length);
        String name = qname(q);
        if (name != null && isBlocked(name)) {
            blocked++;
            byte[] resp = q.clone();
            resp[2] = (byte) (0x80 | (q[2] & 0x01));          // QR=1, RD copiado
            resp[3] = (byte) 0x83;                             // RA=1, NXDOMAIN
            write(out, p, ihl, resp);
            return;
        }
        pool.execute(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                protect(s);
                s.setSoTimeout(4000);
                s.send(new DatagramPacket(q, q.length, InetAddress.getByName(UPSTREAM), 53));
                byte[] rb = new byte[4096];
                DatagramPacket rp = new DatagramPacket(rb, rb.length);
                s.receive(rp);
                write(out, p, ihl, Arrays.copyOf(rb, rp.getLength()));
            } catch (Exception ignored) { }
        });
    }

    private boolean isBlocked(String d) {
        d = d.toLowerCase();
        while (true) {
            if (list.contains(d)) return true;
            int i = d.indexOf('.');
            if (i < 0 || i == d.length() - 1) return false;
            d = d.substring(i + 1);
        }
    }

    private static String qname(byte[] q) {
        try {
            StringBuilder sb = new StringBuilder();
            int i = 12;
            while (q[i] != 0) {
                int len = q[i] & 0xff;
                if (sb.length() > 0) sb.append('.');
                sb.append(new String(q, i + 1, len, "US-ASCII"));
                i += len + 1;
            }
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    /** Arma el paquete de respuesta IPv4/UDP invirtiendo origen y destino. */
    private synchronized void write(FileOutputStream out, byte[] req, int ihl, byte[] dns) {
        try {
            int total = 20 + 8 + dns.length;
            byte[] r = new byte[total];
            r[0] = 0x45; r[2] = (byte) (total >> 8); r[3] = (byte) total;
            r[6] = 0x40; r[8] = 64; r[9] = 17;
            System.arraycopy(req, 16, r, 12, 4);               // src = dst original
            System.arraycopy(req, 12, r, 16, 4);               // dst = src original
            int sum = 0;
            for (int i = 0; i < 20; i += 2) sum += ((r[i] & 0xff) << 8) | (r[i + 1] & 0xff);
            while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
            sum = ~sum; r[10] = (byte) (sum >> 8); r[11] = (byte) sum;
            r[20] = req[ihl + 2]; r[21] = req[ihl + 3];        // puerto origen = 53
            r[22] = req[ihl]; r[23] = req[ihl + 1];            // puerto destino = el del pedido
            int ul = 8 + dns.length; r[24] = (byte) (ul >> 8); r[25] = (byte) ul;
            System.arraycopy(dns, 0, r, 28, dns.length);       // checksum UDP 0 = permitido en IPv4
            out.write(r);
        } catch (Exception ignored) { }
    }

    private void stopAll() {
        running = false;
        try { if (tun != null) tun.close(); } catch (Exception ignored) { }
        tun = null;
        if (pool != null) pool.shutdownNow();
        stopForeground(true);
        stopSelf();
    }

    @Override public void onRevoke() { stopAll(); }
    @Override public void onDestroy() { stopAll(); super.onDestroy(); }
}
