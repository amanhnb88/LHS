@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package id.lhspapua

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import id.lhspapua.ui.*
import id.lhspapua.domain.*
import id.lhspapua.export.ExportFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import androidx.room.Room
import id.lhspapua.data.LhsDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="en-rUS-w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiSmokeTest {
    private val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:LhsViewModel
    private lateinit var database:LhsDatabase
    private val databaseInstance get()=LhsDatabase::class.java.getDeclaredField("instance").apply { isAccessible=true }

    @get:Rule val testRules:RuleChain=RuleChain.outerRule(object:ExternalResource() {
        override fun before() {
            val app=ApplicationProvider.getApplicationContext<Application>()
            database=Room.inMemoryDatabaseBuilder(app,LhsDatabase::class.java).build()
            databaseInstance.set(null,database)
            app.getSharedPreferences("navigation",0).edit().clear().commit()
            app.getSharedPreferences("draft-journal",0).edit().clear().commit()
        }

        override fun after() {
            databaseInstance.set(null,null)
        }
    }).around(compose)

    // StateFlow reads alone do not drain Robolectric's paused Android main queue.
    // runOnIdle synchronizes both Compose and Android callbacks before checking IO completion.
    private fun awaitState(description:String,condition:()->Boolean) {
        try { compose.waitUntil(20000) { compose.runOnIdle { condition() } } }
        catch(e:ComposeTimeoutException) {
            val s=vm.state.value
            throw AssertionError("$description: ready=${s.ready}, pending=${s.pending}, busy=${s.busy}, saved=${s.saved}, error=${s.error}",e)
        }
    }
    @Before fun isolate() {
        vm=compose.runOnIdle { ViewModelProvider(compose.activity)[LhsViewModel::class.java] }
        awaitState("Application initialization") { vm.state.value.ready }
        compose.runOnIdle { vm.navigate("home") }
    }
    @After fun releaseScenario() {
        if(::vm.isInitialized) {
            val job=compose.runOnIdle {
                compose.activity.setContent {}
                checkNotNull(vm.viewModelScope.coroutineContext[Job]).also { it.cancel() }
            }
            compose.waitUntil(15000) { compose.runOnIdle { job.isCompleted } }
        }
        database.close()
    }
    private fun click(text:String) { val node=compose.onNode(hasText(text) and hasClickAction());runCatching { node.performScrollTo() };node.performClick() }
    private fun text(tag:String,value:String) { compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value) }
    private fun saved()=awaitState("Durable save") { vm.state.value.pending==0&&!vm.state.value.busy }
    private fun snapshot(name:String) {
        compose.runOnIdle { val view=compose.activity.window.decorView;val b=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(b));val f=java.io.File("build/verification/$name.png");f.parentFile!!.mkdirs();f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    }
    private fun newCall() { compose.onNodeWithTag("tab-calls").performClick();click("Buat Laporan Baru");if(vm.state.value.call!!.identity.sales.isNotBlank())click("Ubah tanggal / identitas");text("Nama Sales","Sales Lapangan");click("Gunakan Identitas");saved() }
    @Test fun roleInputLocksAllProductsAndRejectsDirectCallbacks() {
        for(mode in listOf(IntegrationMode.SALES,IntegrationMode.LEADER)) {
            compose.runOnIdle { vm.changeIntegrationMode(mode) };saved()
            compose.runOnIdle { vm.newDay() };saved()
            val id=vm.state.value.day!!.id
            assertEquals(mode,vm.state.value.day!!.reportMode)
            val active=if(mode==IntegrationMode.SALES)Tier.RTL else Tier.G
            val locked=if(active==Tier.G)Tier.RTL else Tier.G
            for(p in Product.entries.indices) {
                compose.onNodeWithTag("daily-product-$p").performClick()
                compose.onNodeWithTag("sale-$p-${locked.label}").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithTag("sale-$p-${active.label}").performScrollTo().assertIsEnabled()
                text("sale-$p-${active.label}","500")
                assertEquals(500L,Calculation.stockSold(vm.state.value.day!!,p))
                assertNull(vm.state.value.day!!.products[p].sales[locked.ordinal].quantity)
            }
            saved()
            val before=vm.state.value.day!!
            compose.runOnIdle { vm.day { report -> report.copy(products=report.products.map { stock -> stock.copy(sales=stock.sales.mapIndexed { t,s -> if(t==locked.ordinal)s.copy(quantity=999) else s }) }) } }
            assertEquals(before,vm.state.value.day)
            assertTrue(vm.state.value.error!!.contains("dikunci"))
            compose.runOnIdle { vm.error(null) }
            saved()
            assertEquals(before.products,runBlocking { vm.repository.daily(id) }!!.products)
            compose.onNodeWithTag("daily-product-1").performClick()
            snapshot("STAGE40_${mode.name}_EDITOR")
        }
    }

    @Test fun modeSettingsConfirmPopulatedDraftAndNeverEraseConflictingAmounts() {
        val report=newDaily(Identity(sales="MAIKEL"))
        runBlocking { vm.repository.save(report) }
        compose.runOnIdle { vm.open(report) }
        text("sale-0-G","200")
        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithContentDescription("Pengaturan").performClick()
        click("Integrasi Laporan")
        click("Terintegrasi Sales")
        compose.onNodeWithText("Ubah Mode Laporan?").assertExists()
        click("Batal")
        assertEquals(IntegrationMode.MANDIRI,vm.state.value.settings.integrationMode)
        assertEquals(200L,vm.state.value.day!!.products[0].sales[0].quantity)
        click("Terintegrasi Sales")
        click("Hanya Laporan Baru");saved()
        assertEquals(IntegrationMode.SALES,vm.state.value.settings.integrationMode)
        assertEquals(IntegrationMode.MANDIRI,vm.state.value.day!!.reportMode)
        compose.runOnIdle { vm.open(vm.state.value.day!!) }
        text("sale-0-G","");text("sale-0-RTL","500")
        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithContentDescription("Pengaturan").performClick()
        click("Integrasi Laporan")
        click("Terapkan ke Draft Aktif")
        click("Terapkan ke Draft");saved()
        assertEquals(IntegrationMode.SALES,vm.state.value.day!!.reportMode)
        assertEquals(500L,vm.state.value.day!!.products[0].sales[2].quantity)
        assertFalse(vm.state.value.saveFailed)
        snapshot("STAGE40_MODE_SETTINGS")
    }
    @Test fun masterOutletIsFoundDirectlyAndDraftResumesWithoutDuplicates() {
        vm.settings { it.copy(outlets=listOf(SavedOutlet("Toko Aimas","Jalan Aimas"))) };saved()
        newCall();val id=vm.state.value.call!!.id
        awaitState("Callsheet appears in persisted history") { vm.state.value.calls.any { it.id==id } }
        val count=vm.state.value.calls.size
        text("outlet-search","aimas");click("Toko Aimas")
        compose.onNodeWithTag("Alamat").assertTextContains("JALAN AIMAS")
        assertTrue(vm.state.value.call!!.outlets.last().quantities.all { it.stock==null&&it.sold==null })
        text("call-1-sold","200")
        snapshot("STAGE3_CALLSHEET");click("Simpan & Tambah Outlet");click("Daftar Kunjungan")
        // Finish the list recomposition before sending the real Android Back event.
        // Otherwise Back can still invoke the previous outlet editor callback.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitState("Android Back returns to Callsheet hub") { vm.state.value.screen=="calls" }
        compose.onNodeWithTag("tab-calls").assertIsSelected()
        saved();assertEquals(count,vm.state.value.calls.size)
        val beforeOpen=runBlocking { vm.repository.call(id) }!!.updatedAt
        assertNotNull(beforeOpen)
        compose.onNodeWithTag("tab-drafts").performClick()
        click("Lanjutkan");assertEquals(id,vm.state.value.call!!.id)
        assertEquals(beforeOpen,runBlocking { vm.repository.call(id) }!!.updatedAt)
        compose.onNodeWithText("1. TOKO AIMAS").performScrollTo().assertExists()
        val restored = vm.state.value.call!!.activeOutlets().single().quantities
        assertEquals(Quantity(sold=200), restored[1])
        assertEquals(Quantity(), restored[0])
        saved()
        assertFalse("A persisted draft must not show a save error after navigating",vm.state.value.saveFailed)
        compose.onNodeWithText("Tersimpan").assertExists()
        snapshot("STAGE3_VISITS")
    }
    @Test fun salesOnlyEditorTotalsThreeVisitsAndPreservesHistoricalStock() {
        newCall()
        click("Tambah Outlet")
        for((index,quantity) in listOf(100,200,50).withIndex()) {
            text("Nama Outlet","Outlet Uji ${index+1}")
            text("Alamat","Jayapura")
            for(product in Product.entries.indices) {
                compose.onNodeWithTag("call-$product-stock").assertDoesNotExist()
                compose.onNodeWithTag("call-$product-sold").assertExists()
            }
            text("call-1-sold",quantity.toString())
            click("Simpan & Tambah Outlet")
        }
        click("Daftar Kunjungan")
        val report=vm.state.value.call!!
        assertEquals(350L,Calculation.calls(report,1,false))
        assertTrue(report.activeOutlets().all { it.quantities.all { quantity->quantity.stock==null } })

        val historical=report.activeOutlets().first()
        compose.runOnIdle {
            vm.call { current ->
                current.copy(outlets=current.outlets.map { outlet ->
                    if(outlet.id==historical.id) outlet.copy(quantities=outlet.quantities.replace(1,Quantity(stock=10,sold=100)))
                    else outlet
                })
            }
        }
        saved()
        text("outlet-search","Outlet Uji 1")
        click("Edit Kunjungan")
        text("call-1-sold","125")
        click("Simpan Perubahan")
        saved()
        val reopened=runBlocking { requireNotNull(vm.repository.call(report.id)) }
        assertEquals(Quantity(stock=10,sold=125),reopened.activeOutlets().first().quantities[1])
        text("outlet-search","")
        click("Periksa Laporan")
        compose.onNodeWithTag("call-sold-1").assertTextEquals("375 bungkus")
        compose.onNodeWithTag("call-total-sold").assertTextEquals("375 BUNGKUS")
        compose.onNodeWithText("Stok",substring=true).assertDoesNotExist()
        click("Edit Callsheet")
        text("outlet-search","Outlet Uji 3")
        click("Hapus")
        click("Batal")
        compose.onNodeWithText("3. OUTLET UJI 3").assertExists()
        click("Hapus")
        compose.onNode(hasText("Hapus") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        saved()
        assertEquals(2,vm.state.value.call!!.activeOutlets().size)
    }
    @Test fun callsheetReviewCombinesProductsAndDistinguishesBlankFromZero() {
        fun review(vararg quantities:List<Quantity>) {
            val report=CallReport(
                completed=true,
                outlets=quantities.mapIndexed { index,row -> Outlet(name="TOKO ${index+1}",quantities=row) }
            )
            compose.runOnIdle { vm.open(report) }
            compose.onNodeWithText("Periksa Laporan").assertExists()
            compose.onNodeWithText("Stok",substring=true).assertDoesNotExist()
        }
        review(
            listOf(Quantity(stock=25,sold=200),Quantity(sold=350),Quantity(sold=100)),
            listOf(Quantity(sold=300),Quantity(stock=12,sold=300),Quantity(sold=200))
        )
        listOf("500 bungkus","650 bungkus","300 bungkus").forEachIndexed { index,value ->
            compose.onNodeWithTag("call-sold-$index").assertTextEquals(value)
        }
        compose.onNodeWithTag("call-total-sold").assertTextEquals("1.450 BUNGKUS")
        compose.onNodeWithText("JPG").performScrollTo().assertIsSelected()
        snapshot("STAGE38_CALL_REVIEW")
        click("PDF")
        compose.onNodeWithText("PDF").assertIsSelected()
        val previous=vm.state.value.call!!
        compose.runOnIdle { vm.navigate("history") }
        compose.onNodeWithTag("tab-history").assertIsSelected()
        compose.runOnIdle { vm.open(previous) }
        compose.onNodeWithText("JPG").assertIsSelected()

        review(listOf(Quantity(stock=25),Quantity(),Quantity()))
        compose.onNodeWithText("JPG").assertIsSelected()
        repeat(3) { compose.onNodeWithTag("call-sold-$it").assertTextEquals("—") }
        compose.onNodeWithTag("call-total-sold").assertTextEquals("—")
        review(listOf(Quantity(sold=0),Quantity(),Quantity()))
        compose.onNodeWithTag("call-sold-0").assertTextEquals("0 bungkus")
        compose.onNodeWithTag("call-sold-1").assertTextEquals("—")
        compose.onNodeWithTag("call-total-sold").assertTextEquals("0 BUNGKUS")
    }
    @Test @Config(sdk=[34],qualifiers="en-rUS-w360dp-h640dp-night")
    fun dailyTabsUseMatchingRenderedBoundsOnSmallScreenWithLargeFonts() {
        compose.runOnIdle {
            compose.activity.setContent {
                val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)) { LhsApp(vm) }
            }
            vm.newDay()
        }
        saved()
        for(group in listOf("daily-section","daily-product")) {
            val bounds=(0..2).map { compose.onNodeWithTag("$group-$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
            assertTrue(bounds.maxOf { it.width } - bounds.minOf { it.width } <= 1f)
            assertTrue(bounds.all { it.height==bounds.first().height && it.top==bounds.first().top })
            assertEquals(bounds[1].left-bounds[0].right,bounds[2].left-bounds[1].right,1f)
            compose.onNodeWithTag("$group-0").assertIsSelected()
        }
        listOf("Stok & Penjualan","Tester","Keuangan","MARTIL 12","MARTIL","HD LIGHT").forEach { label ->
            compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed()
        }
        snapshot("STAGE36_DAILY_SMALL_DARK")
    }
    @Test fun automaticSoldAndManualStockInputsExportAsDraft() {
        compose.runOnIdle { vm.settings { it.copy(lhsFeatures=LhsFeatureSettings(true,true,true)) } }
        saved()
        compose.onNodeWithTag("tab-days").performClick();click("Buat Laporan Baru");saved()
        val quantities = listOf(10 to 20,20 to 50,10 to 10)
        quantities.forEachIndexed { p,(gross,retail) ->
            if(p>0) click(Product.entries[p].label)
            compose.onNodeWithTag("stock-$p-opening").assertDoesNotExist()
            compose.onNodeWithTag("sale-$p-SG").assertDoesNotExist()
            compose.onNodeWithTag("stock-$p-sold").assertDoesNotExist()
            if(p==0) {
                text("stock-0-sample","5")
                text("stock-0-tg","25")
            }
            text("sale-$p-G",gross.toString())
            text("sale-$p-RTL",retail.toString())
            compose.onNodeWithTag("stock-$p-sold-auto").assertTextContains("${gross+retail} BUNGKUS")
            if(p==0)snapshot("STAGE36_DAILY_LIGHT")
        }
        assertEquals(70L,Calculation.stockSold(vm.state.value.day!!,1))
        assertEquals(25L,vm.state.value.day!!.products[0].manualTg)
        assertNull(vm.state.value.day!!.products[0].delivery)
        click("Tester")
        text("tester-2-do","10")
        text("tester-2-sample","3")
        text("tester-2-tg","5")
        snapshot("STAGE32_TESTER")
        click("Keuangan")
        text("Saldo Sebelum","50000")
        text("Saldo Akhir","12345")
        compose.onNodeWithText("Atur Keuangan").assertDoesNotExist()
        snapshot("STAGE32_FINANCE")
        click("Periksa Laporan")
        compose.onNodeWithText("Total Penjualan: Rp 1.860.000").performScrollTo().assertExists()
        compose.onNodeWithText("Saldo Akhir: Rp 12.345").assertExists()
        compose.onNodeWithText("Simpan JPG").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Bagikan JPG").assertIsEnabled()
        assertNull(vm.state.value.preview)
        click("PDF")
        compose.onNodeWithText("Simpan PDF").performScrollTo().assertIsEnabled()
        click("Excel")
        compose.onNodeWithText("Simpan Excel").performScrollTo().assertIsEnabled()
        saved()
        val reportId=vm.state.value.day!!.id
        val beforeExport=runBlocking { vm.repository.daily(reportId) }!!.updatedAt
        compose.runOnIdle { vm.export() };saved()
        compose.runOnIdle { vm.export(ExportFormat.EXCEL) };saved()
        assertEquals(beforeExport,runBlocking { vm.repository.daily(reportId) }!!.updatedAt)
        assertTrue((vm.state.value.pdf?.length() ?: 0L) > 0L)
        assertTrue((vm.state.value.xlsx?.length() ?: 0L) > 0L)
        click("Selesaikan Laporan");saved()
        assertEquals(ReportStatus.FINAL,vm.state.value.day!!.status)
        assertEquals(5L,vm.state.value.day!!.testers[2].manualTg)
    }
    @Test fun imeNextDoneAndRupiahPasteRetainCursorAndFinalValue() {
        newCall();click("Tambah Outlet");text("Nama Outlet","Toko Fokus");text("call-0-sold","10")
        compose.onNodeWithTag("call-0-sold").performImeAction()
        compose.onNodeWithTag("call-1-sold").assertIsFocused()
        text("call-2-sold","0");compose.onNodeWithTag("call-2-sold").performImeAction();compose.onNodeWithTag("call-2-sold").assertIsNotFocused()
        compose.runOnIdle { vm.newDay() };saved()
        click("Keuangan")
        compose.runOnIdle { vm.settings { it.copy(lhsFeatures=LhsFeatureSettings(financeEnabled=true)) } }
        val balance = compose.onNodeWithTag("Saldo Sebelum").performScrollTo()
        balance.performTextReplacement("Rp 14.500")
        saved();assertEquals(14500L,vm.state.value.day!!.finance.previous)
        balance.performTextInputSelection(androidx.compose.ui.text.TextRange(1))
        balance.performTextInput("9")
        saved();assertEquals(194500L,vm.state.value.day!!.finance.previous)
    }
    @Test @Config(sdk=[34],qualifiers="en-rUS-w360dp-h640dp") fun smallScreenLargeFontAndSyntheticKeyboardUseInsetsOnce() {
        compose.runOnIdle { compose.activity.setContent { val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)) { LhsApp(vm) } } }
        val orderedTabs=listOf("home","calls","days","drafts","history")
        val tabs=orderedTabs.map { compose.onNodeWithTag("tab-$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        assertTrue(tabs.zipWithNext().all { (left,right)->left.right<=right.left })
        assertTrue(tabs.maxOf { it.width }-tabs.minOf { it.width }<=1f)
        newCall();click("Tambah Outlet");text("Nama Outlet","Toko Layar Kecil");text("call-0-sold","250")
        compose.runOnIdle {
            val insets=androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),androidx.core.graphics.Insets.of(0,24,0,24)).setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(),androidx.core.graphics.Insets.of(0,0,0,280)).setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(),true).build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(compose.activity.findViewById(android.R.id.content),insets)
        }
        text("call-2-sold","3");compose.onNodeWithTag("call-2-sold").assertIsDisplayed()
        compose.onNodeWithText("Simpan & Tambah Outlet").assertIsDisplayed()
        snapshot("STAGE3_FONT_130_IME")
    }
    @Test fun exitStaysOpenWhenAutosaveFailsAndRetryKeepsTheDraft() {
        var exitRequested=false
        compose.runOnIdle {
            compose.activity.setContent { LhsApp(vm,onExit={ exitRequested=true }) }
            vm.newCall()
            vm.call { it.copy(outlets=listOf(Outlet(quantities=listOf(Quantity(sold=-1),Quantity(),Quantity())))) }
            vm.navigate("home")
        }
        awaitState("Invalid data rejected by Room write") { vm.state.value.pending==0 && vm.state.value.saveFailed }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        click("Keluar")
        compose.waitUntil(15000) { compose.onAllNodesWithText("Coba Lagi").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(exitRequested)
        compose.onNodeWithText("Keluar dari LHS Papua?").assertExists()
        click("Coba Lagi")
        compose.waitUntil(15000) { compose.onAllNodesWithText("Coba Lagi").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(exitRequested)
        click("Batal")
        compose.onNodeWithTag("tab-home").assertIsSelected()
        val id=vm.state.value.call!!.id
        compose.runOnIdle {
            vm.call { it.copy(outlets=listOf(Outlet(name="Pulih",quantities=listOf(Quantity(sold=20),Quantity(),Quantity())))) }
            vm.retrySave()
        }
        awaitState("Corrected draft persists") { vm.state.value.pending==0 && !vm.state.value.saveFailed }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        click("Keluar")
        awaitState("Exit callback after durable save") { exitRequested }
        assertEquals(20L,runBlocking { vm.repository.call(id) }?.outlets?.single()?.quantities?.first()?.sold)
    }

    @Test fun draftListConfirmationCancelsAndThenDeletesOnlySelectedDraft() {
        newCall()
        val id=vm.state.value.call!!.id
        saved()
        compose.onNodeWithTag("tab-drafts").performClick()
        click("Hapus Draft")
        compose.onNodeWithText("Hapus Draft?").assertExists()
        click("Batal")
        assertNotNull(runBlocking { vm.repository.call(id) })
        click("Hapus Draft")
        compose.onNode(hasText("Hapus Draft") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        awaitState("Draft deleted") { vm.state.value.pending==0 && vm.state.value.calls.none { it.id==id } }
        assertNull(runBlocking { vm.repository.call(id) })
    }

    @Test fun draftAndHistoryFollowStatusAndHomeOnlyShowsFinalDailySales() {
        val today=ReportTime.date(java.time.Instant.now()).toString()
        val identity=Identity(date=today,sales="MAIKEL")
        val created="2026-11-07T05:35:00Z"
        val draftCall=CallReport(identity=identity,createdAt=created)
        val finalCall=CallReport(identity=identity,completed=true,
            outlets=listOf(Outlet(name="TOKO",quantities=listOf(Quantity(sold=1000),Quantity(),Quantity()))))
        val base=newDaily(identity)
        val draftDay=base.copy(createdAt=created,products=base.products.mapIndexed { index,stock ->
            if(index==1) stock.copy(sales=stock.sales.replace(2,stock.sales[2].copy(quantity=500))) else stock
        })
        val finalDay=draftDay.copy(id=java.util.UUID.randomUUID().toString(),status=ReportStatus.FINAL,finalizedAt=created)
        runBlocking {
            vm.repository.save(draftCall)
            vm.repository.save(finalCall)
            vm.repository.save(draftDay)
            vm.repository.save(finalDay)
        }
        awaitState("Four reports in Room") { vm.state.value.calls.size==2 && vm.state.value.days.size==2 }
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("sales-today") and hasText("Rp7.750.000")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sales-today").assertTextEquals("Rp7.750.000")
        compose.onNodeWithTag("sales-period-1").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("sales-period-1") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sales-period-2").performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("sales-period-2") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sales-bars").assertExists()
        listOf("Belum ada draf","Lanjutkan Draft","Lanjutkan Laporan","Tanpa batang = belum ada omzet tercatat.").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("tab-drafts").assertIsDisplayed()
        snapshot("STAGE38_DASHBOARD")

        compose.onNodeWithTag("tab-drafts").performClick().assertIsSelected()
        compose.onNodeWithTag("report-${draftCall.id}").assertExists()
        compose.onNodeWithTag("report-${draftDay.id}").assertExists()
        compose.onNodeWithTag("report-${finalCall.id}").assertDoesNotExist()
        compose.onNodeWithTag("report-${finalDay.id}").assertDoesNotExist()
        compose.onAllNodesWithText("Dibuat: 07/11/2026 · 14.35 WIT").onFirst().assertExists()
        compose.onNodeWithTag("report-filter-2").performClick()
        compose.onNodeWithTag("report-${draftCall.id}").assertDoesNotExist()
        compose.onNodeWithTag("report-filter-0").performClick()
        snapshot("STAGE38_DRAFTS")
        for((id,route) in listOf(draftCall.id to "call",draftDay.id to "day")) {
            compose.onNode(hasText("Lanjutkan") and hasAnyAncestor(hasTestTag("report-$id"))).performClick()
            awaitState("Draft opened from its own tab") { vm.state.value.screen==route }
            compose.onNodeWithTag("tab-drafts").performClick()
        }
        compose.onNodeWithTag("tab-history").performClick().assertIsSelected()
        compose.onNodeWithTag("report-${finalCall.id}").assertExists()
        compose.onNodeWithTag("report-${finalDay.id}").assertExists()
        compose.onNodeWithTag("report-${draftDay.id}").assertDoesNotExist()
        compose.onNodeWithText("Diselesaikan: Waktu tidak tercatat").assertExists()
        snapshot("STAGE37_HISTORY")

        runBlocking {
            vm.repository.finalizeDay(draftDay.id)
            vm.repository.reopen(finalDay.id)
        }
        awaitState("Reports switch statuses") {
            vm.state.value.days.any { it.id==finalDay.id && it.status==ReportStatus.DRAFT } &&
                vm.state.value.days.any { it.id==draftDay.id && it.status==ReportStatus.FINAL }
        }
        compose.onNodeWithTag("report-${draftDay.id}").assertExists()
        compose.onNodeWithTag("report-${finalDay.id}").assertDoesNotExist()
        compose.onNodeWithTag("tab-drafts").performClick()
        compose.onNodeWithTag("report-${finalDay.id}").assertExists()
        compose.onNodeWithTag("report-${draftDay.id}").assertDoesNotExist()
    }
    @Test fun optionalFeaturesDefaultOffAndPreserveValuesAndNewAuthor() {
        compose.onNodeWithContentDescription("Pengaturan").performClick()
        click("Pengaturan Fitur LHS")
        listOf("stock","tester","finance").forEach { compose.onNodeWithTag("feature-$it").assertIsOff() }
        saved()
        val writesBefore=vm.durableWrites
        compose.runOnIdle { vm.settings { it };vm.flushPending() }
        saved()
        assertEquals(writesBefore,vm.durableWrites)
        compose.runOnIdle { vm.newDay();vm.updateIdentity(vm.state.value.day!!.identity.copy(sales="Maikel")) }
        saved()
        val id=vm.state.value.day!!.id
        listOf("do","sample","bonus","tg").forEach { compose.onNodeWithTag("stock-0-$it").assertIsNotEnabled() }
        compose.onNodeWithTag("sale-0-G").assertIsEnabled()
        compose.onNodeWithTag("sale-0-RTL").assertIsEnabled()
        click("Tester")
        repeat(3) { product ->
            listOf("do","sample","tg").forEach { compose.onNodeWithTag("tester-$product-$it").assertIsNotEnabled() }
        }
        click("Keuangan")
        listOf("Saldo Sebelum","Piutang","Pelunasan","Transfer","Pengeluaran","Saldo Akhir").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
        compose.onNodeWithTag("Keterangan").assertIsEnabled()
        compose.onNodeWithTag("Dibuat Oleh").assertIsNotEnabled().assertTextContains("MAIKEL")
        text("Keterangan","tetap aktif")
        compose.runOnIdle { vm.updateIdentity(vm.state.value.day!!.identity.copy(sales="Rani")) }
        compose.onNodeWithTag("Dibuat Oleh").assertTextContains("RANI")
        saved()
        assertEquals("RANI",runBlocking { vm.repository.daily(id) }!!.finance.madeBy)

        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithContentDescription("Pengaturan").performClick()
        click("Pengaturan Fitur LHS")
        compose.onNodeWithTag("feature-finance").performClick().assertIsOn()
        compose.onNodeWithTag("feature-tester").assertIsOff()
        compose.onNodeWithTag("feature-stock").assertIsOff()
        saved()
        compose.runOnIdle { vm.open(vm.state.value.day!!) }
        compose.onNodeWithTag("Saldo Sebelum").assertIsEnabled()
        text("Saldo Sebelum","1000")
        compose.runOnIdle { vm.settings { it.copy(lhsFeatures=LhsFeatureSettings()) } }
        saved()
        compose.onNodeWithTag("Saldo Sebelum").assertIsNotEnabled().assertTextContains("1.000")
        assertEquals(1000L,runBlocking { vm.repository.daily(id) }!!.finance.previous)
        snapshot("STAGE39_DISABLED_FIELDS")
    }

    @Test fun integrationOffersFinalCandidatesAndRequiresExplicitConfirmation() {
        val identity=Identity(sales="MAIKEL",area="SORONG",route="A")
        fun call(sold:Long,final:Boolean=true)=CallReport(identity=identity,completed=final,
            outlets=listOf(Outlet(quantities=listOf(Quantity(sold=0),Quantity(sold=sold),Quantity(sold=0)))))
        val first=call(650)
        val second=call(700)
        val sourceDraft=call(500,false)
        val day=newDaily(identity).copy(reportMode=null)
        runBlocking {
            vm.repository.save(first)
            vm.repository.save(second)
            vm.repository.save(sourceDraft)
            vm.repository.save(day)
        }
        awaitState("Callsheet choices persisted") { vm.state.value.calls.size==3 }
        compose.runOnIdle { vm.open(day) }
        compose.onNodeWithText("Hubungkan Callsheet").assertDoesNotExist()
        compose.runOnIdle { vm.settings { it.copy(integrationMode=IntegrationMode.TERINTEGRASI) } }
        saved()
        click("Pilih Callsheet")
        compose.onNodeWithTag("call-source-${second.id}").assertExists()
        compose.onNodeWithTag("call-source-${sourceDraft.id}").assertDoesNotExist()
        compose.onNodeWithText("ID ",substring=true).assertDoesNotExist()
        compose.onNodeWithTag("call-source-${first.id}").performClick()
        compose.onNodeWithText("Hubungkan Callsheet dengan LHS?").assertExists()
        assertNull(runBlocking { vm.repository.daily(day.id) }!!.callsheetLink)
        compose.onNode(hasText("Hubungkan") and hasAnyAncestor(isDialog())).performClick()
        awaitState("Link confirmed") { vm.state.value.day?.callsheetLink?.sourceId==first.id }
        assertTrue(vm.state.value.day!!.products.all { it.sales.all { sale->sale.quantity==null } })
        click("Periksa Laporan")
        compose.onNodeWithText("Selesaikan Laporan").assertIsNotEnabled()
        click("Lepaskan Tautan Callsheet")
        click("Lepaskan")
        awaitState("Link removed") { !vm.state.value.busy && vm.state.value.day?.callsheetLink==null }
        compose.onNodeWithText("Selesaikan Laporan").assertIsEnabled()
    }

    @Test fun singleSourceUsesOneDialogAndZeroRequiresExplicitAction() {
        val identity=Identity(sales="MAIKEL")
        val source=CallReport(identity=identity,completed=true,
            outlets=listOf(Outlet(quantities=listOf(Quantity(sold=370),Quantity(),Quantity()))))
        val day=newDaily(identity).copy(reportMode=null)
        runBlocking { vm.repository.save(source);vm.repository.save(day) }
        awaitState("Single Callsheet persisted") { vm.state.value.calls.any { it.id==source.id } }
        compose.runOnIdle {
            vm.open(day)
            vm.settings { it.copy(integrationMode=IntegrationMode.TERINTEGRASI) }
        }
        saved()
        text("sale-0-RTL","370")
        click("Hubungkan")
        compose.onNodeWithText("Pilih Callsheet Final").assertDoesNotExist()
        compose.onNodeWithText("Hubungkan Callsheet dengan LHS?").assertExists()
        assertNull(runBlocking { vm.repository.daily(day.id) }!!.callsheetLink)
        compose.onNode(hasText("Hubungkan") and hasAnyAncestor(isDialog())).performClick()
        awaitState("Single Callsheet confirmed") { vm.state.value.day?.callsheetLink?.sourceId==source.id && !vm.state.value.busy }
        assertNull(vm.state.value.day!!.products[0].sales[Tier.G.ordinal].quantity)
        assertEquals(370L,vm.state.value.day!!.products[0].sales[Tier.RTL.ordinal].quantity)
        compose.onNodeWithTag("call-zero-0-G").performScrollTo().performClick()
        saved()
        assertEquals(0L,runBlocking { vm.repository.daily(day.id) }!!.products[0].sales[Tier.G.ordinal].quantity)
        compose.onNodeWithText("Cocok").assertExists()
        compose.onNodeWithTag("call-zero-0-G").assertDoesNotExist()
        assertNull(vm.state.value.day!!.products[1].sales[Tier.G.ordinal].quantity)
        snapshot("STAGE391_INTEGRATION")
    }

    @Test fun upgradingAnOldDraftRequiresConfirmationAndKeepsItsManualValue() {
        val initial=newDaily(Identity(sales="Historical Sales")).copy(formatVersion=3)
        val old=initial.copy(products=initial.products.mapIndexed { index,stock ->
            if(index!=0)stock else stock.copy(manualSold=95,manualTg=50,sales=stock.sales.mapIndexed { tier,sale ->
                if(tier==Tier.RTL.ordinal)sale.copy(quantity=50) else sale
            })
        })
        runBlocking { vm.repository.save(old) }
        compose.runOnIdle { vm.open(old) }
        click("Aktifkan Jual Otomatis")
        compose.onNodeWithText("MARTIL 12: 95 → 50",substring=true).assertExists()
        click("Batal")
        assertEquals(3,vm.state.value.day!!.formatVersion)
        click("Aktifkan Jual Otomatis")
        click("Aktifkan")
        saved()
        val upgraded=runBlocking { requireNotNull(vm.repository.daily(old.id)) }
        assertTrue(upgraded.automaticSold)
        assertEquals(95L,upgraded.products[0].manualSold)
        assertEquals(50L,Calculation.stockSold(upgraded,0))
        assertEquals(50L,Calculation.stockTg(upgraded,0))
    }

}
