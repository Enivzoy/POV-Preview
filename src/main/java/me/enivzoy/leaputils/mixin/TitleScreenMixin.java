package me.enivzoy.leaputils.mixin;

import me.enivzoy.leaputils.pov.SavepointPlanScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FriendsButton;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Title screen tweaks: drops the three square icon buttons (friends / language / accessibility) and squeezes
 * a "Custom Planner" button into the bottom row between Options and Quit Game. Runs at the tail of init() so
 * it re-applies on every rebuild (resize) - vanilla recreates all widgets each pass, so this stays idempotent.
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "init()V", at = @At("TAIL"))
    private void leaputils$plannerButton(CallbackInfo ci) {
        Screen self = (Screen) (Object) this;

        AbstractWidget options = null;
        AbstractWidget quit = null;
        List<AbstractWidget> icons = new ArrayList<>();
        for (var listener : self.children()) {
            if (!(listener instanceof AbstractWidget widget)) continue;
            // the icons are exactly the friends + sprite (language/accessibility) buttons vanilla adds here
            if (widget instanceof FriendsButton || widget instanceof SpriteIconButton) {
                icons.add(widget);
                continue;
            }
            // locale-proof identification: resolve the same translation keys vanilla uses
            String msg = widget.getMessage().getString();
            if (msg.equals(Component.translatable("menu.options").getString())) options = widget;
            else if (msg.equals(Component.translatable("menu.quit").getString())) quit = widget;
        }

        for (AbstractWidget icon : icons) self.removeWidget(icon);

        if (options == null || quit == null) return;
        int bw = options.getWidth();
        int gap = 4;
        int total = bw * 3 + gap * 2;
        int x0 = self.width / 2 - total / 2;
        int y = options.getY();

        options.setX(x0);
        quit.setX(x0 + 2 * (bw + gap));

        Button planner = Button.builder(Component.literal("Custom Planner"),
                        b -> Minecraft.getInstance().setScreenAndShow(new SavepointPlanScreen()))
                .bounds(x0 + bw + gap, y, bw, options.getHeight())
                .build();
        self.addRenderableWidget(planner);
    }
}
