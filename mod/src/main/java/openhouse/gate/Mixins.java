package openhouse.gate;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class Mixins implements IMixinConfigPlugin {
    boolean mine;

    @Override public void onLoad(String pkg) { mine = (pkg.startsWith("openhouse.mod7.") || pkg.startsWith("openhouse.v7.")) == Gate.v7(); }
    @Override public String getRefMapperConfig() { return null; }

    @Override public boolean shouldApplyMixin(String target, String mixin) { return mine && !(Gate.v7() && mixin.endsWith(".WorldSlotMixin")); }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
