package openhouse.mod;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import openhouse.OpenHostList;

final class MoveBack {
    private MoveBack() { }

    static String run(OpenHostList.Server s, boolean move) throws IOException {
        if (MoveToOpenHost.running(s, false)) throw new IOException("Its OpenHost server is running. Stop it first.");
        File src = s.world();
        if (!new File(src, "level.dat").isFile()) throw new IOException("the server's world isn't there any more");
        File saves = new File(MoveToOpenHost.game(), "saves");
        saves.mkdirs();
        String name = src.getName();
        File dest = new File(saves, name);
        for (int n = 2; dest.exists(); n++) dest = new File(saves, name + " " + n);
        WorldGuard.backup(src, move ? "before-moving-back" : "before-copying-here");
        if (move) {
            try { Files.move(src.toPath(), dest.toPath()); }
            catch (IOException e) { Relaunch.copyTree(src, dest); WorldGuard.deleteTree(src); }
            new File(s.folder, MoveToOpenHost.MOVED).delete();
        } else {
            Relaunch.copyTree(src, dest);
        }
        new File(dest, "session.lock").delete();
        openhouse.OhCrash.note((move ? "moved" : "copied") + " OpenHost world " + name + " back to single player as " + dest.getName());
        return dest.getName();
    }
}
