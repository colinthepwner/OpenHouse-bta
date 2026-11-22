package openhouse.mod.server;

import net.minecraft.server.MinecraftServer;

import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LanAnnounce {
    private LanAnnounce() { }

    private static Thread thread;

    static synchronized void start_if_needed(MinecraftServer server, int port) {
        if (thread != null) return;
        try { Class.forName("net.minecraft.server.net.MulticastPublisher"); return; } catch (Throwable ignored) { }
        final String run = Long.toString(new java.util.Random().nextLong() & Long.MAX_VALUE, 36);
        thread = new Thread(() -> {
            List<NetworkInterface> nics = Collections.emptyList();
            long nics_at = 0;
            while (true) {
                try {
                    long now = System.currentTimeMillis();
                    if (now - nics_at > 30_000L) { nics = nics(); nics_at = now; }
                    String motd = server.motd == null ? "" : server.motd;
                    byte[] b = ("[OHS]" + run + "[/OHS]" + ServerRoom.lan_tag() + String.format("[MOTD]%s[/MOTD][AD]%s[/AD][NAME]%s[/NAME]", motd, port, "")).getBytes(StandardCharsets.UTF_8);
                    send(b, nics);
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) { }
            }
        }, "OpenHouse LAN announce");
        thread.setDaemon(true);
        thread.start();
    }

    private static void send(byte[] b, List<NetworkInterface> nics) {
        try (MulticastSocket s = new MulticastSocket()) {
            DatagramPacket p = new DatagramPacket(b, b.length, InetAddress.getByName("224.0.2.61"), 4445);
            int sent = 0;
            for (NetworkInterface ni : nics) {
                try { s.setNetworkInterface(ni); s.send(p); sent++; } catch (Exception ignored) { }
            }
            if (sent == 0) s.send(p);
        } catch (Exception ignored) { }
    }

    private static List<NetworkInterface> nics() {
        List<NetworkInterface> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                    for (InterfaceAddress a : ni.getInterfaceAddresses()) if (a.getAddress() instanceof Inet4Address) { out.add(ni); break; }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return out;
    }
}
