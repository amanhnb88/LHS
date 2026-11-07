package id.lhspapua.export

import id.lhspapua.domain.*

/** One physical grid drives both vector PDF and real OOXML cells. Coordinates in points. */
data class Cell(val row: Int, val col: Int, val rows: Int=1, val cols: Int=1, val text: String="", val number: Long?=null, val formula: String?=null, val bold: Boolean=false, val center: Boolean=false, val border: Boolean=true, val money: Boolean=false, val size: Float=9f, val edges: Int=15)
data class Sheet(val name: String, val landscape: Boolean, val widths: List<Double>, val heights: List<Double>, val cells: MutableList<Cell> = mutableListOf(), val hidden: Boolean = false) {
    fun put(r:Int,c:Int,rs:Int=1,cs:Int=1,t:String="",n:Long?=null,f:String?=null,b:Boolean=false,center:Boolean=false,border:Boolean=true,money:Boolean=false,size:Float=9f,edges:Int=15) { cells.add(Cell(r,c,rs,cs,t,n,f,b,center,border,money,size,edges)) }
    fun ref(r:Int,c:Int)=column(c)+(r+1)
    companion object { fun column(index: Int): String { var n=index+1; var s=""; while(n>0) { s=('A'+(n-1)%26)+s; n=(n-1)/26 }; return s } }
}
object Layout {
    private val callEdges=listOf(0.0,35.0,152.0,389.0,455.0,521.0,587.0,653.0,719.0,785.0)
    private val callHeaderEdges=List(9) { it*785.0/8 }
    private val callGridEdges=(callEdges+callHeaderEdges).distinct().sorted()
    fun callColumn(index:Int)=callGridEdges.indexOf(callEdges[index])
    fun calls(r: CallReport): List<Sheet> {
        val active=r.activeOutlets()
        val chunks=if(active.isEmpty()) listOf(emptyList()) else active.chunked(30)
        return chunks.mapIndexed { page, outlets ->
            val s=Sheet("Callsheet ${page+1}",true,callGridEdges.zipWithNext { a,b -> b-a }, listOf(22.0,18.0,15.0,13.0,13.0)+List(30){14.5}+listOf(20.0))
            fun span(c:Int,count:Int=1)=callColumn(c+count)-callColumn(c)
            s.put(0,0,cs=s.widths.size,t="CALLSHEET - PAPUA",b=true,center=true,border=false,size=19f)
            val i=r.identity
            listOf("Seles: ${i.sales}","Join: ${i.join}","Area: ${i.area}","Rute: ${i.route}","Kendaraan: ${i.vehicle}","NOPOL: ${i.plate}","Hari: ${i.day()}","Tgl: ${i.displayDate()}").forEachIndexed { k,t ->
                val left=callGridEdges.indexOf(callHeaderEdges[k]); val right=callGridEdges.indexOf(callHeaderEdges[k+1])
                s.put(1,left,cs=right-left,t=t,border=false,size=8f)
            }
            s.put(2,callColumn(0),rs=3,cs=span(0),t="NO",b=true,center=true)
            s.put(2,callColumn(1),rs=3,cs=span(1),t="OUTLET",b=true,center=true)
            s.put(2,callColumn(2),rs=3,cs=span(2),t="ALAMAT",b=true,center=true)
            s.put(2,callColumn(3),cs=span(3,6),t="PENJUALAN PRODUK (dalam bungkus)",b=true,center=true,size=11f)
            Product.entries.forEachIndexed { p, product ->
                s.put(3,callColumn(3+p*2),cs=span(3+p*2,2),t=product.label,b=true,center=true)
                s.put(4,callColumn(3+p*2),cs=span(3+p*2),t="Stok",b=true,center=true)
                s.put(4,callColumn(4+p*2),cs=span(4+p*2),t="Jual",b=true,center=true)
            }
            repeat(30) { k ->
                val row=5+k; val o=outlets.getOrNull(k)
                s.put(row,callColumn(0),cs=span(0),n=(page*30+k+1).toLong(),center=true,size=8f)
                s.put(row,callColumn(1),cs=span(1),t=o?.name ?: "",size=8f)
                s.put(row,callColumn(2),cs=span(2),t=o?.address ?: "",size=8f)
                repeat(3) { p ->
                    s.put(row,callColumn(3+p*2),cs=span(3+p*2),n=o?.quantities?.get(p)?.stock,center=true,size=8f)
                    s.put(row,callColumn(4+p*2),cs=span(4+p*2),n=o?.quantities?.get(p)?.sold,center=true,size=8f)
                }
            }
            s.put(35,0,cs=callColumn(3),t="JUMLAH",b=true)
            repeat(6) { c ->
                val col=callColumn(c+3); val refs="${s.ref(5,col)}:${s.ref(34,col)}"
                s.put(35,col,cs=span(c+3),n=Calculation.sum(outlets.map { if(c%2==0) it.quantities[c/2].stock else it.quantities[c/2].sold }),f="IF(COUNT($refs)=0,\"\",SUM($refs))",b=true,center=true,size=8f)
            }
            s
        }
    }
    fun daily(r: DailyReport): Sheet {
        val s=Sheet("Laporan Harian",false,List(24){22.45}, listOf(29.0,10.0,25.0,25.0,12.0,22.0)+List(24){13.5}+listOf(12.0,20.0,19.0,16.0,16.0,16.0,10.0,22.0,14.0)+List(7){19.0})
        val i=r.identity
        s.put(0,0,cs=24,t="LAPORAN HARIAN SALES - PAPUA",b=true,center=true,border=false,size=17f)
        listOf("Hari: ${i.day()}","Sales: ${i.sales}","Area: ${i.area}","Rute: ${i.route}","Kendaraan: ${i.vehicle}","Nopol: ${i.plate}").forEachIndexed { k,t -> s.put(2,k*4,cs=4,t=t,b=true,size=8f) }
        s.put(3,0,cs=8,t="Tgl: ${i.displayDate()}",b=true); s.put(3,8,cs=16,t="Join: ${i.join}",b=true)
        s.put(5,0,cs=10,t="STOCK",b=true,center=true,size=14f); s.put(5,10,cs=14,t="PENJUALAN",b=true,center=true,size=14f)
        val missingPrices = r.products.flatMapIndexed { p, stock ->
            stock.sales.indices.filter { stock.sales[it].applicable && r.salesMode.allows(Tier.entries[it]) }.map { tier ->
                missingPrice(s, 8+p*8+tier)
            }
        }.joinToString(",")
        r.products.forEachIndexed { p, stock ->
            val base=6+p*8
            val activeTiers = stock.sales.indices.filter { stock.sales[it].applicable && r.salesMode.allows(Tier.entries[it]) }
            val soldRefs = activeTiers.joinToString(",") { s.ref(base+2+it,13) }
            s.put(base,0,rs=2,cs=10,t=Product.entries[p].label,b=true,size=12f); s.put(base,10,rs=2,cs=14,t=Product.entries[p].label,b=true,size=12f)
            val quantities=listOf(stock.delivery,Calculation.stockSold(r,p),stock.sample,stock.bonus,Calculation.stockTg(r,p))
            listOf("DO","Jual","Sample","Bns","TG").forEachIndexed { k,label ->
                s.put(base+2+k,0,cs=4,t="$label :",edges=11)
                val f=when(k) {
                    1 -> if(r.independent && !r.automaticSold) null else "IF(COUNT($soldRefs)=0,\"\",SUM($soldRefs))"
                    4 -> if(r.independent) null else if(stock.openingTracked) {
                        val opening="'_StokAwal'!\$B\$${p+2}"
                        "IF(OR($opening=\"\",${s.ref(base+2,4)}=\"\",${s.ref(base+4,4)}=\"\",${s.ref(base+5,4)}=\"\",COUNT($soldRefs)<${activeTiers.size}),\"\",$opening+${s.ref(base+2,4)}-SUM(${s.ref(base+3,4)},${s.ref(base+4,4)},${s.ref(base+5,4)}))"
                    } else if(r.rules.automaticTg) "IF(${s.ref(base+2,4)}=\"\",\"\",${s.ref(base+2,4)}-SUM(${s.ref(base+3,4)})${if(r.rules.subtractSample) "-SUM(${s.ref(base+4,4)})" else ""}${if(r.rules.subtractBonus) "-SUM(${s.ref(base+5,4)})" else ""})" else null
                    else -> null
                }
                s.put(base+2+k,4,cs=6,n=quantities[k],f=f,edges=14)
            }
            // The empty separator under each product is preserved in the reference.
            s.put(base+7,0,cs=10)
            stock.sales.forEachIndexed { t,sale ->
                val row=base+2+t
                s.put(row,10,cs=3,t=Tier.entries[t].label,b=true,center=true)
                s.put(row,13,cs=2,n=sale.quantity.takeIf { sale.applicable },center=true,edges=11)
                s.put(row,15,t="x",center=true,edges=10)
                s.put(row,16,cs=3,t=if(sale.applicable) "" else "-",n=sale.price.takeIf { sale.applicable },money=true,edges=10)
                s.put(row,19,t="=",center=true,edges=10)
                val q=s.ref(row,13); val price=s.ref(row,16)
                s.put(row,20,cs=4,n=if(t in activeTiers)Calculation.subtotal(sale) else null,f=if(t in activeTiers) "IF($q=\"\",\"\",IF($q=0,0,IF($price=\"\",\"\",$q*$price)))" else null,money=true,edges=14)
            }
            s.put(base+5,10,rs=2,cs=10,t="Total",b=true,center=true)
            val range = if(activeTiers.size == 3) "${s.ref(base+2,20)}:${s.ref(base+4,20)}"
                else activeTiers.joinToString(",") { s.ref(base+2+it,20) }
            val missing = activeTiers.joinToString(",") { missingPrice(s, base+2+it) }
            s.put(base+5,20,rs=2,cs=4,n=Calculation.productTotal(r,p),f="IF(OR($missing),\"\",IF(COUNT($range)=0,\"\",SUM($range)))",money=true,b=true)
            s.put(base+7,10,cs=14)
        }
        s.put(31,0,cs=17,t="TESTER",b=true,size=12f)
        r.testers.forEachIndexed { p,t ->
            val col=listOf(0,6,12)[p]; val span=if(p==2) 5 else 6
            s.put(32,col,cs=span,t=Product.entries[p].label,b=true,center=true)
            listOf("DO","Sampel","TG").forEachIndexed { k,label ->
                s.put(33+k,col,cs=3,t="$label :",size=8f,edges=11)
                val f=if(r.independent) null else if(k==2 && t.openingTracked) {
                    val opening="'_StokAwal'!\$C\$${p+2}"
                    "IF(OR($opening=\"\",${s.ref(33,col+3)}=\"\",${s.ref(34,col+3)}=\"\"),\"\",$opening+${s.ref(33,col+3)}-${s.ref(34,col+3)})"
                } else if(k==2 && r.rules.automaticTg) "IF(${s.ref(33,col+3)}=\"\",\"\",${s.ref(33,col+3)}-SUM(${s.ref(34,col+3)}))" else null
                s.put(33+k,col+3,cs=span-3,n=listOf(t.delivery,t.sample,Calculation.testerTg(r,p))[k],f=f,size=8f,edges=14)
            }
        }
        s.put(32,18,cs=6,t="G = GROSIR",b=true,border=false,center=true)
        s.put(33,18,cs=6,t="SG = SUB GROSIR",b=true,border=false,center=true)
        s.put(34,18,cs=6,t="RTL = RETAIL",b=true,border=false,center=true)
        s.put(37,0,cs=24,t="NB. BUKTI TRANSFER BANK DAN BIAYA OPERASIONAL WAJIB DILAMPIRKAN !",b=true,center=true,border=false,size=10f)
        val start=39; val f=r.finance
        val vals=listOf(Calculation.total(r),f.previous,f.debt,f.settlement,f.transfer,f.expense,Calculation.balance(r))
        listOf("Total Penjualan","Saldo Sebelum","Piutang","Pelunasan","Transfer","Pengeluaran","Saldo Akhir").forEachIndexed { k,label ->
            s.put(start+k,0,cs=5,t="$label :",b=true,size=8f,edges=11)
            val formula=when(k) {
                0 -> {
                    val refs=listOf(11,19,27).map { s.ref(it,20) }
                    "IF(OR($missingPrices),\"\",IF(COUNT(${refs.joinToString(",")})=0,\"\",SUM(${refs.joinToString(",")})))"
                }
                6 -> if(r.independent || !r.rules.automaticFinance || !r.rules.acknowledged) null else {
                    val range="F40:F45"
                    val condition=if(r.formatVersion>=2) "COUNT($range)<6" else "COUNT($range)=0"
                    "IF(OR($missingPrices),\"\",IF($condition,\"\",SUM(F41,F40)${signed(r.rules.debtSign,"F42")}${signed(r.rules.settlementSign,"F43")}${signed(r.rules.transferSign,"F44")}${signed(r.rules.expenseSign,"F45")}))"
                }
                else -> null
            }
            s.put(start+k,5,cs=5,n=vals[k],f=formula,money=true,b=k==0||k==6,size=8f,edges=14)
        }
        s.put(start,11,cs=7,t="Keterangan",b=true,border=false)
        val lines=noteLines(f.notes)
        require(lines.size<=6) { "Keterangan terlalu panjang untuk 6 baris formulir A4. Pendekkan keterangan." }
        repeat(6) { k -> s.put(start+1+k,11,cs=6,t=lines.getOrNull(k) ?: "",edges=8,size=8f) }
        s.put(start,18,rs=7,edges=1)
        s.put(start,19,cs=5,t="Dibuat Oleh :",b=true)
        s.put(start+1,19,rs=6,cs=5,t=f.madeBy,center=true)
        return s
    }
    fun dailyWorkbook(r:DailyReport):List<Sheet> {
        val main=daily(r)
        if(!r.usesLedger) return listOf(main)
        val helper=Sheet("_StokAwal",false,listOf(110.0,80.0,80.0,180.0),List(4){18.0},hidden=true)
        listOf("Produk","Stok Awal","Stok Awal Tester","Sumber / Penyesuaian").forEachIndexed { c,t->helper.put(0,c,t=t) }
        Product.entries.forEachIndexed { p,product ->
            helper.put(p+1,0,t=product.label)
            helper.put(p+1,1,n=r.products[p].opening)
            helper.put(p+1,2,n=r.testers[p].opening)
            helper.put(p+1,3,t=r.stockSourceId ?: r.openingNote)
        }
        return listOf(main,helper)
    }
    private fun noteLines(text:String):List<String> = text.lines().flatMap { paragraph ->
        val result=mutableListOf<String>(); var line=""
        paragraph.split(" ").forEach { word ->
            if((line+" "+word).trim().length>28 && line.isNotEmpty()) { result.add(line); line=word } else line=if(line.isEmpty()) word else "$line $word"
        }
        result.add(line); result
    }
    private fun missingPrice(sheet: Sheet, row: Int) =
        "AND(${sheet.ref(row,13)}>0,${sheet.ref(row,16)}=\"\")"

    private fun signed(sign:Int,ref:String) = (if(sign==1) "+" else "-")+"SUM($ref)"
}
