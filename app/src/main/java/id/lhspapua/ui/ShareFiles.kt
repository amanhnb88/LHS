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
        val mimeTypes=files.map {
            when(it.extension.lowercase(java.util.Locale.ROOT)) {
                "pdf" -> "application/pdf"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "jpg","jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "application/octet-stream"
            }
        }.distinct()
        val mime=mimeTypes.singleOrNull() ?: "*/*"
        return Intent(if(files.size==1)Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type=mime;addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if(uris.size==1)putExtra(Intent.EXTRA_STREAM,uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris)
            clipData=ClipData.newRawUri("Laporan LHS Papua",uris.first()).also { c->uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        }
    }
    fun view(context:Context,file:File) {
        val source=intent(context,listOf(file))
        context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(source.clipData!!.getItemAt(0).uri,source.type);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);clipData=source.clipData })
    }
    fun share(context:Context,files:List<File>) { context.startActivity(Intent.createChooser(intent(context,files),"Bagikan laporan")) }
}
