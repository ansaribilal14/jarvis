package com.jarvis.mobile.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Structured in-memory agent log (observability requirement).
 * Shows actions / observations / results only - never hidden model reasoning.
 */
object Logx {
    data class Entry(val at: Long = System.currentTimeMillis(), val level: String, val tag: String, val msg: String) {
        fun render(): String {
            val t = timeFmt.format(Date(at))
            return "$t [$level] $tag: $msg"
        }
    }

    private const val MAX = 600
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val timeFmt: SimpleDateFormat get() = fmt

    private val buffer = ArrayDeque<Entry>(MAX)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> get() = _entries

    @Synchronized
    fun log(level: String, tag: String, msg: String) {
        val e = Entry(level = level, tag = tag, msg = msg.take(900))
        if (buffer.size >= MAX) buffer.removeFirst()
        buffer.addLast(e)
        _entries.value = buffer.toList()
        when (level) {
            "E" -> Log.e(tag, msg)
            "W" -> Log.w(tag, msg)
            else -> Log.i(tag, msg)
        }
    }

    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun e(tag: String, msg: String) = log("E", tag, msg)

    fun dump(): String = _entries.value.joinToString("\n") { it.render() }

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }
}
