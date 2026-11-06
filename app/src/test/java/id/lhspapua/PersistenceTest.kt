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
        val draft=newDaily(Identity())
        val day=draft.copy(
            products=draft.products.mapIndexed { i,stock -> if(i==0) stock.copy(manualSold=100,manualTg=25) else stock },
            testers=listOf(Tester(),Tester(),Tester(delivery=10,sample=3,manualTg=5)),
            finance=Finance(manualBalance=12345),
            attachments=listOf(attachment)
        )
        repo.save(call); repo.save(day); repo.save(Settings(profile=Identity(sales="Backup Sales")))
        val backup=java.io.File(directory,"backup.zip"); repo.backup(backup)
        repo.deleteCall(call.id); repo.deleteDaily(day.id)
        repo.restore(androidx.core.content.FileProvider.getUriForFile(context,"id.lhspapua.files",backup))
        assertEquals(call,repo.call(call.id)); assertEquals("Backup Sales",repo.settings().profile.sales)
        val restored=repo.daily(day.id)!!; assertEquals(1,restored.attachments.size)
        assertArrayEquals(byteArrayOf(1,2,3,4),repo.attachment(restored.attachments.first()).readBytes())
        assertNotEquals(attachment.file,restored.attachments.first().file)
        assertEquals(day.copy(attachments=restored.attachments),restored)
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
            is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(e.filterKeys { it !in setOf("stockOwnerId","opening","openingTracked","manualBalance","automaticFinance","manualSold") }.mapValues { oldPayload(it.value) })
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

    @Test fun timestampsTrackDurableChangesAndActualFinalizationOnly() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,LhsDatabase::class.java).build()
        try {
            val created="2026-11-07T04:00:00Z"
            val edited="2026-11-07T05:35:00Z"
            val final="2026-11-07T09:45:00Z"
            val again="2026-11-07T10:00:00Z"
            fun repo(at:String)=Repository(context,db,java.time.Clock.fixed(java.time.Instant.parse(at),java.time.ZoneOffset.UTC))
            val call=CallReport(createdAt=created)
            val day=newDaily(Identity()).copy(createdAt=created)
            val legacy=CallReport(completed=true)
            repo(created).apply { save(call);save(day);save(legacy) }
            assertEquals(created,repo(created).call(call.id)!!.updatedAt)
            assertEquals(created,repo(created).daily(day.id)!!.updatedAt)
            assertNull(repo(created).call(legacy.id)!!.completedAt)
            repo(edited).save(call.copy(outlets=listOf(Outlet(name="TOKO"))))
            repo(edited).save(day.copy(finance=Finance(notes="EDIT")))
            val editedCall=repo(final).call(call.id)!!
            val editedDay=repo(final).daily(day.id)!!
            assertEquals(created,editedCall.createdAt)
            assertEquals(created,editedDay.createdAt)
            assertEquals(edited,editedCall.updatedAt)
            assertEquals(edited,editedDay.updatedAt)
            repo(final).apply { save(editedCall);save(editedDay);completeCall(call.id);finalizeDay(day.id) }
            val finishedCall=repo(again).call(call.id)!!
            val finishedDay=repo(again).daily(day.id)!!
            assertEquals(edited,finishedCall.updatedAt)
            assertEquals(edited,finishedDay.updatedAt)
            assertEquals(final,finishedCall.completedAt)
            assertEquals(final,finishedDay.finalizedAt)
            repo(again).apply {
                save(finishedCall.copy(completed=false))
                reopen(day.id)
                assertNull(this.call(call.id)!!.completedAt)
                assertNull(daily(day.id)!!.finalizedAt)
                assertEquals(again,completeCall(call.id).completedAt)
                assertEquals(again,finalizeDay(day.id).reports.single().finalizedAt)
                assertNull(this.call(legacy.id)!!.createdAt)
                assertNull(this.call(legacy.id)!!.updatedAt)
            }
        } finally { db.close() }
        Unit
    }

    @Test fun linksAreTransactionalAndRecheckedAtFinalization() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,LhsDatabase::class.java).build()
        try {
            val repo=Repository(context,db)
            val identity=Identity(date="2026-11-07",sales="MAIKEL",area="SORONG")
            val source=CallReport(identity=identity,completed=true,outlets=listOf(
                Outlet(quantities=listOf(Quantity(sold=0),Quantity(sold=650),Quantity(sold=0)))
            ))
            val base=newDaily(identity)
            val draft=base.copy(products=base.products.mapIndexed { p,stock ->
                val g=if(p==1)200L else 0L
                val rtl=if(p==1)450L else 0L
                stock.copy(sales=listOf(stock.sales[0].copy(quantity=g),stock.sales[1],stock.sales[2].copy(quantity=rtl)))
            })
            val other=draft.copy(id=java.util.UUID.randomUUID().toString())
            repo.save(source)
            repo.save(draft)
            repo.save(other)
            val hash=SalesReconciliation.fingerprint(source)
            assertEquals(IntegrationMode.MANDIRI,repo.settings().integrationMode)
            assertTrue(runCatching { repo.changeCallsheetLink(draft.id,source.id,hash) }.isFailure)
            repo.save(Settings(integrationMode=IntegrationMode.TERINTEGRASI))
            assertTrue(runCatching { repo.changeCallsheetLink(draft.id,source.id,"stale") }.isFailure)
            val linked=repo.changeCallsheetLink(draft.id,source.id,hash)
            assertEquals(draft.products,linked.products)
            assertTrue(runCatching { repo.changeCallsheetLink(other.id,source.id,hash) }.isFailure)
            repo.save(Settings())
            assertEquals(linked.callsheetLink,repo.daily(draft.id)!!.callsheetLink)
            val mismatch=draft.copy(products=draft.products.mapIndexed { p,stock ->
                if(p==1)stock.copy(sales=stock.sales.mapIndexed { tier,sale -> if(tier==2)sale.copy(quantity=400) else sale }) else stock
            })
            repo.save(mismatch)
            assertEquals(linked.callsheetLink,repo.daily(draft.id)!!.callsheetLink)
            assertTrue(runCatching { repo.finalizeDay(draft.id) }.isFailure)
            assertTrue(runCatching { repo.save(mismatch.copy(status=ReportStatus.FINAL)) }.isFailure)
            repo.save(draft)
            repo.save(source.copy(completed=false))
            assertTrue(runCatching { repo.finalizeDay(draft.id) }.isFailure)
            val changed=repo.save(source.copy(outlets=source.outlets.map { outlet ->
                outlet.copy(quantities=listOf(Quantity(sold=0),Quantity(sold=700),Quantity(sold=0)))
            }))
            assertTrue(runCatching { repo.finalizeDay(draft.id) }.exceptionOrNull()!!.message!!.contains("konfirmasi ulang",true))
            repo.changeCallsheetLink(draft.id,source.id,SalesReconciliation.fingerprint(changed))
            assertTrue(runCatching { repo.finalizeDay(draft.id) }.isFailure)
            repo.save(source)
            val restored=repo.call(source.id)!!
            repo.changeCallsheetLink(draft.id,source.id,SalesReconciliation.fingerprint(restored))
            val final=repo.finalizeDay(draft.id).reports.single()
            repo.deleteCall(source.id)
            assertEquals(final,repo.daily(draft.id))
            assertTrue(runCatching { repo.changeCallsheetLink(draft.id,null,null) }.isFailure)
            repo.reopen(draft.id)
            val unlinked=repo.changeCallsheetLink(draft.id,null,null)
            assertNull(unlinked.callsheetLink)
            assertEquals(draft.products,unlinked.products)
            assertEquals(ReportStatus.FINAL,repo.finalizeDay(draft.id).reports.single().status)
        } finally { db.close() }
        Unit
    }

    @Test fun featureSettingsAndLinksRoundTripAndLegacySettingsDefaultOff() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="stage39-settings"
        context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,LhsDatabase::class.java,name).build()
        val identity=Identity(date="2026-11-07",sales="MAIKEL")
        val source=CallReport(identity=identity,completed=true)
        val draft=newDaily(identity)
        val historical=newDaily(identity).copy(status=ReportStatus.FINAL,finance=Finance(madeBy="PEMBUAT LAMA"))
        val settings=Settings(lhsFeatures=LhsFeatureSettings(true,false,true),integrationMode=IntegrationMode.TERINTEGRASI)
        val dir=java.io.File(context.cacheDir,"exports").apply { mkdirs() }
        val file=java.io.File(dir,"stage39-backup.zip")
        val initialDb=open()
        try {
            val repo=Repository(context,initialDb)
            initialDb.dao().save(SettingsEntity(payload="{}"))
            assertEquals(LhsFeatureSettings(),repo.settings().lhsFeatures)
            assertEquals(IntegrationMode.MANDIRI,repo.settings().integrationMode)
            repo.save(settings)
            repo.save(source)
            repo.save(draft)
            repo.save(historical)
            repo.changeCallsheetLink(draft.id,source.id,SalesReconciliation.fingerprint(source))
            repo.backup(file)
        } finally { initialDb.close() }
        val reopenedDb=open()
        try {
            val repo=Repository(context,reopenedDb)
            assertEquals(settings,repo.settings())
            assertEquals("PEMBUAT LAMA",repo.daily(historical.id)!!.finance.madeBy)
            val before=repo.daily(draft.id)!!.callsheetLink
            repo.deleteDaily(draft.id)
            repo.deleteCall(source.id)
            repo.save(Settings())
            val uri=androidx.core.content.FileProvider.getUriForFile(context,"id.lhspapua.files",file)
            repo.restore(uri)
            assertEquals(settings,repo.settings())
            assertEquals(before,repo.daily(draft.id)!!.callsheetLink)
            assertEquals(source,repo.call(source.id))
            assertEquals(historical,repo.daily(historical.id))
        } finally { reopenedDb.close() }
        context.deleteDatabase(name)
        Unit
    }

}
