package id.lhspapua.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Native ECMA-376 OOXML workbook, typed cells, merges, formula caches, print settings. */
object ExcelExport {
    fun write(sheets: List<Sheet>, output: OutputStream) {
        val styles=mutableListOf(Cell(0,0,border=false))
        fun style(c:Cell): Int { val key=c.copy(row=0,col=0,rows=1,cols=1,text="",number=null,formula=null); val i=styles.indexOf(key); if(i>=0)return i; styles.add(key); return styles.lastIndex }
        sheets.forEach { sheet -> sheet.cells.forEach { c ->
            style(c)
            for(row in c.row until c.row+c.rows) for(col in c.col until c.col+c.cols) {
                val edge=(if(col==c.col) c.edges and 1 else 0) or (if(col==c.col+c.cols-1) c.edges and 4 else 0) or (if(row==c.row) c.edges and 2 else 0) or (if(row==c.row+c.rows-1) c.edges and 8 else 0)
                style(c.copy(edges=edge))
            }
        } }
        ZipOutputStream(output).use { zip ->
            fun part(path:String,text:String) { zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            part("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it+1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }}</Types>""")
            part("_rels/.rels","""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            part("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView/></bookViews><sheets>${sheets.mapIndexed { i,s -> "<sheet name=\"${xml(s.name)}\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>" }.joinToString("")}</sheets><definedNames>${sheets.mapIndexed { i,s -> "<definedName name=\"_xlnm.Print_Area\" localSheetId=\"$i\">'${xml(s.name)}'!\$A\$1:\$${Sheet.column(s.widths.lastIndex)}\$${s.heights.size}</definedName>" }.joinToString("")}</definedNames><calcPr calcId="191029" fullCalcOnLoad="1" forceFullCalc="1" calcMode="auto"/></workbook>""")
            part("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${sheets.indices.joinToString("") { "<Relationship Id=\"rId${it+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it+1}.xml\"/>" }}<Relationship Id="rStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            val fonts=(listOf(false to 9f)+styles.map { it.bold to it.size }).distinct()
            val borders=styles.map { if(it.border) it.edges else 0 }.distinct()
            part("xl/styles.xml", """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="&quot;Rp &quot;#,##0"/></numFmts><fonts count="${fonts.size}">${fonts.joinToString("") { (b,size) -> "<font><sz val=\"$size\"/><name val=\"Arial\"/>${if(b) "<b/>" else ""}</font>" }}</fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="${borders.size}">${borders.joinToString("") { mask -> "<border>"+listOf("left" to 1,"right" to 4,"top" to 2,"bottom" to 8).joinToString("") { (side,bit) -> if(mask and bit != 0) "<$side style=\"thin\"><color rgb=\"FF000000\"/></$side>" else "<$side/>" }+"<diagonal/></border>" }}</borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="${styles.size}">${styles.joinToString("") { c -> "<xf numFmtId=\"${if(c.money) 164 else 0}\" fontId=\"${fonts.indexOf(c.bold to c.size)}\" fillId=\"0\" borderId=\"${borders.indexOf(if(c.border)c.edges else 0)}\" xfId=\"0\" applyAlignment=\"1\" applyNumberFormat=\"1\"><alignment horizontal=\"${if(c.center) "center" else if(c.money) "right" else "left"}\" vertical=\"center\" wrapText=\"1\"/></xf>" }}</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            sheets.forEachIndexed { index,s ->
                val expanded=mutableMapOf<Pair<Int,Int>,Pair<Cell,Boolean>>()
                s.cells.forEach { c ->
                    for(row in c.row until c.row+c.rows) for(col in c.col until c.col+c.cols) {
                        check(!expanded.containsKey(row to col)) { "Tumpang tindih cell $row,$col" }
                        val edge=(if(col==c.col) c.edges and 1 else 0) or (if(col==c.col+c.cols-1) c.edges and 4 else 0) or (if(row==c.row) c.edges and 2 else 0) or (if(row==c.row+c.rows-1) c.edges and 8 else 0)
                        expanded[row to col]=c.copy(edges=edge) to (row==c.row && col==c.col)
                        style(c.copy(edges=edge))
                    }
                }
                val rows=s.heights.mapIndexed { r,h ->
                    "<row r=\"${r+1}\" ht=\"$h\" customHeight=\"1\">"+s.widths.indices.mapNotNull { col ->
                        expanded[r to col]?.let { (c,anchor) ->
                            val ref=s.ref(r,col); val attr="r=\"$ref\" s=\"${style(c)}\""
                            if(!anchor) "<c $attr/>" else when {
                                c.formula!=null -> "<c $attr${if(c.number==null) " t=\"str\"" else ""}><f>${xml(c.formula)}</f><v>${c.number ?: ""}</v></c>"
                                c.number!=null -> "<c $attr><v>${c.number}</v></c>"
                                c.text.isNotEmpty() -> "<c $attr t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(c.text)}</t></is></c>"
                                else -> "<c $attr/>"
                            }
                        }
                    }.joinToString("")+"</row>"
                }.joinToString("")
                val merges=s.cells.filter { it.rows>1||it.cols>1 }.joinToString("") { c -> "<mergeCell ref=\"${s.ref(c.row,c.col)}:${s.ref(c.row+c.rows-1,c.col+c.cols-1)}\"/>" }
                part("xl/worksheets/sheet${index+1}.xml", """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetPr><pageSetUpPr fitToPage="1"/></sheetPr><dimension ref="A1:${s.ref(s.heights.lastIndex,s.widths.lastIndex)}"/><sheetViews><sheetView workbookViewId="0" showGridLines="0"/></sheetViews><sheetFormatPr defaultRowHeight="15"/><cols>${s.widths.mapIndexed { c,w -> "<col min=\"${c+1}\" max=\"${c+1}\" width=\"${if(w<9.0) w/9.0 else (w-3.75)/5.25}\" customWidth=\"1\"/>" }.joinToString("")}</cols><sheetData>$rows</sheetData><mergeCells count="${s.cells.count { it.rows>1||it.cols>1 }}">$merges</mergeCells><printOptions horizontalCentered="1"/><pageMargins left="0.3937" right="0.3937" top="0.3937" bottom="0.3937" header="0" footer="0"/><pageSetup paperSize="9" orientation="${if(s.landscape) "landscape" else "portrait"}" fitToWidth="1" fitToHeight="1"/></worksheet>""")
            }
            // Border variants for constituent merged cells were collected above; styles must include them.
            // styles.xml is written after expansion in the public preparation step below.
        }
    }
    private fun xml(s:String)=s.filter { it=='\n'||it=='\r'||it=='\t'||it.code>=32 }.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
}
