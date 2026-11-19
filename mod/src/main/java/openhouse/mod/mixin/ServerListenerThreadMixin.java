package openhouse.mod.mixin;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Collections;
import net.minecraft.client.gui.server.ServerListenerThread;
import net.minecraft.client.gui.server.ServerManager;
import openhouse.mod.NearbyRooms;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerListenerThread.class, remap = false)
public abstract class ServerListenerThreadMixin {
    @Shadow @Final @NotNull public ServerManager serverManager;

    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Ljava/net/MulticastSocket;joinGroup(Ljava/net/InetAddress;)V"), require = 0)
    private void openhouse$join_all(MulticastSocket s, InetAddress grp) throws IOException {
        s.joinGroup(grp);
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                    boolean v4 = false;
                    for (InterfaceAddress a : ni.getInterfaceAddresses()) if (a.getAddress() instanceof Inet4Address) v4 = true;
                    if (v4) s.joinGroup(new InetSocketAddress(grp, 0), ni);
                } catch (IOException ignored) { }
            }
        } catch (Exception ignored) { }
    }

    @Shadow protected MulticastSocket multicastSocket;

    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;sleep(J)V"), require = 0)
    private void openhouse$drain(long ms) throws InterruptedException {
        MulticastSocket s = multicastSocket;
        if (s == null || s.isClosed()) { Thread.sleep(ms); return; }
        long end = System.currentTimeMillis() + ms;
        byte[] buf = new byte[1024];
        try {
            for (long left; (left = end - System.currentTimeMillis()) > 0; ) {
                s.setSoTimeout((int) Math.max(1, left));
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (java.net.SocketTimeoutException t) { break; }
                String msg = new String(p.getData(), 0, p.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                if (!"end".equals(msg)) ((ServerListenerThread) (Object) this).receivedLocalServer(p, msg);
            }
        } catch (IOException ignored) {
        } finally {
            try { s.setSoTimeout(0); } catch (IOException ignored) { }
        }
        long left = end - System.currentTimeMillis();
        if (left > 0) Thread.sleep(left);
    }

    @Inject(method = "receivedLocalServer", at = @At("HEAD"), cancellable = true, require = 0)
    private void openhouse$room(DatagramPacket p, String msg, CallbackInfo ci) {
        try {
            if (NearbyRooms.heard(serverManager, p.getAddress().getHostAddress(), msg)) ci.cancel();
        } catch (RuntimeException ignored) { }
    }
}
