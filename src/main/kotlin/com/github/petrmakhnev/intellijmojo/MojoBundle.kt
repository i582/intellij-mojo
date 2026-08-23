package com.github.petrmakhnev.intellijmojo

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.MojoBundle"

object MojoBundle : DynamicBundle(BUNDLE) {
    @Nls
    operator fun get(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = getMessage(key, *params)

    fun messagePointer(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ) = getLazyMessage(key, *params)
}
