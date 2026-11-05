package id.lhspapua.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ShareFiles {
    fun intent(context:Context,files:List<File>):Intent {
        require(files.isNotEmpty() && files.all { it.isFile }) { "File belum tersedia. Buat ulang ekspor." }
        val uris=ArrayList(files.map { FileProvider.getUriForFile(context,"${context.packageName}.files",it) })
        val mime=if(files.size>1) "*/*" else when(files.first().extension) { "pdf"->"application/pdf";"xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";"jpg"->"image/jpeg";"png"->"image/png";"webp"->"image/webp";else->"application/octet-stream" }
        return Intent(if(files.size==1)Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type=mime;addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if(uris.size==1)putExtra(Intent.EXTRA_STREAM,uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris)
            clipData=ClipData.newRawUri("Laporan LHS Papua",uris.first()).also { c->uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        }
    }
    fun share(context:Context,files:List<File>) { context.startActivity(Intent.createChooser(intent(context,files),"Bagikan laporan")) }
}
