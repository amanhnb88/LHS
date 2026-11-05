package id.lhspapua

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.geometry.Offset
import id.lhspapua.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class PreviewShareTest {
    @Test fun wholePageFitWidthAndPanBounds() {
        val scale=PreviewGeometry.fit(595f,842f,400f,600f)
        assertTrue(595*scale<=400);assertTrue(842*scale<=600)
        val fitted=PreviewGeometry.clamp(Offset(1000f,-1000f),1f,595f,842f,400f,600f);assertEquals(0f,fitted.x,0.001f);assertEquals(0f,fitted.y,0.001f)
        val wide=PreviewGeometry.fitWidthZoom(842f,595f,400f,200f);assertTrue(wide>1f)
        val pan=PreviewGeometry.clamp(Offset(1000f,-1000f),2f,595f,842f,400f,600f);assertTrue(pan.x in 0f..200f);assertTrue(pan.y in -300f..0f)
    }
    @Test fun sharesOnlyGrantedContentUrisIncludingAttachments() {
        val cache=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache");cache.isAccessible=true;(cache.get(null) as MutableMap<*,*>).clear()
        val context=ApplicationProvider.getApplicationContext<Context>()
        val dir=File(context.cacheDir,"exports").apply { mkdirs() };val pdf=File(dir,"report.pdf").apply { writeText("test") };val image=File(dir,"proof.jpg").apply { writeText("test") }
        val one=ShareFiles.intent(context,listOf(pdf));assertEquals(Intent.ACTION_SEND,one.action);assertEquals("application/pdf",one.type)
        assertTrue(one.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0);assertEquals("content",one.clipData!!.getItemAt(0).uri.scheme)
        val many=ShareFiles.intent(context,listOf(pdf,image));assertEquals(Intent.ACTION_SEND_MULTIPLE,many.action);assertEquals(2,many.clipData!!.itemCount)
    }

}
