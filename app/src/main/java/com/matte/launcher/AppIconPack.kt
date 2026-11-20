package com.matte.launcher

import android.content.Context
import android.graphics.drawable.Drawable

/** Custom line-art app icons, looked up by app label from drawables named ic_pack_<slug>. */
internal object AppIconPack {
    private val ids = HashMap<String, Int>()

    fun slug(label: String): String {
        val s = label.lowercase().replace("+", " plus").replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return if (s.startsWith("matte_live")) "matte_live" else s
    }

    fun forApp(context: Context, label: String, packageName: String): Drawable? {
        var slug = slug(label)
        if (slug.isEmpty()) return null
        if (slug == "photos") slug = if (packageName.startsWith("com.google")) "photos_google" else "photos_samsung"
        val id = ids.getOrPut(slug) { context.resources.getIdentifier("ic_pack_$slug", "drawable", context.packageName) }
        return if (id == 0) null else context.getDrawable(id)?.mutate()
    }
}
