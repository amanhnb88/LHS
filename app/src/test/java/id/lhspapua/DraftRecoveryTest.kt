package id.lhspapua

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import id.lhspapua.domain.*
import id.lhspapua.ui.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DraftRecoveryTest {
    private lateinit var app:Application
    @Before fun setup() { app=ApplicationProvider.getApplicationContext();app.getSharedPreferences("draft-journal",0).edit().clear().commit();app.getSharedPreferences("navigation",0).edit().clear().commit() }
    private fun until(condition:()->Boolean) {
        val deadline=System.currentTimeMillis()+15000
        while(!condition()&&System.currentTimeMillis()<deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10) }
        assertTrue("Timed out waiting for durable state",condition())
    }
    @Test fun fastTypingCoalescesRoomWritesAndPersistsLastValue() {
        val vm=LhsViewModel(app);until { vm.state.value.ready }
        vm.newCall();until { vm.state.value.pending==0 }
        val before=vm.durableWrites
        repeat(100) { n->vm.call { it.copy(identity=it.identity.copy(route="Rute ${n+1}")) } }
        vm.navigate("home");until { vm.state.value.pending==0 }
        val id=vm.state.value.call!!.id
        assertEquals("Rute 100",runBlocking { vm.repository.call(id) }!!.identity.route)
        assertTrue("Rapid changes must not write 100 Room transactions",vm.durableWrites-before<20)
        vm.viewModelScope.cancel()
    }
    @Test fun journalRecoversLatestDraftWithoutMakingAnotherReport() {
        val old=LhsViewModel(app);until { old.state.value.ready }
        val draft=CallReport(identity=Identity(sales="Pemulihan"),outlets=listOf(Outlet(name="Toko Aimas",address="Aimas",quantities=List(3){Quantity(50,20)})))
        // Simulate a process stopping after the recovery journal is durable but before the Room write.
        app.getSharedPreferences("draft-journal",0).edit().putString("call:${draft.id}",old.repository.json.encodeToString(draft)).commit()
        app.getSharedPreferences("navigation",0).edit().putString("id",draft.id).putString("type","call").commit()
        old.viewModelScope.cancel()
        val navigation=SavedStateHandle(mapOf("root" to "calls","screen" to "call","callId" to draft.id))
        val reopened=LhsViewModel(app,navigation);until { reopened.state.value.ready }
        val restored=runBlocking { reopened.repository.call(draft.id) }
        assertEquals(draft,restored);assertEquals("call",reopened.state.value.screen);assertEquals("calls",reopened.state.value.root);assertEquals(draft.id,reopened.state.value.lastId)
        assertFalse(app.getSharedPreferences("draft-journal",0).contains("call:${draft.id}"));reopened.viewModelScope.cancel()
    }
    @Test fun switchingToAutomaticWithoutSourcePreservesManualOpening() {
        val vm=LhsViewModel(app);until { vm.state.value.ready }
        val r=StockFixture.report();vm.open(r);vm.day { it };until { vm.state.value.pending==0 }
        vm.automaticOpening();assertEquals(OpeningMode.MANUAL,vm.state.value.day!!.openingMode)
        assertEquals(50L,vm.state.value.day!!.products[0].opening);assertNotNull(vm.state.value.error)
        vm.viewModelScope.cancel()
    }
    @Test fun deleteDrainsAutosaveAndFinalReportsRemainProtected() {
        val vm=LhsViewModel(app);until { vm.state.value.ready }
        vm.newCall()
        repeat(25) { n->vm.call { it.copy(outlets=List(3) { Outlet(name="Outlet ${n+1}") }) } }
        val draft=vm.state.value.call!!
        vm.flushPending()
        vm.removeCall(draft,draftOnly=true)
        vm.call { it.copy(identity=it.identity.copy(sales="Late callback")) }
        until { vm.state.value.pending==0 }
        assertNull(runBlocking { vm.repository.call(draft.id) })
        assertNull(vm.state.value.call)
        vm.newDay()
        val day=vm.state.value.day!!
        val finalCall=CallReport(completed=true)
        val otherDay=newDaily(Identity(sales="Other"))
        val finalDay=newDaily(Identity(sales="Final")).copy(status=ReportStatus.FINAL)
        runBlocking { vm.repository.save(finalCall);vm.repository.save(otherDay);vm.repository.save(finalDay) }
        vm.removeDay(day,draftOnly=true)
        until { vm.state.value.pending==0 }
        vm.removeCall(finalCall.copy(completed=false),draftOnly=true)
        until { vm.state.value.pending==0 }
        vm.removeDay(finalDay.copy(status=ReportStatus.DRAFT),draftOnly=true)
        until { vm.state.value.pending==0 }
        vm.viewModelScope.cancel()
        val reopened=LhsViewModel(app);until { reopened.state.value.ready }
        runBlocking {
            assertNull(reopened.repository.call(draft.id))
            assertNull(reopened.repository.daily(day.id))
            assertEquals(otherDay,reopened.repository.daily(otherDay.id))
            assertTrue(reopened.repository.call(finalCall.id)!!.completed)
            assertEquals(ReportStatus.FINAL,reopened.repository.daily(finalDay.id)!!.status)
        }
        reopened.viewModelScope.cancel()
    }
    @Test fun recoveryCompletesJournaledDeletionBeforeReplayingDraftWrites() {
        val vm=LhsViewModel(app);until { vm.state.value.ready }
        val report=CallReport(identity=Identity(sales="Queued for removal"))
        runBlocking { vm.repository.save(report) }
        val key="call:${report.id}"
        val journal=app.getSharedPreferences("draft-journal",0)
        assertTrue(journal.edit().putString(key,vm.repository.json.encodeToString(report)).putBoolean("delete:$key",true).commit())
        vm.viewModelScope.cancel()
        val restarted=LhsViewModel(app);until { restarted.state.value.ready }
        assertNull(runBlocking { restarted.repository.call(report.id) })
        assertFalse(journal.contains(key))
        assertFalse(journal.contains("delete:$key"))
        restarted.viewModelScope.cancel()
    }

}
