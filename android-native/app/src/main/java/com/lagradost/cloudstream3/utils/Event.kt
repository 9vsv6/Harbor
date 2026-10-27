package com.lagradost.cloudstream3.utils

/** Cloudstream's tiny event: listeners added with +=, called with invoke. */
class Event<T> {
    private val observers = mutableSetOf<(T) -> Unit>()

    operator fun plusAssign(observer: (T) -> Unit) {
        synchronized(observers) { observers.add(observer) }
    }

    operator fun minusAssign(observer: (T) -> Unit) {
        synchronized(observers) { observers.remove(observer) }
    }

    operator fun invoke(value: T) {
        synchronized(observers) { observers.toList() }.forEach { runCatching { it(value) } }
    }
}
