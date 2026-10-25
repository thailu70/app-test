package com.example.data.api

/** Matches the server's supported Ethiopian mobile spellings without implying ownership. */
fun canonicalAccountPhone(value: String): String? {
    val clean = value.trim().replace(Regex("[\\s()-]"), "")
    val match = Regex("^(?:\\+251|251|0)?([79]\\d{8})$").matchEntire(clean) ?: return null
    return "+251" + match.groupValues[1]
}
