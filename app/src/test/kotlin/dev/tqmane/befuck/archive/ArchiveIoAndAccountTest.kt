package dev.tqmane.befuck.archive

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class ArchiveIoAndAccountTest {
    @Test fun boundedCopyRejectsLargeAndEmptyStreams() {
        var left=ArchiveStore.MAX_BYTES+1
        val input=object:InputStream() {
            override fun read():Int=if(left-- > 0) 1 else -1
            override fun read(b:ByteArray,off:Int,len:Int):Int { if(left<=0)return -1;val n=minOf(len.toLong(),left).toInt();left-=n;return n }
        }
        val sink=object:OutputStream() { override fun write(b:Int)=Unit;override fun write(b:ByteArray,off:Int,len:Int)=Unit }
        assertThrows(ArchiveFailure::class.java) { ArchiveBackup.copy(input,sink) }
        assertThrows(ArchiveFailure::class.java) { ArchiveBackup.copy(ByteArrayInputStream(byteArrayOf()),sink) }
    }
    @Test fun copyCancellationStopsBeforePublishingMoreBytes() {
        val output=ByteArrayOutputStream();var checks=0
        assertThrows(ArchiveFailure::class.java) { ArchiveBackup.copy(ByteArrayInputStream(ByteArray(100000)),output) { if(++checks==2) throw ArchiveFailure("cancelled") } }
        assertEquals(65536,output.size())
    }
    @Test fun staleAccountResolutionAndBackupLeaseCannotCrossAccountChange() {
        ArchiveAccounts.invalidate()
        val before=ArchiveAccounts.snapshot();ArchiveAccounts.accept(before.generation,"first")
        val lease=ArchiveAccounts.snapshot();ArchiveAccounts.requireScope(lease)
        ArchiveAccounts.invalidate();ArchiveAccounts.accept(lease.generation,"stale-first")
        assertNull(ArchiveAccounts.snapshot().uid)
        ArchiveAccounts.accept(ArchiveAccounts.snapshot().generation,"second")
        assertEquals("second",ArchiveAccounts.snapshot().uid)
        assertThrows(ArchiveFailure::class.java) { ArchiveAccounts.requireScope(lease) }
        ArchiveAccounts.invalidate()
    }
}
