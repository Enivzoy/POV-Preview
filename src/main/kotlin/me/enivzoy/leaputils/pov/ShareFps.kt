package me.enivzoy.leaputils.pov

object ShareFps {
    const val NONE = 15
    @JvmStatic fun showsNone(setting: String, value: Number): Boolean = setting == "Sharing FPS ❗" && value.toInt() <= NONE
}
