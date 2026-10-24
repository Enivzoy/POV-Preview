package me.enivzoy.leaputils.pov

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.EventPriority
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.render.textDim
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

object PovPreviews : Module(
    name = "POV Previews",
    category = Category.custom("Leap Utils", 860, 10),
    description = "Renders each teammate's first-person view into their quarter of Odin's Spirit Leap menu.",
) {

    val previewsPerFrame by NumberSetting(
        "Previews Per Frame", 1, 1..4, 1,
        desc = "Feeds refreshed each frame; the rest keep their last image. Higher is smoother and costs more."
    )

    val resolution by NumberSetting(
        "Resolution", 0.75f, 0.25..1.0, 0.05f,
        desc = "Render scale of each feed, as a fraction of its quadrant. Lower is cheaper and softer."
    )

    val opacity by NumberSetting(
        "Opacity", 1f, 0.1..1.0, 0.05f,
        desc = "How opaque the previews are. Below 1 you can see your own game through them."
    )

    enum class HeadSmoothing { VANILLA, RAW }

    val headSmoothing by SelectorSetting(
        "Head Smoothing", HeadSmoothing.VANILLA, desc = "How a teammate's head movement is interpolated for their preview.",
        listOf(HeadSmoothing.VANILLA, HeadSmoothing.RAW)
    )

    val maxDistance by NumberSetting(
        "Max Distance", 2000, 16..5000, 16,
        desc = "Previews skip teammates further away than this. Guards against corrupt far-away positions."
    )

    val showCost by BooleanSetting(
        "Show Cost", true,
        desc = "HUD line with the milliseconds the previews added to the last frame."
    )

    val disableOnError by BooleanSetting(
        "Disable On Error", true,
        desc = "Turn the previews off for the session if rendering one throws, instead of retrying every frame."
    )


    val mode: PovPose.Mode
        get() = if (headSmoothing == HeadSmoothing.RAW) PovPose.Mode.RAW else PovPose.Mode.VANILLA

    private     val costHud by HUD(
        "POV Cost",
        desc = "Milliseconds the POV previews added to the last frame, and how many feeds are live.",
        x = 491,
        y = 415,
        scale = 1f
    ) { example ->
        val line = if (example) "\u00a77POV \u00a7f1.8\u00a77ms  \u00a7f4\u00a77/4" else PovCapture.costLine() ?: return@HUD 0 to 0
        val width = textDim(line, 0, 0, Colors.WHITE).first
        text(line, 0, 0, Colors.WHITE)
        width to 9
    }

    init {
        on<ScreenEvent.Render>(EventPriority.HIGHEST + 100) {
            try {
                PovCapture.onScreenExtract(guiGraphics)
            } catch (t: Throwable) {
                // fallback to pip
                try {
                    PovPip.onScreenExtract(guiGraphics)
                } catch (_: Throwable) {
                }
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            PovCapture.releaseIfUnwanted()
        }

        on<LevelEvent.Load> {
            PovCapture.onWorldChange()
        }
    }

    override fun onDisable() {
        PovCapture.requestFree()
        super.onDisable()
    }

    fun wants(): Boolean {
        if (!enabled || PovCapture.disabledForSession) return false
        if (!LeapMenu.enabled) return false
        val mc = Minecraft.getInstance()
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return false
        val title = screen.title.string
        return title == "Spirit Leap" || title == "Teleport to Player" || title == "Obsidian"
    }
}
