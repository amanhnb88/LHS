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
    @Test fun stageTwoCarryForwardSurvivesReopenAndLegacyPayloadStaysIntact() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("upgrade-test")
        fun open()=Room.databaseBuilder(context,LhsDatabase::class.java,"upgrade-test").allowMainThreadQueries().build()
        var db=open();var repo=Repository(context,db)
        // Actual v1-style JSON omits every added Stage 2 field; SQL schema and identity stay unchanged.
        val legacy=DailyReport(identity=Identity(sales="Legacy"),products=List(3){Stock(100,2,1,sales=listOf(Sale(10,12345),Sale(0,null),Sale(0,null)))},rules=Rules(acknowledged=true))
        val payload=repo.json.encodeToString(DailyReport.serializer(),legacy)
        val obj=kotlinx.serialization.json.Json.parseToJsonElement(payload) as kotlinx.serialization.json.JsonObject
        val oldKeys=setOf("id","identity","products","testers","finance","rules","attachments")
        fun oldPayload(e:kotlinx.serialization.json.JsonElement):kotlinx.serialization.json.JsonElement = when(e) {
            is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(e.filterKeys { it !in setOf("stockOwnerId","opening","openingTracked","manualBalance","automaticFinance") }.mapValues { oldPayload(it.value) })
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map(::oldPayload))
            else -> e
        }
        val raw=oldPayload(kotlinx.serialization.json.JsonObject(obj.filterKeys { it in oldKeys })).toString()
        db.dao().save(DailyEntity(legacy.id,legacy.identity.date,legacy.identity.sales,raw))
        assertEquals(legacy,repo.daily(legacy.id));assertEquals(87L,Calculation.tg(repo.daily(legacy.id)!!.products[0],legacy.rules))
        val first=StockFixture.report();repo.save(first);repo.finalizeDay(first.id)
        val next=StockFixture.auto("2026-11-03");repo.save(next)
        assertEquals(85L,repo.daily(next.id)!!.products[1].opening)
        db.close();db=open();repo=Repository(context,db)
        val restored=repo.daily(next.id)!!;assertEquals(60L,Calculation.tg(restored.products[1],restored.rules));assertEquals(11L,Calculation.tg(restored.testers[1],restored.rules))
        assertEquals(legacy,repo.daily(legacy.id));repo.finalizeDay(next.id)
        repo.reopen(first.id);val frozen=repo.daily(next.id)!!;assertTrue(frozen.stockStale);assertEquals(85L,frozen.products[1].opening)
        assertEquals(legacy.id,repo.daily(legacy.id)!!.id)
        db.close();context.deleteDatabase("upgrade-test");Unit
    }

    @Test fun backupV2PreservesLedgerAndV1StillImports() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val db=Room.inMemoryDatabaseBuilder(context,LhsDatabase::class.java).allowMainThreadQueries().build();val repo=Repository(context,db)
        val first=StockFixture.report();repo.save(first);repo.finalizeDay(first.id)
        val next=StockFixture.auto("2026-11-03");repo.save(next);repo.finalizeDay(next.id)
        val before=repo.daily(next.id)!!
        val dir=java.io.File(context.cacheDir,"exports").apply { mkdirs() };val zip=java.io.File(dir,"ledger.zip");repo.backup(zip)
        repo.deleteDaily(next.id);repo.deleteDaily(first.id)
        repo.restore(androidx.core.content.FileProvider.getUriForFile(context,"id.lhspapua.files",zip))
        assertEquals(before,repo.daily(next.id));assertEquals(60L,Calculation.tg(repo.daily(next.id)!!.products[1],before.rules))
        val old=DailyReport(identity=Identity(sales="Backup Lama"))
        val metadata=repo.json.encodeToString(Backup.serializer(),Backup(version=1,calls=emptyList(),daily=listOf(old),settings=Settings()))
        val oldZip=java.io.File(dir,"old.zip");java.util.zip.ZipOutputStream(oldZip.outputStream()).use { out->out.putNextEntry(java.util.zip.ZipEntry("reports.json"));out.write(metadata.toByteArray());out.closeEntry() }
        repo.restore(androidx.core.content.FileProvider.getUriForFile(context,"id.lhspapua.files",oldZip))
        assertEquals(old,repo.daily(old.id));assertEquals(before,repo.daily(next.id));db.close();Unit
    }

}
