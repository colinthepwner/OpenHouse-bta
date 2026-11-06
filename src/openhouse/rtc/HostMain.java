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

    static final String JOINER_PREFIX = "bta-join-";

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

    static volatile java.io.File poolRoot;
    static volatile java.util.List<String> poolFolders = java.util.Collections.emptyList();

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

        if (args.length > 4 && !args[4].isEmpty()) {
            String[] pool = args[4].split("\t");
            java.util.List<String> fs = openhouse.ShareFolders.folders(pool.length > 1 ? pool[1] : null);
            if (!pool[0].isEmpty() && !fs.isEmpty()) {
                poolRoot = new java.io.File(pool[0]);
                poolFolders = fs;
                log("players may add to this server's " + String.join(", ", fs) + " folder" + (fs.size() == 1 ? "" : "s"));
            }
        }

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

                synchronized (System.out) { System.out.println("TAKEN " + code); System.out.flush(); }
                if (!openhouse.Rtc.isPicked(code) && ++taken >= 4) { code = newCode(); taken = 0; log("code was taken, using a new one"); }
            } else taken = 0;
            Thread.sleep(idTaken[0] ? 15000 : 5000);
        }
    }

    static boolean validCode(String c) {
        return c != null && c.equals(openhouse.Rtc.normalizeCode(c));
    }

    static String newCode() {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < 5; k++) b.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        return b.toString();
    }

    static final Map<String, Long> MISMATCH_SAID = new java.util.concurrent.ConcurrentHashMap<>();

    static void mismatch(String remote, String theirs) {
        long now = System.currentTimeMillis();
        Long last = MISMATCH_SAID.put(remote == null ? "" : remote, now);
        if (last != null && now - last < 60000) return;
        String v = openhouse.Version.VERSION;
        String line = theirs == null
                ? "A player tried to join with an old OpenHouse (before 1.0.2) - this server runs " + v + ", so they were asked to update."
                : openhouse.Version.compare(theirs, v) < 0
                ? "A player tried to join with OpenHouse " + openhouse.Version.base(theirs) + ", older than this server's " + v + " - they were asked to update."
                : "A player tried to join with OpenHouse " + openhouse.Version.base(theirs) + ", newer than this server's " + v + " - update this server to let them in.";
        synchronized (System.out) { System.out.println("WARN " + line); System.out.flush(); }
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
            p.web = !src.startsWith(JOINER_PREFIX);
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

            debug("room code " + code + " is still registered elsewhere");
        }
    }

    static final class Peer implements PeerConnectionObserver {
        final String remote, cid;
        final boolean mods;
        final String theirVersion;

        volatile boolean web;
        final RTCPeerConnection pc;
        final List<RTCIceCandidate> early = new ArrayList<>();
        boolean remoteSet;
        volatile RTCDataChannel dc;
        volatile Socket tcp;
        volatile Thread upThread;
        static final long UP_LINGER_MS = 1000;
        static final long UP_DRAIN_MS = UP_LINGER_MS + 2000;
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

        void candidate(String mid, int index, String sdp) {
            if (sdp == null || sdp.isEmpty()) return;
            RTCIceCandidate c = new RTCIceCandidate(mid, index, sdp);
            synchronized (this) {
                if (closed) return;
                if (!remoteSet) { early.add(c); return; }
            }
            try { pc.addIceCandidate(c); } catch (RuntimeException e) { debug(cid + " candidate not added: " + e); }
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

                    if (DEBUG && bytes.length > 0 && bytes[0] == (byte) 255) debug(cid + " joiner sent a disconnect packet (" + bytes.length + " bytes)");
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

                        if (!openhouse.Version.same(theirVersion, openhouse.Version.VERSION)) mismatch(remote, theirVersion);

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
                    } else if (cmd.startsWith("FOLDERS")) {

                        sendText(wantedFrom(cmd));
                    } else if (cmd.startsWith("FPUT ")) {
                        takeFile(cmd);
                    }
                }
            } catch (Exception e) {
                if (!closed) log("mod transfer ended: " + e.getMessage());
            } finally {
                if (sent > 0) log("sent " + sent + " mod file" + (sent == 1 ? "" : "s") + " to a joiner");
                close();
            }
        }

        long accepted;
        final java.util.Map<String, long[]> wanted = new java.util.HashMap<>();
        final java.util.Map<String, String> wantedPath = new java.util.HashMap<>();

        String wantedFrom(String cmd) {
            java.io.File root = poolRoot;
            java.util.List<String> folders = poolFolders;
            if (root == null || folders.isEmpty()) return "WANT";
            java.util.Set<String> here = new java.util.HashSet<>();
            for (openhouse.ShareFolders.Item it : openhouse.ShareFolders.scan(root, folders)) here.add(it.sha256);
            StringBuilder b = new StringBuilder("WANT");
            int n = cmd.indexOf('\n');
            if (n < 0) return b.toString();
            long room = openhouse.ShareFolders.MAX_TOTAL - accepted;
            for (String line : cmd.substring(n + 1).split("\n")) {
                String[] f = line.trim().split(" ", 3);
                if (f.length < 3) continue;
                String sha = f[0];
                long size;
                try { size = Long.parseLong(f[1]); } catch (NumberFormatException e) { continue; }
                String path = openhouse.ShareFolders.safePath(f[2], folders);
                if (path == null || !sha.matches("[0-9a-f]{64}") || size <= 0 || size > openhouse.ShareFolders.MAX_FILE) continue;
                if (here.contains(sha) || wanted.containsKey(sha) || size > room) continue;
                if (wanted.size() >= openhouse.ShareFolders.MAX_FILES) break;
                wanted.put(sha, new long[] { size });
                wantedPath.put(sha, path);
                room -= size;
                b.append('\n').append(sha).append(' ').append(size).append(' ').append(path);
            }
            return b.toString();
        }

        void takeFile(String cmd) throws Exception {
            String[] f = cmd.substring(5).trim().split(" ", 3);
            if (f.length < 3) { sendText("FNO - malformed"); return; }
            String sha = f[0];
            long[] want = wanted.get(sha);
            String path = wantedPath.get(sha);
            long size;
            try { size = Long.parseLong(f[1]); } catch (NumberFormatException e) { sendText("FNO " + sha + " size"); return; }
            if (want == null || path == null || size != want[0] || !path.equals(openhouse.ShareFolders.safePath(f[2], poolFolders))) {
                sendText("FNO " + sha + " not asked for");
                return;
            }
            sendText("FOK " + sha);
            byte[] bytes = new byte[(int) size];
            int got = 0;
            while (got < size && !closed) {
                byte[] b = toServer.poll(30, java.util.concurrent.TimeUnit.SECONDS);
                if (b == null) throw new java.io.IOException("the transfer of " + path + " stalled");
                if (b.length == 0) throw new java.io.IOException("the player left during " + path);
                if (got + b.length > size) throw new java.io.IOException("too many bytes for " + path);
                System.arraycopy(b, 0, bytes, got, b.length);
                got += b.length;
            }
            if (closed) return;
            if (!openhouse.Rtc.sha256(bytes).equals(sha)) { sendText("FNO " + sha + " damaged"); return; }
            wanted.remove(sha);
            wantedPath.remove(sha);
            accepted += size;
            java.io.File dest = openhouse.ShareFolders.receive(poolRoot, path, bytes, sha);
            sendText("FGOT " + sha);

            if (dest != null) log("a player added " + path + (dest.getName().equals(new java.io.File(path).getName()) ? "" : " (as " + dest.getName() + ")") + " to this server");
        }

        void refuse() {
            String v = openhouse.Version.VERSION;
            String msg = theirVersion == null ? "This world uses OpenHouse " + v + ". Update OpenHouse to join."
                    : openhouse.Version.compare(theirVersion, v) < 0 ? "This world uses OpenHouse " + v + " (you have " + openhouse.Version.base(theirVersion) + "). Update OpenHouse to join."
                    : "This world uses OpenHouse " + v + " (you have " + openhouse.Version.base(theirVersion) + "). The host needs to update OpenHouse.";
            mismatch(remote, theirVersion);
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

            final java.util.concurrent.CountDownLatch serverDone = new java.util.concurrent.CountDownLatch(1);
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(targetHost, targetPort), 5000);
                s.setTcpNoDelay(true);
                tcp = s;
                debug(cid + " piped to the server from port " + s.getLocalPort());
                if (web) synchronized (System.out) { System.out.println("WEB " + s.getLocalPort()); System.out.flush(); }
                players();

                Thread up = new Thread(() -> {
                    try {
                        OutputStream out = s.getOutputStream();
                        while (true) {
                            byte[] b = toServer.take();
                            if (b.length == 0) break;
                            out.write(b);
                            if (toServer.isEmpty()) out.flush();
                        }
                        out.flush();
                        serverDone.await(UP_LINGER_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (Exception ignored) {
                    } finally {
                        try { s.close(); } catch (IOException ignored) { }
                        close();
                    }
                }, "openhouse up");
                up.setDaemon(true);
                upThread = up;
                up.start();

                InputStream in = s.getInputStream();
                byte[] buf = new byte[16384];
                int n;
                boolean sending = true;
                while ((n = in.read(buf)) > 0) {
                    if (!sending || closed) continue;
                    RTCDataChannel ch = dc;
                    if (ch == null) { sending = false; close(); continue; }
                    try {
                        while (!closed && ch.getBufferedAmount() > MAX_BUFFERED) Thread.sleep(5);
                        if (!closed) ch.send(new RTCDataChannelBuffer(ByteBuffer.wrap(Arrays.copyOf(buf, n)), true));
                    } catch (Exception e) {

                        sending = false;
                        if (!closed) {
                            boolean gone;
                            try { gone = ch.getState() != RTCDataChannelState.OPEN; }
                            catch (Throwable t) { gone = true; }
                            if (gone) debug(cid + " channel closed by the joiner while the server was sending (" + e.getMessage() + ")");
                            else log("player connection ended: " + e.getMessage());
                        }
                        close();
                    }
                }
            } catch (Exception e) {

                if (!closed) log("player connection ended: " + e.getMessage());
            } finally {
                serverDone.countDown();
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

            final Socket s = tcp;
            final Thread up = upThread;
            if (up == null) try { if (s != null) s.close(); } catch (IOException ignored) { }

            final boolean wasPlayer = s != null;
            Thread t = new Thread(() -> {
                if (up != null) {
                    try { up.join(UP_DRAIN_MS); } catch (InterruptedException ignored) { }
                    try { s.close(); } catch (IOException ignored) { }
                }
                try { RTCDataChannel ch = dc; if (ch != null) ch.close(); } catch (Throwable ignored) { }
                try { pc.close(); } catch (Throwable ignored) { }
                if (wasPlayer) players();
            }, "openhouse close");
            t.setDaemon(true);
            t.start();
        }
    }
}
