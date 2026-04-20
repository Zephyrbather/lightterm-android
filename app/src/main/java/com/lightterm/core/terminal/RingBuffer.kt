package com.lightterm.core.terminal

class RingBuffer<T>(private val capacity: Int) {
    private val elements = ArrayDeque<T>(capacity)

    @Synchronized
    fun add(item: T) {
        if (elements.size == capacity) {
            elements.removeFirst()
        }
        elements.addLast(item)
    }

    @Synchronized
    fun clear() {
        elements.clear()
    }

    @Synchronized
    fun snapshot(): List<T> = elements.toList()
}

