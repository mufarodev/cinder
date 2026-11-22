package com.discord.loader.mana

import java.util.LinkedHashMap

class ManaState<T>(
    initialValue: T,
) {
    private val lock = Any()
    private var current: T = initialValue
    private val listeners = LinkedHashMap<Int, (T) -> Unit>()
    private var nextListenerId: Int = 1

    var value: T
        get() = synchronized(lock) { current }
        set(newValue) {
            val snapshot: List<(T) -> Unit>
            synchronized(lock) {
                if (current == newValue) return
                current = newValue
                snapshot = if (listeners.isEmpty()) emptyList() else ArrayList(listeners.values)
            }
            for (listener in snapshot) {
                listener(newValue)
            }
        }

    fun update(transform: (T) -> T) {
        value = transform(value)
    }

    fun observe(
        scope: ManaScope,
        emitInitial: Boolean = false,
        listener: (T) -> Unit,
    ): () -> Unit {
        val id: Int
        val initial: T
        synchronized(lock) {
            id = nextListenerId++
            listeners[id] = listener
            initial = current
        }
        val unsub: () -> Unit = {
            synchronized(lock) {
                listeners.remove(id)
            }
        }
        scope.addDisposer(unsub)
        if (emitInitial) {
            listener(initial)
        }
        return unsub
    }

    fun <R> map(
        scope: ManaScope,
        transform: (T) -> R,
    ): ManaState<R> {
        val derived = ManaState(transform(value))
        observe(scope) { next ->
            derived.value = transform(next)
        }
        return derived
    }

    fun listenerCount(): Int = synchronized(lock) { listeners.size }

    override fun toString(): String = "ManaState($value)"
}

fun <T> manaState(initial: T): ManaState<T> = ManaState(initial)

fun booleanState(initial: Boolean = false): ManaState<Boolean> = ManaState(initial)

fun stringState(initial: String = ""): ManaState<String> = ManaState(initial)

fun intState(initial: Int = 0): ManaState<Int> = ManaState(initial)

fun <T> listState(initial: List<T> = emptyList()): ManaState<List<T>> = ManaState(initial)

fun <A, B, R> combineState(
    scope: ManaScope,
    first: ManaState<A>,
    second: ManaState<B>,
    transform: (A, B) -> R,
): ManaState<R> {
    val derived = ManaState(transform(first.value, second.value))
    first.observe(scope) { a -> derived.value = transform(a, second.value) }
    second.observe(scope) { b -> derived.value = transform(first.value, b) }
    return derived
}
