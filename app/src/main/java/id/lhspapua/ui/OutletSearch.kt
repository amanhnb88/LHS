package id.lhspapua.ui

import id.lhspapua.domain.*
import java.util.Locale

object OutletSearch {
    fun matches(query:String,settings:Settings,reports:List<CallReport>):List<SavedOutlet> =
        (settings.outlets+reports.flatMap { r->r.activeOutlets().filter { it.name.isNotBlank() }.map { SavedOutlet(it.name,it.address) } })
            .distinctBy { it.name.trim().uppercase(Locale.ROOT) to it.address.trim().uppercase(Locale.ROOT) }
            .filter { matchesText(it.name,query)||matchesText(it.address,query) }.sortedBy { it.name }
    fun matchesText(value:String,query:String)=value.uppercase(Locale.ROOT).contains(query.trim().uppercase(Locale.ROOT))
    fun visit(outlet:SavedOutlet)=Outlet(name=outlet.name.uppercase(Locale.ROOT),address=outlet.address.uppercase(Locale.ROOT))
}
