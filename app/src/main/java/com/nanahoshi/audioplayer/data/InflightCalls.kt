package com.nanahoshi.audioplayer.data

import okhttp3.Call

class InflightCalls {
    class Ticket internal constructor(val key: String, val token: Int)

    private class Slot {
        var token: Int = 0
        var call: Call? = null
    }

    private val slots = HashMap<String, Slot>()

    fun prepare(key: String): Ticket {
        val (ticket, previous) = synchronized(slots) {
            val slot = slots.getOrPut(key) { Slot() }
            slot.token += 1
            val current = slot.call
            slot.call = null
            Ticket(key, slot.token) to current
        }
        previous?.cancel()
        return ticket
    }

    fun begin(ticket: Ticket, call: Call): Boolean {
        val accepted = synchronized(slots) {
            val slot = slots[ticket.key]
            if (slot == null || slot.token != ticket.token) {
                false
            } else {
                slot.call = call
                true
            }
        }
        if (!accepted) call.cancel()
        return accepted
    }

    fun end(ticket: Ticket, call: Call) {
        synchronized(slots) {
            val slot = slots[ticket.key] ?: return
            if (slot.token == ticket.token && slot.call === call) slot.call = null
        }
    }

    fun cancel(key: String) {
        val doomed = synchronized(slots) {
            val slot = slots.getOrPut(key) { Slot() }
            slot.token += 1
            val current = slot.call
            slot.call = null
            current
        }
        doomed?.cancel()
    }
}
