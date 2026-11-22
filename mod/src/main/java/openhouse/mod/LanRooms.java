package openhouse.mod;

import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LanRooms {
    private LanRooms() { }

    public static final class Room {
        public final String key, code, world;
        public final boolean pw;
        long seen;
        Room(String key, String code, String world, boolean pw) { this.key = key; this.code = code; this.world = world; this.pw = pw; }
    }

    private static final Map<String, Room> rooms = new LinkedHashMap<>();
    private static Thread thread;
    private static volatile MulticastSocket sock;

    public static synchronized void start() {
        if (thread != null) return;
        thread = new Thread(LanRooms::listen, "OpenHouse nearby rooms");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void stop() {
        thread = null;
        MulticastSocket s = sock;
        if (s != null) s.close();
    }

    public static synchronized List<Room> now() {
        long cut = System.currentTimeMillis() - 5000;
        List<Room> l = new ArrayList<>();
        for (Room r : rooms.values()) if (r.seen >= cut) l.add(r);
        return l;
    }

    private static void listen() {
        Thread me = Thread.currentThread();
        try (MulticastSocket s = new MulticastSocket(4445)) {
            sock = s;
            InetAddress grp = InetAddress.getByName("224.0.2.61");
            try { s.joinGroup(grp); } catch (Exception ignored) { }
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                    boolean v4 = false;
                    for (InterfaceAddress a : ni.getInterfaceAddresses()) if (a.getAddress() instanceof Inet4Address) v4 = true;
                    if (v4) s.joinGroup(new InetSocketAddress(grp, 0), ni);
                } catch (Exception ignored) { }
            }
            byte[] buf = new byte[1024];
            while (thread == me) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                heard(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            if (thread == me) OpenHouseMod.LOG.info("nearby rooms: {}", e.toString());
        } finally {
            sock = null;
        }
    }

    static void heard(String msg) {
        String run = NearbyRooms.tag(msg, "[OHS]", "[/OHS]"), oh = NearbyRooms.tag(msg, "[OH]", "[/OH]");
        if (oh == null) return;
        boolean pw = oh.endsWith(",p");
        String code = pw ? oh.substring(0, oh.length() - 2) : oh;
        if (code.isEmpty() || code.length() > 16) return;
        String motd = NearbyRooms.tag(msg, "[MOTD]", "[/MOTD]"), name = NearbyRooms.tag(msg, "[NAME]", "[/NAME]");
        String world = name != null && !name.trim().isEmpty() ? name.trim() : motd;
        if (world != null && world.endsWith(OpenHouseMod.LAN_MARK)) world = world.substring(0, world.length() - OpenHouseMod.LAN_MARK.length());
        if (world == null || world.trim().isEmpty()) world = code;
        String key = run != null ? "s" + run : "c" + code;
        synchronized (LanRooms.class) {
            Room r = new Room(key, code, world.trim(), pw);
            r.seen = System.currentTimeMillis();
            rooms.put(key, r);
        }
    }
}
