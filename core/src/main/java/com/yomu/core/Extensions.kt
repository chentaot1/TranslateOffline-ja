package com.yomu.core

import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

fun <T> Flow<T>.handleErrors(onError: (Throwable) -> Unit): Flow<T> {
    return this.catch { e ->
        onError(e)
    }
}

fun Long.toFileSizeString(): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    
    return when {
        this >= gb -> String.format(Locale.US, "%.2f GB", this / gb)
        this >= mb -> String.format(Locale.US, "%.2f MB", this / mb)
        this >= kb -> String.format(Locale.US, "%.2f KB", this / kb)
        else -> "$this B"
    }
}
