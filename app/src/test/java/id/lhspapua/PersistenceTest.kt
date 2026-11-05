package id.lhspapua

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import id.lhspapua.data.*
import id.lhspapua.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PersistenceTest {
    @org.junit.Before fun clearProviderCacheBetweenRobolectricContexts() {
        val cache=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible=true
        (cache.get(null) as MutableMap<*,*>).clear()
    }
    @Test fun reportsSurviveDatabaseCloseAndRemainIndependent() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("persistence-test")
        fun db()=Room.databaseBuilder(context,LhsDatabase::class.java,"persistence-test").allowMainThreadQueries().build()
        val first=db(); val repo=Repository(context,first)
        val call=CallReport(outlets=List(30) { Outlet(name="Outlet ${it+1}",quantities=List(3) { Quantity(10,2) }) })
        val day=DailyReport(identity=call.identity,products=List(3) { Stock(delivery=100,sales=List(3) { Sale(2,10000) }) })
        repo.save(call); repo.save(day); repo.save(Settings(prices=List(3) { List(3) { 12345L } }))
        first.close()
        val second=db(); val reopened=Repository(context,second)
        assertEquals(call,reopened.call(call.id)); assertEquals(day,reopened.daily(day.id)); assertEquals(12345L,reopened.settings().prices[0][0])
        reopened.save(call.copy(outlets=call.outlets.drop(1)))
        assertEquals(29,reopened.call(call.id)!!.outlets.size); assertEquals(day,reopened.daily(day.id))
        reopened.deleteCall(call.id); assertNull(reopened.call(call.id)); assertEquals(day,reopened.daily(day.id))
        second.close(); context.deleteDatabase("persistence-test"); Unit
    }

    @Test fun backupRoundTripIncludesAttachmentsAndSettings() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,LhsDatabase::class.java).allowMainThreadQueries().build()
        val repo=Repository(context,db)
        val directory=java.io.File(context.cacheDir,"exports").apply { mkdirs() }
        val source=java.io.File(directory,"proof.jpg").apply { writeBytes(byteArrayOf(1,2,3,4)) }
        val sourceUri=androidx.core.content.FileProvider.getUriForFile(context,"id.lhspapua.files",source)
        val attachment=repo.importAttachment(sourceUri)
        val call=CallReport(outlets=listOf(Outlet(name="Backup Outlet")))
        val day=DailyReport(attachments=listOf(attachment))
        repo.save(call); repo.save(day); repo.save(Settings(profile=Identity(sales="Backup Sales")))
        val backup=java.io.File(directory,"backup.zip"); repo.backup(backup)
        repo.deleteCall(call.id); repo.deleteDaily(day.id)
        repo.restore(androidx.core.content.FileProvider.getUriForFile(context,"id.lhspapua.files",backup))
        assertEquals(call,repo.call(call.id)); assertEquals("Backup Sales",repo.settings().profile.sales)
        val restored=repo.daily(day.id)!!; assertEquals(1,restored.attachments.size)
        assertArrayEquals(byteArrayOf(1,2,3,4),repo.attachment(restored.attachments.first()).readBytes())
        assertNotEquals(attachment.file,restored.attachments.first().file)
        db.close(); Unit
    }
    @Test fun maliciousBackupDoesNotWriteOutsideStaging() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,LhsDatabase::class.java).allowMainThreadQueries().build()
        val repo=Repository(context,db)
        val directory=java.io.File(context.cacheDir,"exports").apply { mkdirs() }
        val zip=java.io.File(directory,"bad.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out -> out.putNextEntry(java.util.zip.ZipEntry("../escape.txt")); out.write("bad".toByteArray()) }
        var rejected=false
        try { repo.restore(androidx.core.content.FileProvider.getUriForFile(context,"id.lhspapua.files",zip)) } catch(e:IllegalArgumentException) { rejected=true }
        assertTrue(rejected); assertFalse(java.io.File(context.cacheDir,"escape.txt").exists()); db.close(); Unit
    }
}
