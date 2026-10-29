package openhouse;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class WsClient {
    public interface Listener {
        void onText(String text);
        void onClosed(String why);
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom rnd = new SecureRandom();
    private final Object sendLock = new Object();
    private volatile boolean closed;

    private WsClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    public static WsClient connect(String url, Listener listener) throws IOException {
        URI u = URI.create(url);
        boolean tls = "wss".equalsIgnoreCase(u.getScheme());
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        Socket s;
        if (tls) {
            SSLSocket ss = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            ss.connect(new InetSocketAddress(host, port), 10000);
            SSLParameters p = ss.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            ss.setSSLParameters(p);

            try {
                p.setServerNames(java.util.Collections.<javax.net.ssl.SNIServerName>singletonList(new javax.net.ssl.SNIHostName(host)));
                ss.setSSLParameters(p);
            } catch (Throwable ignored) { }
            ss.startHandshake();
            s = ss;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), 10000);
        }
        s.setTcpNoDelay(true);
        s.setSoTimeout(15000);
        WsClient c = new WsClient(s);
        byte[] keyBytes = new byte[16];
        c.rnd.nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + (u.getPort() > 0 ? ":" + port : "")
                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key
                + "\r\nSec-WebSocket-Version: 13\r\nUser-Agent: openhouse\r\n\r\n";
        c.out.write(req.getBytes(StandardCharsets.ISO_8859_1));
        c.out.flush();
        String status = c.readLine();
        if (status == null || !status.contains(" 101")) throw new IOException("signaling server refused the upgrade: " + status);
        while (true) {
            String line = c.readLine();
            if (line == null) throw new EOFException();
            if (line.isEmpty()) break;
        }
        s.setSoTimeout(0);
        Thread t = new Thread(() -> c.readLoop(listener), "openhouse signaling");
        t.setDaemon(true);
        t.start();
        return c;
    }

    private String readLine() throws IOException {
        StringBuilder b = new StringBuilder();
        while (true) {
            int ch = in.read();
            if (ch < 0) return b.length() == 0 ? null : b.toString();
            if (ch == '\n') return b.toString();
            if (ch != '\r') b.append((char) ch);
            if (b.length() > 8192) throw new IOException("header line too long");
        }
    }

    private void readLoop(Listener listener) {
        String why = "closed";
        java.io.ByteArrayOutputStream msg = new java.io.ByteArrayOutputStream();
        try {
            while (!closed) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = read1();
                int op = b0 & 15;
                boolean fin = (b0 & 0x80) != 0;
                long len = b1 & 127;
                if (len == 126) len = (read1() << 8) | read1();
                else if (len == 127) { len = 0; for (int k = 0; k < 8; k++) len = (len << 8) | read1(); }
                if (len < 0 || len > (8 << 20)) { why = "frame too big"; break; }
                byte[] mask = null;
                if ((b1 & 0x80) != 0) { mask = new byte[4]; readFully(mask); }
                byte[] p = new byte[(int) len];
                readFully(p);
                if (mask != null) for (int k = 0; k < p.length; k++) p[k] ^= mask[k & 3];
                if (op == 8) {

                    why = "server closed";
                    if (p.length >= 2) {
                        why += " " + (((p[0] & 255) << 8) | (p[1] & 255));
                        if (p.length > 2) why += " " + new String(p, 2, p.length - 2, StandardCharsets.UTF_8);
                    }
                    break;
                }
                if (op == 9) { sendFrame(0xA, p); continue; }
                if (op == 10) continue;
                msg.write(p, 0, p.length);
                if (fin) {
                    String text = new String(msg.toByteArray(), StandardCharsets.UTF_8);
                    msg.reset();
                    try { listener.onText(text); } catch (Throwable t) { t.printStackTrace(); }
                }
            }
        } catch (IOException e) {
            why = e.toString();
        }
        close();
        listener.onClosed(why);
    }

    private int read1() throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private void readFully(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
    }

    public void send(String text) throws IOException {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendFrame(int op, byte[] data) throws IOException {
        synchronized (sendLock) {
            if (closed) throw new IOException("closed");
            byte[] mask = new byte[4];
            rnd.nextBytes(mask);
            int n = data.length;
            out.write(0x80 | op);
            if (n < 126) out.write(0x80 | n);
            else if (n < 65536) { out.write(0x80 | 126); out.write(n >> 8); out.write(n); }
            else { out.write(0x80 | 127); for (int k = 7; k >= 0; k--) out.write(k >= 4 ? 0 : (n >> (8 * k))); }
            out.write(mask);
            byte[] m = new byte[n];
            for (int k = 0; k < n; k++) m[k] = (byte) (data[k] ^ mask[k & 3]);
            out.write(m);
            out.flush();
        }
    }

    public boolean isOpen() { return !closed; }

    public void close() {
        if (closed) return;
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
    }
}
