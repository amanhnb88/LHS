package id.lhspapua.ui

import id.lhspapua.domain.*
import java.util.Locale

object OutletSearch {
    fun matches(query:String,settings:Settings,reports:List<CallReport>):List<SavedOutlet> =
        (settings.outlets+reports.flatMap { r->r.activeOutlets().filter { it.name.isNotBlank() }.map { SavedOutlet(it.name,it.address) } })
            .distinctBy { it.name.trim().lowercase(Locale.ROOT) to it.address.trim().lowercase(Locale.ROOT) }
            .filter { it.name.contains(query.trim(),true)||it.address.contains(query.trim(),true) }.sortedBy { it.name }
    fun visit(outlet:SavedOutlet)=Outlet(name=outlet.name,address=outlet.address)
}
