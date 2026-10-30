package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageIconTest {
    @Test
    fun picksTheIconFromTheWording() {
        assertEquals(Sym.Delete, messageIcon("Moved 3 items to Trash"))
        assertEquals(Sym.RestoreFromTrash, messageIcon("Restored 3 items"))
        assertEquals(Sym.Lock, messageIcon("Moved 2 photos to the Private safe"))
        assertEquals(Sym.Info, messageIcon("Couldn't save. Try again."))
        assertEquals(Sym.CheckCircle, messageIcon("Kept 4 items"))
    }

    @Test
    fun saysMessageTimesInTenths() {
        assertEquals("0.3 s", secondsLabel(0.3f))
        assertEquals("4 s", secondsLabel(4f))
        assertEquals("2.5 s", secondsLabel(2.5f))
        assertEquals("10 s", secondsLabel(10f))
    }
}
