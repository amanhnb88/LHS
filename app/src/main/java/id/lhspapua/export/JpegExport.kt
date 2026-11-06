package id.lhspapua.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.Locale

enum class ExportFormat(val label:String,val extension:String) {
    PDF("PDF","pdf"), EXCEL("Excel","xlsx"), JPG("JPG","jpg")
}

object JpegExport {
    /** Each bitmap covers one complete A4 page at nominal 300 DPI. */
    fun write(pdf:File,directory:File,baseName:String):List<File> {
        val completed=mutableListOf<File>()
        try {
            PdfRenderer(ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                for(index in 0 until renderer.pageCount)renderer.openPage(index).use { page ->
                    val landscape=page.width>page.height
                    val width=if(landscape)3508 else 2480
                    val height=if(landscape)2480 else 3508
                    val suffix=if(renderer.pageCount==1)"" else "_%02d".format(Locale.ROOT,index+1)
                    val file=File(directory,"$baseName$suffix.jpg")
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                    completed.add(file)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        val scale=minOf(width.toFloat()/page.width,height.toFloat()/page.height)
                        val matrix=Matrix().apply {
                            setScale(scale,scale)
                            postTranslate((width-page.width*scale)/2f,(height-page.height*scale)/2f)
                        }
                        page.render(bitmap,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        file.outputStream().buffered().use {
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG,94,it)) { "Gambar JPG gagal ditulis." }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            check(completed.isNotEmpty()) { "Dokumen tidak memiliki halaman." }
            return completed
        } catch(e:Exception) {
            for(file in completed)file.delete()
            throw e
        }
    }
}
