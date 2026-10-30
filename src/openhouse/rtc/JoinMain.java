package openhouse.rtc;

import openhouse.Json;
import openhouse.WsClient;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsType;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public final class JoinMain {
    static final String SIGNAL = "wss://0.peerjs.com:443/peerjs?key=peerjs";

    static final long MAX_BUFFERED = 4L << 20;

    static final long OPEN_TIMEOUT_MS = 25000;
    static final long PROBE_TIMEOUT_MS = 15000;
    static final SecureRandom rnd = new SecureRandom();

    static String room;
    static volatile String self;
    static volatile String token;
    static boolean relayOnly;
    static PeerConnectionFactory factory;
    static volatile WsClient signal;
    static volatile boolean signalOpen;
    static boolean probedOnce;
    static final Object state = new Object();
    static volatile boolean probing;
    static volatile long missingAt;
    static final Map<String, Link> links = new ConcurrentHashMap<>();

    static void out(String line) { synchronized (System.out) { System.out.println(line); System.out.flush(); } }
    static void log(String s) { out("LOG " + s); }

    static final boolean DEBUG = System.getenv("OPENHOUSE_DEBUG") != null;
    static void debug(String s) { if (DEBUG) log(String.format("%tT.%<tL %s", System.currentTimeMillis(), s)); }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: JoinMain <room peer id> [port] [--relay]"); System.exit(2); }
        room = args[0];
        int port = 0;
        for (int k = 1; k < args.length; k++) {
            if (args[k].equals("--relay")) relayOnly = true;
            else port = Integer.parseInt(args[k]);
        }
        newIdentity();

        ServerSocket v4 = new ServerSocket();
        try {
            v4.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        } catch (IOException e) {
            if (port == 0) throw e;

            log("port " + port + " is in use, using another");
            v4 = new ServerSocket();
            v4.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
        }
        int bound = v4.getLocalPort();
        ServerSocket v6 = null;
        try {
            v6 = new ServerSocket();
            v6.bind(new InetSocketAddress(InetAddress.getByName("::1"), bound), 16);
        } catch (IOException e) {
            v6 = null;
        }
        out("PORT " + bound);

        Thread watch = new Thread(() -> {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().equalsIgnoreCase("PROBE")) startProbe();
                    else if (line.startsWith("MOD")) modCommand(line);
                }
            } catch (IOException ignored) { }
            System.exit(0);
        }, "openhouse parent watch");
        watch.setDaemon(true);
        watch.start();

        factory = new PeerConnectionFactory(new HeadlessAudioDeviceModule());

        Thread sig = new Thread(JoinMain::signalLoop, "openhouse signaling loop");
        sig.setDaemon(true);
        sig.start();

        if (v6 != null) {
            final ServerSocket s6 = v6;
            Thread t = new Thread(() -> acceptLoop(s6), "openhouse accept v6");
            t.setDaemon(true);
            t.start();
        }
        acceptLoop(v4);
    }

    static void acceptLoop(ServerSocket ss) {
        while (true) {
            try {
                Socket s = ss.accept();
                s.setTcpNoDelay(true);
                Link l = new Link(s);
                Thread t = new Thread(l::begin, "openhouse link");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                log("accept failed: " + e.getMessage());
                try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
            }
        }
    }

    static void newIdentity() {
        self = "bta-join-" + randomId(16);
        token = randomId(12);
    }

    static String randomId(int n) {
        String a = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < n; k++) b.append(a.charAt(rnd.nextInt(a.length())));
        return b.toString();
    }

    static void signalLoop() {
        while (true) {
            final Object done = new Object();
            final String[] why = { null };
            final boolean[] idTaken = { false };
            try {
                WsClient s = WsClient.connect(SIGNAL + "&id=" + self + "&token=" + token + "&version=1.5.4", new WsClient.Listener() {
                    public void onText(String text) {
                        debug("in  " + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
                        Map<String, Object> m;
                        try { m = Json.obj(Json.parse(text)); } catch (RuntimeException e) { return; }
                        if (m == null) return;
                        if ("ID-TAKEN".equals(Json.str(m.get("type")))) idTaken[0] = true;
                        onSignal(m);
                    }
                    public void onClosed(String w) { synchronized (done) { why[0] = w; done.notifyAll(); } }
                });
                signal = s;
                synchronized (done) {
                    while (why[0] == null) {
                        done.wait(5000);
                        if (why[0] == null) {
                            try { s.send("{\"type\":\"HEARTBEAT\"}"); } catch (IOException e) { break; }
                        }
                    }
                }
                s.close();
                if (signalOpen) log("matchmaking connection dropped (" + why[0] + "), reconnecting");
            } catch (Exception e) {
                out("OFFLINE " + e.getMessage());
            }
            signalOpen = false;

            if (idTaken[0]) newIdentity();
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
        }
    }

    static void sendSignal(Map<String, Object> m) {
        WsClient s = signal;
        if (s == null) return;
        String text = Json.write(m);
        debug("out " + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
        try { s.send(text); } catch (IOException e) { log("signaling send failed: " + e.getMessage()); }
    }

    static void onSignal(Map<String, Object> m) {
        String type = Json.str(m.get("type"));
        String src = Json.str(m.get("src"));
        Map<String, Object> payload = Json.obj(m.get("payload"));
        if ("OPEN".equals(type)) {
            boolean first;
            synchronized (state) {
                signalOpen = true;
                first = !probedOnce;
                probedOnce = true;
                state.notifyAll();
            }
            out("SIGNAL");
            if (first) startProbe();
        } else if ("ANSWER".equals(type) && payload != null) {
            Link l = links.get(Json.str(payload.get("connectionId")));
            Map<String, Object> sdp = Json.obj(payload.get("sdp"));
            if (l != null && sdp != null) l.answer(Json.str(sdp.get("sdp")));
        } else if ("CANDIDATE".equals(type) && payload != null) {
            Link l = links.get(Json.str(payload.get("connectionId")));
            Map<String, Object> c = Json.obj(payload.get("candidate"));
            if (l != null && c != null) {
                Object idx = c.get("sdpMLineIndex");
                l.candidate(Json.str(c.get("sdpMid")), idx instanceof Number ? ((Number) idx).intValue() : 0, Json.str(c.get("candidate")));
            }
        } else if (("EXPIRE".equals(type) || "LEAVE".equals(type)) && room.equals(src)) {

            missingAt = System.currentTimeMillis();
            for (Link l : new ArrayList<>(links.values())) if (!l.opened) l.close();
            synchronized (state) { state.notifyAll(); }
        } else if ("ERROR".equals(type)) {
            log("matchmaking server error: " + (payload != null ? payload.get("msg") : ""));
        } else if ("ID-TAKEN".equals(type)) {
            log("our random id was taken, picking another");
        }
    }

    static void startProbe() {
        synchronized (state) {
            if (probing) return;
            probing = true;
        }
        Thread t = new Thread(() -> {
            String result = "NOANSWER";
            Link p = new Link(null);
            try {
                long started = System.currentTimeMillis();
                if (p.begin()) {
                    long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS;
                    synchronized (state) {
                        while (true) {
                            if (p.answered) { result = "FOUND"; break; }
                            if (missingAt >= started) { result = "NOTFOUND"; break; }
                            long left = deadline - System.currentTimeMillis();
                            if (left <= 0) break;
                            state.wait(Math.min(left, 200));
                        }
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                p.close();
                synchronized (state) { probing = false; state.notifyAll(); }
            }
            out(result);
        }, "openhouse probe");
        t.setDaemon(true);
        t.start();
    }

    static final String MODS_LABEL = "openhouse-mods";
    static final BlockingQueue<Object[]> modInbox = new LinkedBlockingQueue<>();
    static volatile Link modLink;
    static final Object modTurn = new Object();

    static void modCommand(String line) {
        Thread t = new Thread(() -> {
            synchronized (modTurn) {
                try {
                    if (line.equals("MODLIST")) {
                        modConnect();
                        modSend("LIST");
                        Object[] m = modInbox.poll(20, java.util.concurrent.TimeUnit.SECONDS);
                        if (m == null) throw new IOException("the host didn't send its mod list");

                        if ((Boolean) m[0]) { modDrop(); out("MODLIST none"); return; }
                        out("MODLIST " + new String((byte[]) m[1], StandardCharsets.UTF_8));
                    } else if (line.startsWith("MODGET\t")) {
                        String[] parts = line.split("\t");
                        java.io.File dir = new java.io.File(parts[1]);
                        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
                        modConnect();
                        if (parts.length > 2 && !parts[2].isEmpty())
                            for (String item : parts[2].split(",")) {
                                int c = item.indexOf(':');
                                modGet(item.substring(0, c), item.substring(c + 1), dir);
                            }
                        out("MODSDONE");
                    } else if (line.equals("MODCLOSE")) {
                        modDrop();
                    }
                } catch (Exception e) {
                    modDrop();
                    out("MODSFAIL " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            }
        }, "openhouse mods");
        t.setDaemon(true);
        t.start();
    }

    static void modConnect() throws Exception {
        Link l = modLink;
        if (l != null && l.opened && !l.closed) return;
        modInbox.clear();
        l = new Link(null, true);
        modLink = l;
        if (!l.begin()) throw new IOException("can't reach the room");
        long deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS;
        synchronized (state) {
            while (!l.opened && !l.closed && System.currentTimeMillis() < deadline) state.wait(200);
        }
        if (!l.opened) { l.close(); throw new IOException("couldn't open a connection to the host for its mods"); }
    }

    static void modDrop() {
        Link l = modLink;
        modLink = null;
        if (l != null) l.close();
    }

    static void modSend(String text) throws Exception {
        Link l = modLink;
        if (l == null || l.dc == null) throw new IOException("mod connection closed");
        l.dc.send(new RTCDataChannelBuffer(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)), false));
    }

    static void modGet(String sha, String file, java.io.File dir) throws Exception {
        if (!file.matches("[A-Za-z0-9._+\\-]{1,120}")) throw new IOException("bad file name " + file);
        modSend("GET " + sha);
        Object[] m = modInbox.poll(30, java.util.concurrent.TimeUnit.SECONDS);
        if (m == null || (Boolean) m[0]) throw new IOException("no answer for " + file);
        Map<String, Object> head = Json.obj(Json.parse(new String((byte[]) m[1], StandardCharsets.UTF_8)));
        if (head == null || head.get("error") != null || !(head.get("size") instanceof Number))
            throw new IOException("the host can't send " + file + (head != null && head.get("error") != null ? " (" + head.get("error") + ")" : ""));
        long size = ((Number) head.get("size")).longValue();
        java.io.File part = new java.io.File(dir, file + ".part");
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        long got = 0, lastReport = 0;
        try (OutputStream o = new java.io.FileOutputStream(part)) {
            while (got < size) {
                Object[] b = modInbox.poll(30, java.util.concurrent.TimeUnit.SECONDS);
                if (b == null) throw new IOException("the transfer of " + file + " stalled");
                if (!(Boolean) b[0]) throw new IOException("unexpected message during " + file);
                byte[] bytes = (byte[]) b[1];
                o.write(bytes);
                md.update(bytes);
                got += bytes.length;
                if (got - lastReport >= 256 * 1024 || got >= size) { lastReport = got; out("MODPROGRESS " + sha + " " + got + " " + size); }
            }
        }
        Object[] done = modInbox.poll(30, java.util.concurrent.TimeUnit.SECONDS);
        if (done == null || (Boolean) done[0] || !new String((byte[]) done[1], StandardCharsets.UTF_8).equals("DONE " + sha))
            throw new IOException("the transfer of " + file + " didn't finish properly");
        StringBuilder hex = new StringBuilder();
        for (byte x : md.digest()) hex.append(String.format("%02x", x & 0xff));
        if (!hex.toString().equals(sha)) { part.delete(); throw new IOException(file + " arrived damaged (checksum)"); }
        java.io.File dest = new java.io.File(dir, file);
        if (dest.exists() && !dest.delete()) throw new IOException("can't replace " + dest);
        if (!part.renameTo(dest)) throw new IOException("can't move " + part + " into place");
        out("MODGOT " + sha);
    }

    static RTCConfiguration iceConfig() {
        RTCConfiguration cfg = new RTCConfiguration();

        RTCIceServer stun = new RTCIceServer();
        stun.urls = Arrays.asList("stun:stun.l.google.com:19302");
        RTCIceServer turn = new RTCIceServer();
        turn.urls = Arrays.asList("turn:eu-0.turn.peerjs.com:3478", "turn:us-0.turn.peerjs.com:3478");
        turn.username = "peerjs";
        turn.password = "peerjsp";
        cfg.iceServers = new ArrayList<>(Arrays.asList(stun, turn));

        if (relayOnly) cfg.iceTransportPolicy = RTCIceTransportPolicy.RELAY;
        return cfg;
    }

    static int openCount() {
        int n = 0;
        for (Link l : links.values()) if (l.opened && l.tcp != null) n++;
        return n;
    }

    static final class Link implements PeerConnectionObserver {
        final Socket tcp;
        final boolean mods;
        final String cid = "dc_" + randomId(10);
        RTCPeerConnection pc;
        volatile RTCDataChannel dc;
        final List<RTCIceCandidate> early = new ArrayList<>();
        boolean remoteSet;
        volatile boolean answered, opened, closed;
        volatile long received;
        final BlockingQueue<byte[]> toGame = new LinkedBlockingQueue<>();

        Link(Socket tcp) { this(tcp, false); }
        Link(Socket tcp, boolean mods) { this.tcp = tcp; this.mods = mods; }

        boolean connects() { return tcp != null || mods; }

        boolean begin() {
            try {
                long deadline = System.currentTimeMillis() + 30000;
                synchronized (state) {

                    while (!closed && !signalOpen) {
                        long left = deadline - System.currentTimeMillis();
                        if (left <= 0) break;
                        state.wait(Math.min(left, 500));
                    }
                }
                if (closed) return false;
                if (!signalOpen) {
                    log("can't reach the matchmaking server, dropping a connection");
                    close();
                    return false;
                }
            } catch (InterruptedException e) {
                close();
                return false;
            }
            synchronized (this) {
                if (closed) return false;
                pc = factory.createPeerConnection(iceConfig(), this);
            }
            links.put(cid, this);

            RTCDataChannel ch = pc.createDataChannel(mods ? MODS_LABEL : cid, new RTCDataChannelInit());
            dc = ch;
            ch.registerObserver(new RTCDataChannelObserver() {
                public void onBufferedAmountChange(long previous) { }
                public void onStateChange() {
                    RTCDataChannelState st = ch.getState();
                    if (st == RTCDataChannelState.OPEN) opened();
                    else if (st == RTCDataChannelState.CLOSED) close();
                }
                public void onMessage(RTCDataChannelBuffer b) {

                    ByteBuffer d = b.data;
                    byte[] bytes = new byte[d.remaining()];
                    d.get(bytes);
                    if (mods) modInbox.offer(new Object[] { b.binary, bytes });
                    else if (bytes.length > 0) { received += bytes.length; toGame.offer(bytes); }
                }
            });
            pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                public void onSuccess(RTCSessionDescription offer) {
                    pc.setLocalDescription(offer, new SetSessionDescriptionObserver() {
                        public void onSuccess() {
                            sendSignal(Json.map("type", "OFFER", "dst", room, "payload", Json.map(
                                    "sdp", Json.map("type", "offer", "sdp", offer.sdp),
                                    "type", "data", "connectionId", cid, "label", mods ? MODS_LABEL : cid,
                                    "serialization", "raw", "reliable", Boolean.TRUE,

                                    "oh", openhouse.Version.VERSION)));
                        }
                        public void onFailure(String e) { log("setLocalDescription: " + e); close(); }
                    });
                }
                public void onFailure(String e) { log("createOffer: " + e); close(); }
            });
            if (connects()) {
                Thread w = new Thread(() -> {
                    try { Thread.sleep(OPEN_TIMEOUT_MS); } catch (InterruptedException ignored) { }
                    if (!opened && !closed) {
                        log("couldn't connect to the room's host in time (no network path found)");
                        tell("Couldn't reach the room's host - try joining again.");
                        close();
                    }
                }, "openhouse open watchdog");
                w.setDaemon(true);
                w.start();
            }
            return true;
        }

        void answer(String sdp) {
            answered = true;
            synchronized (state) { state.notifyAll(); }
            if (!connects() || sdp == null) return;
            pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, sdp), new SetSessionDescriptionObserver() {
                public void onSuccess() {
                    synchronized (Link.this) {
                        remoteSet = true;
                        for (RTCIceCandidate c : early) pc.addIceCandidate(c);
                        early.clear();
                    }
                }
                public void onFailure(String e) { log("setRemoteDescription: " + e); close(); }
            });
        }

        synchronized void candidate(String mid, int index, String sdp) {
            if (!connects() || closed || sdp == null || sdp.isEmpty()) return;
            RTCIceCandidate c = new RTCIceCandidate(mid, index, sdp);
            if (remoteSet) pc.addIceCandidate(c); else early.add(c);
        }

        @Override public void onIceCandidate(RTCIceCandidate c) {
            debug("local candidate " + c.sdp);
            if (!connects() || closed) return;
            sendSignal(Json.map("type", "CANDIDATE", "dst", room, "payload",
                    Json.map("candidate", Json.map("candidate", c.sdp, "sdpMid", c.sdpMid, "sdpMLineIndex", c.sdpMLineIndex),
                             "type", "data", "connectionId", cid)));
        }

        @Override public void onConnectionChange(RTCPeerConnectionState s) {
            debug(cid + " connection " + s);
            if (s == RTCPeerConnectionState.FAILED || s == RTCPeerConnectionState.CLOSED) close();
        }

        private boolean piped;
        void opened() {
            debug(cid + " channel open" + (mods ? " (mods)" : tcp == null ? " (probe)" : ""));
            if (mods) {
                opened = true;
                synchronized (state) { state.notifyAll(); }
                return;
            }
            synchronized (this) {
                if (piped || closed || tcp == null) return;
                piped = true;
                opened = true;
            }
            reportPath();
            watchFirstByte();
            Thread down = new Thread(this::down, "openhouse down");
            down.setDaemon(true);
            down.start();
            Thread up = new Thread(this::up, "openhouse up");
            up.setDaemon(true);
            up.start();
        }

        void reportPath() {
            try {
                pc.getStats(report -> {
                    String path = "unknown";
                    try {
                        Map<String, RTCStats> all = report.getStats();
                        for (RTCStats s : all.values()) {
                            if (s.getType() != RTCStatsType.CANDIDATE_PAIR) continue;
                            Map<String, Object> a = s.getAttributes();
                            if (!"succeeded".equals(String.valueOf(a.get("state"))) || !Boolean.TRUE.equals(a.get("nominated"))) continue;
                            RTCStats lc = all.get(String.valueOf(a.get("localCandidateId")));
                            RTCStats rc = all.get(String.valueOf(a.get("remoteCandidateId")));
                            String lt = lc == null ? "?" : String.valueOf(lc.getAttributes().get("candidateType"));
                            String rt = rc == null ? "?" : String.valueOf(rc.getAttributes().get("candidateType"));
                            path = ("relay".equals(lt) || "relay".equals(rt) ? "relayed" : "direct") + "(" + lt + "/" + rt + ")";
                            break;
                        }
                    } catch (Throwable ignored) { }
                    out("OPEN " + openCount() + " " + path);
                });
            } catch (Throwable t) {
                out("OPEN " + openCount() + " unknown");
            }
        }

        static final long FIRST_BYTE_MS = 15000;

        void watchFirstByte() {
            Thread w = new Thread(() -> {
                try { Thread.sleep(FIRST_BYTE_MS); } catch (InterruptedException e) { return; }
                if (closed || received > 0) return;
                log("the room's host didn't answer a connection in " + FIRST_BYTE_MS / 1000 + " s - dropping it");
                tell("The room's host didn't answer - try joining again.");
                close();
            }, "openhouse first byte watch");
            w.setDaemon(true);
            w.start();
        }

        private volatile boolean told;

        void tell(String why) {
            if (tcp == null || received > 0 || told) return;
            told = true;
            try {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream d = new java.io.DataOutputStream(b);
                d.writeByte(255);
                d.writeShort(why.length());
                d.write(why.getBytes(StandardCharsets.UTF_16BE));
                d.flush();
                OutputStream o = tcp.getOutputStream();
                o.write(b.toByteArray());
                o.flush();
            } catch (IOException ignored) {
            }
        }

        void down() {
            try {
                OutputStream o = tcp.getOutputStream();
                while (true) {
                    byte[] b = toGame.take();
                    if (b.length == 0) break;
                    o.write(b);
                    if (toGame.isEmpty()) o.flush();
                }
                o.flush();
            } catch (Exception ignored) {
            } finally {
                close();
                try { tcp.close(); } catch (IOException ignored) { }
            }
        }

        void up() {
            try {
                InputStream in = tcp.getInputStream();
                byte[] buf = new byte[16384];
                int n;
                while (!closed && (n = in.read(buf)) > 0) {
                    RTCDataChannel ch = dc;
                    if (ch == null) break;
                    while (!closed && ch.getBufferedAmount() > MAX_BUFFERED) Thread.sleep(5);
                    ch.send(new RTCDataChannelBuffer(ByteBuffer.wrap(Arrays.copyOf(buf, n)), true));
                }
            } catch (Exception e) {
                if (!closed) log("connection ended: " + e.getMessage());
            } finally {
                close();
            }
        }

        void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            debug(cid + " closed" + (tcp == null ? (mods ? " (mods)" : " (probe)") : opened ? ", " + received + " bytes from the host" : " (never opened)"));
            links.remove(cid, this);
            toGame.offer(new byte[0]);
            tell("Lost the connection to the room's host - try joining again.");

            if (tcp != null && !piped) {
                try { tcp.close(); } catch (IOException ignored) { }
            } else if (tcp != null) {
                Thread g = new Thread(() -> {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                    try { tcp.close(); } catch (IOException ignored) { }
                }, "openhouse close socket");
                g.setDaemon(true);
                g.start();
            }

            if (!opened && pc != null)
                sendSignal(Json.map("type", "CANDIDATE", "dst", room, "payload",
                        Json.map("candidate", Json.map("candidate", "", "sdpMid", "0", "sdpMLineIndex", 0),
                                 "type", "data", "connectionId", cid, "bye", Boolean.TRUE)));
            final boolean wasOpen = opened;

            Thread t = new Thread(() -> {
                try { RTCDataChannel ch = dc; if (ch != null) { ch.unregisterObserver(); ch.close(); } } catch (Throwable ignored) { }
                try { if (pc != null) pc.close(); } catch (Throwable ignored) { }
                if (wasOpen && tcp != null) out("CLOSED " + openCount());
            }, "openhouse close");
            t.setDaemon(true);
            t.start();
        }
    }
}
