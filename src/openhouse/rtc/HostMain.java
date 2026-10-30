package openhouse.rtc;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import openhouse.Json;
import openhouse.WsClient;

public final class HostMain {
    static final String PREFIX = openhouse.Rtc.PREFIX;
    static final String ALPHABET = openhouse.Rtc.ALPHABET;
    static final String SIGNAL = "wss://0.peerjs.com:443/peerjs?key=peerjs";

    static final long MAX_BUFFERED = 4L << 20;

    static final long OPEN_TIMEOUT_MS = 45000;

    static String code;
    static String targetHost;
    static int targetPort;
    static PeerConnectionFactory factory;
    static volatile WsClient signal;
    static final Map<String, Peer> peers = new ConcurrentHashMap<>();
    static final SecureRandom rnd = new SecureRandom();

    static void log(String s) { synchronized (System.out) { System.out.println("LOG " + s); System.out.flush(); } }

    static final boolean DEBUG = System.getenv("OPENHOUSE_DEBUG") != null;
    static void debug(String s) { if (DEBUG) log(String.format("%tT.%<tL %s", System.currentTimeMillis(), s)); }

    static volatile String shareJson;
    static final Map<String, java.io.File> shareFiles = new ConcurrentHashMap<>();
    static final String MODS_LABEL = "openhouse-mods";

    static void loadShare(java.io.File manifest) {
        try {
            String text = new String(java.nio.file.Files.readAllBytes(manifest.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> root = Json.obj(Json.parse(text));
            Map<String, Object> paths = root == null ? null : Json.obj(root.remove("paths"));
            if (paths != null) for (Map.Entry<String, Object> e : paths.entrySet()) shareFiles.put(e.getKey(), new java.io.File(String.valueOf(e.getValue())));

            root.put("openhouse", openhouse.Version.VERSION);
            shareJson = Json.write(root);
            log("sharing " + shareFiles.size() + " mod files with joiners");
        } catch (Exception e) {
            log("can't read the shared mod list " + manifest + ": " + e);
        }
    }

    public static void main(String[] args) throws Exception {
        code = args.length > 0 ? args[0].toUpperCase() : "";
        String target = args.length > 1 ? args[1] : "127.0.0.1:25565";
        int c = target.lastIndexOf(':');
        targetHost = c > 0 ? target.substring(0, c) : target;
        targetPort = c > 0 ? Integer.parseInt(target.substring(c + 1)) : 25565;
        if (!validCode(code)) code = newCode();

        String token = args.length > 2 && !args[2].isEmpty() ? args[2] : Long.toString(rnd.nextLong() & Long.MAX_VALUE, 36);

        if (args.length > 3 && !args[3].isEmpty()) loadShare(new java.io.File(args[3]));

        Thread watch = new Thread(() -> {
            try { while (System.in.read() >= 0) { } } catch (IOException ignored) { }
            for (Peer p : new ArrayList<>(peers.values())) {
                try { RTCDataChannel ch = p.dc; if (ch != null) ch.close(); } catch (Throwable ignored) { }
            }
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }
            System.exit(0);
        }, "parent watch");
        watch.setDaemon(true);
        watch.start();

        factory = new PeerConnectionFactory(new HeadlessAudioDeviceModule());

        int taken = 0;
        while (true) {
            final Object done = new Object();
            final String[] why = { null };
            final boolean[] idTaken = { false };
            try {
                signal = WsClient.connect(SIGNAL + "&id=" + PREFIX + code + "&token=" + token + "&version=1.5.4", new WsClient.Listener() {
                    public void onText(String text) {
                        debug("in  " + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
                        Map<String, Object> m = Json.obj(Json.parse(text));
                        if (m == null) return;
                        String type = Json.str(m.get("type"));
                        if ("ID-TAKEN".equals(type)) idTaken[0] = true;
                        onSignal(m);
                    }
                    public void onClosed(String w) { synchronized (done) { why[0] = w; done.notifyAll(); } }
                });

                synchronized (done) {
                    while (why[0] == null) {
                        done.wait(5000);
                        if (why[0] == null) {
                            try { signal.send("{\"type\":\"HEARTBEAT\"}"); } catch (IOException e) { break; }
                        }
                    }
                }
                signal.close();
            } catch (Exception e) {
                log("matchmaking server unreachable (" + e.getMessage() + "), trying again shortly");
            }
            if (idTaken[0]) {

                if (++taken >= 4) { code = newCode(); taken = 0; log("code was taken, using a new one"); }
            } else taken = 0;
            Thread.sleep(idTaken[0] ? 15000 : 5000);
        }
    }

    static boolean validCode(String c) {
        if (c == null || c.length() != 5) return false;
        for (int k = 0; k < 5; k++) if (ALPHABET.indexOf(c.charAt(k)) < 0) return false;
        return true;
    }

    static String newCode() {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < 5; k++) b.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        return b.toString();
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
            synchronized (System.out) { System.out.println("ROOM " + code); System.out.flush(); }
        } else if ("OFFER".equals(type) && payload != null && src != null) {
            String cid = Json.str(payload.get("connectionId"));
            Map<String, Object> sdp = Json.obj(payload.get("sdp"));
            if (cid == null || sdp == null) return;
            Peer old = peers.remove(cid);
            if (old != null) old.close();
            Peer p = new Peer(src, cid, MODS_LABEL.equals(Json.str(payload.get("label"))), Json.str(payload.get("oh")));
            peers.put(cid, p);
            p.offer(Json.str(sdp.get("sdp")));
            Thread w = new Thread(() -> {
                try { Thread.sleep(OPEN_TIMEOUT_MS); } catch (InterruptedException ignored) { }
                if (!p.piped && !p.closed) p.close();
            }, "openhouse open watchdog");
            w.setDaemon(true);
            w.start();
        } else if ("CANDIDATE".equals(type) && payload != null && Boolean.TRUE.equals(payload.get("bye"))) {

            Peer p = peers.get(Json.str(payload.get("connectionId")));
            if (p != null && p.remote.equals(src) && !p.piped) p.close();
        } else if ("CANDIDATE".equals(type) && payload != null) {
            Peer p = peers.get(Json.str(payload.get("connectionId")));
            Map<String, Object> c = Json.obj(payload.get("candidate"));
            if (p != null && c != null) {
                Object idx = c.get("sdpMLineIndex");
                p.candidate(Json.str(c.get("sdpMid")), idx instanceof Number ? ((Number) idx).intValue() : 0, Json.str(c.get("candidate")));
            }
        } else if ("LEAVE".equals(type) || "EXPIRE".equals(type)) {
            for (Peer p : new ArrayList<>(peers.values())) if (p.remote.equals(src)) p.close();
        } else if ("ERROR".equals(type)) {
            log("matchmaking server error: " + (payload != null ? payload.get("msg") : ""));
        } else if ("ID-TAKEN".equals(type)) {
            log("room code " + code + " is still registered elsewhere");
        }
    }

    static final class Peer implements PeerConnectionObserver {
        final String remote, cid;
        final boolean mods;
        final String theirVersion;
        final RTCPeerConnection pc;
        final List<RTCIceCandidate> early = new ArrayList<>();
        boolean remoteSet;
        volatile RTCDataChannel dc;
        volatile Socket tcp;
        final BlockingQueue<byte[]> toServer = new LinkedBlockingQueue<>();
        volatile boolean closed;

        Peer(String remote, String cid, boolean mods, String theirVersion) {
            this.remote = remote;
            this.cid = cid;
            this.mods = mods;
            this.theirVersion = theirVersion;
            RTCConfiguration cfg = new RTCConfiguration();

            RTCIceServer stun = new RTCIceServer();
            stun.urls = Arrays.asList("stun:stun.l.google.com:19302");
            RTCIceServer turn = new RTCIceServer();
            turn.urls = Arrays.asList("turn:eu-0.turn.peerjs.com:3478", "turn:us-0.turn.peerjs.com:3478");
            turn.username = "peerjs";
            turn.password = "peerjsp";
            cfg.iceServers = new ArrayList<>(Arrays.asList(stun, turn));
            pc = factory.createPeerConnection(cfg, this);
        }

        void offer(String sdp) {
            pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, sdp), new SetSessionDescriptionObserver() {
                public void onSuccess() {
                    synchronized (Peer.this) {
                        remoteSet = true;
                        for (RTCIceCandidate c : early) pc.addIceCandidate(c);
                        early.clear();
                    }
                    pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                        public void onSuccess(RTCSessionDescription answer) {
                            pc.setLocalDescription(answer, new SetSessionDescriptionObserver() {
                                public void onSuccess() {
                                    sendSignal(Json.map("type", "ANSWER", "dst", remote, "payload",
                                            Json.map("sdp", Json.map("type", "answer", "sdp", answer.sdp), "type", "data", "connectionId", cid)));
                                }
                                public void onFailure(String e) { log("setLocalDescription: " + e); close(); }
                            });
                        }
                        public void onFailure(String e) { log("createAnswer: " + e); close(); }
                    });
                }
                public void onFailure(String e) { log("setRemoteDescription: " + e); close(); }
            });
        }

        synchronized void candidate(String mid, int index, String sdp) {
            if (sdp == null || sdp.isEmpty()) return;
            RTCIceCandidate c = new RTCIceCandidate(mid, index, sdp);
            if (remoteSet) pc.addIceCandidate(c); else early.add(c);
        }

        @Override public void onIceCandidate(RTCIceCandidate c) {
            sendSignal(Json.map("type", "CANDIDATE", "dst", remote, "payload",
                    Json.map("candidate", Json.map("candidate", c.sdp, "sdpMid", c.sdpMid, "sdpMLineIndex", c.sdpMLineIndex),
                             "type", "data", "connectionId", cid)));
        }

        @Override public void onConnectionChange(RTCPeerConnectionState s) {
            debug(cid + " connection " + s);
            if (s == RTCPeerConnectionState.FAILED || s == RTCPeerConnectionState.CLOSED) close();
        }

        @Override public void onDataChannel(RTCDataChannel channel) {
            debug(cid + " data channel arrived (" + channel.getState() + ")");
            dc = channel;
            channel.registerObserver(new RTCDataChannelObserver() {
                public void onBufferedAmountChange(long previous) { }
                public void onStateChange() {
                    RTCDataChannelState st = channel.getState();
                    if (st == RTCDataChannelState.OPEN) startPipe();
                    else if (st == RTCDataChannelState.CLOSED) close();
                }
                public void onMessage(RTCDataChannelBuffer b) {

                    ByteBuffer d = b.data;
                    byte[] bytes = new byte[d.remaining()];
                    d.get(bytes);
                    toServer.offer(bytes);
                }
            });

            if (channel.getState() == RTCDataChannelState.OPEN) startPipe();
        }

        volatile boolean piped;
        synchronized void startPipe() {
            debug(cid + " channel open" + (piped ? " (already piped)" : "") + (closed ? " (already closed)" : ""));
            if (piped || closed) return;
            piped = true;
            Runnable job = mods ? this::serveMods : openhouse.Version.same(theirVersion, openhouse.Version.VERSION) ? this::pipe : this::refuse;
            Thread t = new Thread(job, mods ? "openhouse mods" : "openhouse player");
            t.setDaemon(true);
            t.start();
        }

        void serveMods() {
            int sent = 0;
            try {
                while (!closed) {
                    byte[] b = toServer.take();
                    if (b.length == 0) break;
                    String cmd = new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (cmd.equals("LIST")) {

                        String none = "{\"mods\":[],\"openhouse\":\"" + openhouse.Version.VERSION + "\"}";
                        sendText(shareJson == null || theirVersion == null ? none : shareJson);
                    } else if (cmd.startsWith("GET ")) {
                        String sha = cmd.substring(4).trim();
                        java.io.File f = shareFiles.get(sha);
                        if (f == null || !f.isFile()) {
                            sendText(Json.write(Json.map("get", sha, "error", "not shared")));
                            continue;
                        }
                        sendText(Json.write(Json.map("get", sha, "size", (double) f.length())));
                        try (InputStream in = new java.io.FileInputStream(f)) {
                            byte[] buf = new byte[16384];
                            int n;
                            while (!closed && (n = in.read(buf)) > 0) {
                                RTCDataChannel ch = dc;
                                while (!closed && ch.getBufferedAmount() > MAX_BUFFERED) Thread.sleep(5);
                                ch.send(new RTCDataChannelBuffer(ByteBuffer.wrap(Arrays.copyOf(buf, n)), true));
                            }
                        }
                        sendText("DONE " + sha);
                        sent++;
                    }
                }
            } catch (Exception e) {
                if (!closed) log("mod transfer ended: " + e.getMessage());
            } finally {
                if (sent > 0) log("sent " + sent + " mod file" + (sent == 1 ? "" : "s") + " to a joiner");
                close();
            }
        }

        void refuse() {
            String v = openhouse.Version.VERSION;
            String msg = theirVersion == null ? "This world uses OpenHouse " + v + ". Update OpenHouse to join."
                    : openhouse.Version.compare(theirVersion, v) < 0 ? "This world uses OpenHouse " + v + " (you have " + openhouse.Version.base(theirVersion) + "). Update OpenHouse to join."
                    : "This world uses OpenHouse " + v + " (you have " + openhouse.Version.base(theirVersion) + "). The host needs to update OpenHouse.";
            log("turned away a player on " + (theirVersion == null ? "an older OpenHouse" : "OpenHouse " + theirVersion) + " - this room runs " + v);
            try {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream d = new java.io.DataOutputStream(b);
                d.writeByte(255);
                d.writeShort(msg.length());
                d.write(msg.getBytes(java.nio.charset.StandardCharsets.UTF_16BE));
                d.flush();
                RTCDataChannel ch = dc;
                if (ch != null) ch.send(new RTCDataChannelBuffer(ByteBuffer.wrap(b.toByteArray()), true));
                long until = System.currentTimeMillis() + 3000;
                while (!closed && System.currentTimeMillis() < until) Thread.sleep(50);
            } catch (Exception e) {
                if (!closed) log("couldn't tell a player why: " + e.getMessage());
            } finally {
                close();
            }
        }

        void sendText(String s) throws Exception {
            RTCDataChannel ch = dc;
            while (!closed && ch.getBufferedAmount() > MAX_BUFFERED) Thread.sleep(5);
            ch.send(new RTCDataChannelBuffer(ByteBuffer.wrap(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)), false));
        }

        void pipe() {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(targetHost, targetPort), 5000);
                s.setTcpNoDelay(true);
                tcp = s;
                debug(cid + " piped to the server from port " + s.getLocalPort());
                players();

                Thread up = new Thread(() -> {
                    try {
                        OutputStream out = s.getOutputStream();
                        while (!closed) {
                            byte[] b = toServer.take();
                            if (b.length == 0) break;
                            out.write(b);
                            if (toServer.isEmpty()) out.flush();
                        }
                    } catch (Exception ignored) {
                    } finally { close(); }
                }, "openhouse up");
                up.setDaemon(true);
                up.start();

                InputStream in = s.getInputStream();
                byte[] buf = new byte[16384];
                int n;
                while (!closed && (n = in.read(buf)) > 0) {
                    RTCDataChannel ch = dc;
                    if (ch == null) break;
                    while (!closed && ch.getBufferedAmount() > MAX_BUFFERED) Thread.sleep(5);
                    ch.send(new RTCDataChannelBuffer(ByteBuffer.wrap(Arrays.copyOf(buf, n)), true));
                }
            } catch (Exception e) {
                if (!closed) log("player connection ended: " + e.getMessage());
            } finally {
                close();
            }
        }

        static void players() {
            int n = 0;
            for (Peer p : peers.values()) if (p.tcp != null && !p.closed) n++;
            synchronized (System.out) { System.out.println("PLAYERS " + n); System.out.flush(); }
        }

        void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            debug(cid + " closed" + (piped ? "" : " (never piped)"));
            peers.remove(cid, this);
            toServer.offer(new byte[0]);
            try { if (tcp != null) tcp.close(); } catch (IOException ignored) { }

            final boolean wasPlayer = tcp != null;
            Thread t = new Thread(() -> {
                try { RTCDataChannel ch = dc; if (ch != null) ch.close(); } catch (Throwable ignored) { }
                try { pc.close(); } catch (Throwable ignored) { }
                if (wasPlayer) players();
            }, "openhouse close");
            t.setDaemon(true);
            t.start();
        }
    }
}
