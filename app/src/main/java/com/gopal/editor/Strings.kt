package com.gopal.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Simple Hindi / English UI text. `L.t("English", "हिंदी")` picks by the user's toggle. */
object L {
    var lang by mutableStateOf("hi")
    val hi: Boolean get() = lang == "hi"
    fun t(en: String, hi: String): String = if (lang == "hi") hi else en
}
