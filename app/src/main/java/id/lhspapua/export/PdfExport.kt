package id.lhspapua.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.text.NumberFormat
import java.util.Locale

/** Small standards-based vector PDF writer; no screen capture, network or Android graphics dependency. */
object PdfExport {
    private val encoding=Charset.forName("windows-1252")
    fun write(sheets: List<Sheet>, output: OutputStream) {
        val objects=mutableListOf<ByteArray>()
        fun add(s:String): Int { objects.add(s.toByteArray(encoding)); return objects.size }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("")
        val regular=add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        val bold=add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        val pages=mutableListOf<Int>()
        sheets.forEach { sheet ->
            val width=if(sheet.landscape) 841.889764 else 595.275591
            val height=if(sheet.landscape) 595.275591 else 841.889764
            val x=mutableListOf(28.35); sheet.widths.forEach { x.add(x.last()+it) }
            val y=mutableListOf(28.35); sheet.heights.forEach { y.add(y.last()+it) }
            require(x.last()<=width-22 && y.last()<=height-22) { "Tabel melewati margin A4." }
            val stream=StringBuilder("0 G 0 g 0.6 w\n")
            sheet.cells.forEach { cell ->
                val left=x[cell.col]; val top=y[cell.row]; val w=x[cell.col+cell.cols]-left; val h=y[cell.row+cell.rows]-top
                val edges=cell.edges
                if(cell.border) {
                    if(edges and 1 !=0) stream.append("${d(left)} ${d(height-top)} m ${d(left)} ${d(height-top-h)} l S\n")
                    if(edges and 2 !=0) stream.append("${d(left)} ${d(height-top)} m ${d(left+w)} ${d(height-top)} l S\n")
                    if(edges and 4 !=0) stream.append("${d(left+w)} ${d(height-top)} m ${d(left+w)} ${d(height-top-h)} l S\n")
                    if(edges and 8 !=0) stream.append("${d(left)} ${d(height-top-h)} m ${d(left+w)} ${d(height-top-h)} l S\n")
                }
                val text=cell.number?.let { if(cell.money) "Rp "+NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID")).format(it) else it.toString() } ?: cell.text
                if(text.isNotEmpty()) {
                    var font=cell.size.toDouble(); var lines=wrap(text,w-6,font)
                    while((lines.size*font*1.12>h-2 || lines.any { estimate(it,font)>w-6 }) && font>6.0) { font-=0.25; lines=wrap(text,w-6,font) }
                    require(lines.size*font*1.12 <= h-1 && lines.all { estimate(it,font)<=w-4 }) { "Teks terlalu panjang untuk formulir A4: ${text.take(40)}. Pendekkan teks atau alamat." }
                    val textTop=top+(h-lines.size*font*1.12)/2+font
                    lines.forEachIndexed { index,line ->
                        val xx=if(cell.center) left+(w-estimate(line,font))/2 else left+3
                        val yy=height-(textTop+index*font*1.12)
                        stream.append("BT /F${if(cell.bold) 2 else 1} ${d(font)} Tf 1 0 0 1 ${d(xx)} ${d(yy)} Tm (${escape(line)}) Tj ET\n")
                    }
                }
            }
            val bytes=stream.toString().toByteArray(encoding)
            val content=add("<< /Length ${bytes.size} >>\nstream\n${stream}endstream")
            pages.add(add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${d(width)} ${d(height)}] /Resources << /Font << /F1 $regular 0 R /F2 $bold 0 R >> >> /Contents $content 0 R >>"))
        }
        objects[1]="<< /Type /Pages /Kids [${pages.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>".toByteArray(encoding)
        val bytes=ByteArrayOutputStream(); bytes.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(encoding)); val offsets=mutableListOf<Int>()
        objects.forEachIndexed { i,obj -> offsets.add(bytes.size()); bytes.write("${i+1} 0 obj\n".toByteArray()); bytes.write(obj); bytes.write("\nendobj\n".toByteArray()) }
        val xref=bytes.size(); bytes.write("xref\n0 ${objects.size+1}\n0000000000 65535 f \n".toByteArray())
        offsets.forEach { bytes.write(String.format(Locale.US,"%010d 00000 n \n",it).toByteArray()) }
        bytes.write("trailer\n<< /Size ${objects.size+1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray()); bytes.writeTo(output)
    }
    private fun escape(s:String)=s.replace("\\","\\\\").replace("(","\\(").replace(")","\\)")
    private fun d(n:Double)=String.format(Locale.US,"%.4f",n)
    // Conservative Helvetica widths keep lines within cells; no clipping or silent truncation.
    private fun estimate(s:String,font:Double)=s.sumOf { ch -> when(ch) { 'i','l','I','.',',',':',' ','!' -> 0.28; 'm','w','M','W'->0.87; else->0.57 } }*font
    private fun wrap(s:String,width:Double,font:Double):List<String> = s.lines().flatMap { paragraph ->
        val result=mutableListOf<String>(); var line=""
        paragraph.split(" ").forEach { word ->
            if(estimate(if(line.isEmpty()) word else "$line $word",font)<=width) line=if(line.isEmpty()) word else "$line $word"
            else { if(line.isNotEmpty()) result.add(line); line=""; word.forEach { ch -> if(estimate(line+ch,font)>width && line.isNotEmpty()) { result.add(line); line="" }; line+=ch } }
        }
        if(line.isNotEmpty() || result.isEmpty()) result.add(line)
        result
    }
}
