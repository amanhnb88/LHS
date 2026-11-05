package id.lhspapua.ui

import id.lhspapua.domain.*

data class ReportIssue(val message:String,val action:String,val field:String,val section:Int=0,val product:Int=-1,val outletId:String?=null)
object ReportIssues {
    fun call(r:CallReport):List<ReportIssue> = buildList {
        if(r.identity.sales.isBlank())add(ReportIssue("Nama Sales belum diisi.","Isi Nama Sales","Nama Sales"))
        r.activeOutlets().forEach { o->
            if(o.name.isBlank())add(ReportIssue("Nama outlet belum diisi.","Isi Nama Outlet","Nama Outlet",outletId=o.id))
            o.quantities.forEachIndexed { p,q->
                if(q.stock==null)add(ReportIssue("${o.name} · ${Product.entries[p].label}: stok belum diisi.","Isi Stok ${Product.entries[p].label}","call-$p-stock",outletId=o.id))
                if(q.sold==null)add(ReportIssue("${o.name} · ${Product.entries[p].label}: jual belum diisi.","Isi Jual ${Product.entries[p].label}","call-$p-sold",outletId=o.id))
                if(q.stock!=null&&q.sold!=null&&q.sold>q.stock)add(ReportIssue("${o.name} · ${Product.entries[p].label}: Jual melebihi Stok.","Periksa Jual ${Product.entries[p].label}","call-$p-sold",outletId=o.id))
            }
        }
        if(Calculation.errors(r).any { it.contains("besar") })add(ReportIssue("Total terlalu besar untuk diekspor.","Periksa jumlah outlet","outlet-search"))
    }
    /** Routing adapter only; every arithmetic result and finalization gate still comes from Calculation. */
    fun daily(r:DailyReport):List<ReportIssue> = Calculation.errors(r).map { message->
        val p=Product.entries.indexOfFirst { message.startsWith(it.label)||message.startsWith("Tester ${it.label}") }
        val tester=message.startsWith("Tester")||message.contains("tester",true)
        when {
            message.contains("harga",true)&&p>=0->{
                val t=Tier.entries.indexOfFirst { message.startsWith("${Product.entries[p].label} ${it.label}:") }.coerceAtLeast(0)
                ReportIssue(message,"Atur Harga ${listOf("Grosir","Sub Grosir","Retail")[t]}","price-$p-$t",0,p)
            }
            tester->ReportIssue(message,if(p>=0)"Periksa Tester ${Product.entries[p].label}" else "Periksa Tester",if(p>=0)"tester-$p-${if(r.testers[p].opening==null)"opening" else "do"}" else "tester-0-opening",1,p)
            p>=0->{
                val stock=r.products[p]
                val field=when { stock.opening==null->"opening";stock.delivery==null->"do";stock.sample==null->"sample";stock.bonus==null->"bonus";else->"do" }
                val emptySale=stock.sales.indexOfFirst { it.quantity==null }
                val tag=if(stock.opening!=null&&stock.delivery!=null&&stock.sample!=null&&stock.bonus!=null&&emptySale>=0)"sale-$p-${Tier.entries[emptySale].label}" else "stock-$p-$field"
                ReportIssue(message,if(stock.opening==null)"Isi Stok Awal ${Product.entries[p].label}" else "Periksa ${Product.entries[p].label}",tag,0,p)
            }
            message.contains("aturan",true)->ReportIssue(message,"Atur Keuangan","rules",2)
            message.contains("Saldo Akhir manual",true)->ReportIssue(message,"Isi Saldo Akhir","Saldo Akhir Manual",2)
            message.contains("Keuangan",true)->ReportIssue(message,"Lengkapi Keuangan",listOf("Saldo Sebelum","Piutang","Pelunasan","Transfer","Pengeluaran").zip(listOf(r.finance.previous,r.finance.debt,r.finance.settlement,r.finance.transfer,r.finance.expense)).firstOrNull { it.second==null }?.first ?: "Saldo Sebelum",2)
            message.contains("Sales",true)->ReportIssue(message,"Periksa Identitas","Nama Sales")
            else->ReportIssue(message,"Periksa Laporan","identity")
        }
    }
}
