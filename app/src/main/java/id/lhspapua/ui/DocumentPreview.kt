package id.lhspapua.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import id.lhspapua.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Pure viewport math, separate from PDF physical page dimensions. */
object PreviewGeometry {
    fun fit(pageWidth:Float,pageHeight:Float,width:Float,height:Float):Float = min(width/pageWidth,height/pageHeight)
    fun fitWidthZoom(pageWidth:Float,pageHeight:Float,width:Float,height:Float) = ((width/pageWidth)/fit(pageWidth,pageHeight,width,height)).coerceIn(1f,6f)
    fun gesture(offset:Offset,oldZoom:Float,newZoom:Float,centroid:Offset,pan:Offset,width:Float,height:Float):Offset {
        val pivot=centroid-Offset(width/2,height/2)
        return (offset-pivot)*(newZoom/oldZoom)+pivot+pan
    }
    fun clamp(offset:Offset,zoom:Float,pageWidth:Float,pageHeight:Float,width:Float,height:Float):Offset {
        val base=fit(pageWidth,pageHeight,width,height)
        val x=max(0f,(pageWidth*base*zoom-width)/2)
        val y=max(0f,(pageHeight*base*zoom-height)/2)
        return Offset(offset.x.coerceIn(-x,x),offset.y.coerceIn(-y,y))
    }
}
data class RenderedPage(val bitmap:Bitmap,val pageCount:Int)
object NativePdfPreview {
    fun render(file:File,index:Int,maxSide:Int=2048):RenderedPage {
        return PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            require(index in 0 until renderer.pageCount) { "Halaman tidak ditemukan." }
            renderer.openPage(index).use { page ->
                val scale=maxSide.toFloat()/max(page.width,page.height)
                val bitmap=Bitmap.createBitmap(max(1,(page.width*scale).toInt()),max(1,(page.height*scale).toInt()),Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                RenderedPage(bitmap,renderer.pageCount)
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DocumentPreview(file:File,title:String,bottomBar:@Composable ()->Unit,onClose:()->Unit) {
    var page by rememberSaveable(file.absolutePath) { mutableIntStateOf(0) }
    var rendered by remember(file) { mutableStateOf<RenderedPage?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var zoom by rememberSaveable(file.absolutePath,page) { mutableFloatStateOf(1f) }
    var offset by remember(file,page) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(file,page) {
        rendered=null;error=null
        try { rendered=withContext(Dispatchers.IO) { NativePdfPreview.render(file,page) } }
        catch(e:kotlinx.coroutines.CancellationException) { throw e }
        catch(e:Exception) { error=e.message ?: "Pratinjau tidak dapat dibuka." }
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Scaffold(contentWindowInsets=WindowInsets.safeDrawing,bottomBar=bottomBar,topBar={ TopAppBar(title={ Text(title) },navigationIcon={ IconButton(onClick=onClose) { Icon(painterResource(R.drawable.nav_back),"Kembali") } }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                val image=rendered
                if(error!=null) Text(error!!,Modifier.padding(20.dp),color=MaterialTheme.colorScheme.error)
                if(image==null && error==null) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
                    TextButton(onClick={ zoom=1f;offset=Offset.Zero }) { Text("Halaman utuh") }
                    TextButton(onClick={ if(image!=null && viewport.width>0 && viewport.height>0) { zoom=PreviewGeometry.fitWidthZoom(image.bitmap.width.toFloat(),image.bitmap.height.toFloat(),viewport.width.toFloat(),viewport.height.toFloat());offset=Offset.Zero } },enabled=image!=null) { Text("Lebar layar") }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { viewport=it }) {
                    if(image!=null) {
                        val density=LocalDensity.current
                        val width=with(density) { maxWidth.toPx() };val height=with(density) { maxHeight.toPx() }
                        val bw=image.bitmap.width.toFloat();val bh=image.bitmap.height.toFloat()
                        fun setZoom(next:Float,pan:Offset,centroid:Offset) {
                            val newZoom=next.coerceIn(1f,6f)
                            offset=PreviewGeometry.clamp(PreviewGeometry.gesture(offset,zoom,newZoom,centroid,pan,width,height),newZoom,bw,bh,width,height)
                            zoom=newZoom
                        }
                        Box(Modifier.fillMaxSize()
                            .pointerInput(file,page,width,height) {
                                detectTransformGestures { centroid,pan,change,_ -> setZoom(zoom*change,pan,centroid) }
                            }
                            .pointerInput(file,page,width,height) {
                                detectTapGestures(onDoubleTap={ point -> setZoom(if(zoom>1f)1f else 2.5f,Offset.Zero,point) })
                            }
                        ) {
                        Image(image.bitmap.asImageBitmap(),contentDescription="Halaman ${page+1} dari ${image.pageCount}",contentScale=ContentScale.Fit,
                            modifier=Modifier.fillMaxSize().graphicsLayer(scaleX=zoom,scaleY=zoom,translationX=offset.x,translationY=offset.y))
                        }

                    }
                }
                Surface(tonalElevation=2.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),
                        verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.SpaceBetween) {
                        if(image!=null && image.pageCount>1)TextButton(onClick={ page-- },enabled=page>0) { Text("‹ Hal.") }
                        TextButton(onClick={ zoom=(zoom/1.25f).coerceAtLeast(1f);offset=Offset.Zero },enabled=zoom>1f,modifier=Modifier.semantics { contentDescription="Perkecil" }) { Text("−") }
                        Text(if(image==null)"…" else "${page+1} / ${image.pageCount}",style=MaterialTheme.typography.labelLarge)
                        TextButton(onClick={ zoom=(zoom*1.25f).coerceAtMost(6f) },enabled=zoom<6f,modifier=Modifier.semantics { contentDescription="Perbesar" }) { Text("+") }
                        if(image!=null && image.pageCount>1)TextButton(onClick={ page++ },enabled=page+1<image.pageCount) { Text("Hal. ›") }
                    }
                }
            }
        }
    }
}
