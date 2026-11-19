package openhouse.mod.mixin.server;

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
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.net.MulticastPublisher;
import openhouse.mod.server.ServerRoom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MulticastPublisher.class, remap = false)
public abstract class MulticastMixin {
    @Unique private static final AtomicBoolean busy = new AtomicBoolean();
    @Unique private static volatile List<NetworkInterface> nics = Collections.emptyList();
    @Unique private static volatile long nics_at;

    @Unique private static final String run_id = Long.toString(new java.util.Random().nextLong() & Long.MAX_VALUE, 36);

    @Inject(method = "multicast", at = @At("HEAD"), cancellable = true, require = 0)
    private void openhouse$announce(String msg, CallbackInfo ci) {
        ci.cancel();
        if (!busy.compareAndSet(false, true)) return;
        final byte[] b = ("[OHS]" + run_id + "[/OHS]" + ServerRoom.lan_tag() + msg).getBytes(StandardCharsets.UTF_8);
        Thread t = new Thread(() -> {
            try { openhouse$send(b); } finally { busy.set(false); }
        }, "OpenHouse LAN announce");
        t.setDaemon(true);
        t.start();
    }

    @Unique
    private static void openhouse$send(byte[] b) {
        try (MulticastSocket s = new MulticastSocket()) {
            InetAddress grp = InetAddress.getByName("224.0.2.61");
            DatagramPacket p = new DatagramPacket(b, b.length, grp, 4445);
            int sent = 0;
            for (NetworkInterface ni : openhouse$nics()) {
                try { s.setNetworkInterface(ni); s.send(p); sent++; } catch (Exception ignored) { }
            }
            if (sent == 0) {
                try (MulticastSocket d = new MulticastSocket()) { d.send(p); }
            }
        } catch (Exception ignored) { }
    }

    @Unique
    private static List<NetworkInterface> openhouse$nics() {
        long now = System.currentTimeMillis();
        if (now - nics_at < 30_000L) return nics;
        List<NetworkInterface> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                    boolean v4 = false;
                    for (InterfaceAddress a : ni.getInterfaceAddresses()) if (a.getAddress() instanceof Inet4Address) v4 = true;
                    if (v4) out.add(ni);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        nics = out;
        nics_at = now;
        return out;
    }
}
