package openhouse.mod;

import net.minecraft.client.option.OptionBoolean;

public final class LocatorOption extends OptionBoolean {
    public static final LocatorOption INSTANCE = new LocatorOption();

    private LocatorOption() {
        super("openhouse.locator", OpenHouseMod.locator_on());
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        OpenHouseMod.set_locator(value);
    }
}
