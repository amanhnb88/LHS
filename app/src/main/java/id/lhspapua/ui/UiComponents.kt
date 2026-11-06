@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.ExperimentalFoundationApi::class)
package id.lhspapua.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import id.lhspapua.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

fun <T> List<T>.replace(index:Int,value:T)=mapIndexed { i,v->if(i==index)value else v }
data class FieldFocus(val tag:String="",val serial:Long=0)
internal val LocalFieldFocus=staticCompositionLocalOf { FieldFocus() }
internal val LocalInputLocked=staticCompositionLocalOf { false }
internal val LocalInputCommit=staticCompositionLocalOf<()->Unit> { {} }

@Composable internal fun SalesTheme(content:@Composable ()->Unit) {
    val colors=if(isSystemInDarkTheme()) darkColorScheme(
        primary=Color(0xFF86D7C5),onPrimary=Color(0xFF00382F),primaryContainer=Color(0xFF174C41),
        onPrimaryContainer=Color(0xFFC2F4E8),background=Color(0xFF101815),surface=Color(0xFF16211D),
        surfaceVariant=Color(0xFF273731),onSurfaceVariant=Color(0xFFBCCDC6)
    ) else lightColorScheme(
        primary=Color(0xFF00695C),onPrimary=Color.White,primaryContainer=Color(0xFFE1F0EA),
        secondary=Color(0xFF486459),background=Color(0xFFF7F9F8),surface=Color.White,
        onSurface=Color(0xFF193A32),onBackground=Color(0xFF193A32)
    )
    MaterialTheme(colorScheme=colors,content=content)
}
@Composable internal fun ScrollPage(pageKey:Any=Unit,content:@Composable ColumnScope.()->Unit) {
    key(pageKey) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { content();Spacer(Modifier.height(16.dp)) } }
}
@Composable internal fun FlowFrame(
    pageKey:Any,state:UiState,vm:LhsViewModel,primary:String,onPrimary:()->Unit,
    secondary:String?=null,onSecondary:()->Unit={},enabled:Boolean=true,scroll:ScrollState?=null,
    header:@Composable ()->Unit={},content:@Composable ColumnScope.()->Unit
) {
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val imeOpen=WindowInsets.ime.getBottom(LocalDensity.current)>0
    Column(Modifier.fillMaxSize()) {
        if(!imeOpen)header()
        key(pageKey) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll ?: rememberScrollState()).padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)) { content();Spacer(Modifier.height(16.dp)) }
        }
        if(!imeOpen)Surface(shadowElevation=2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)) {
                SaveStatus(state,vm)
                Button(onClick={ focus.clearFocus();keyboard?.hide();onPrimary() },enabled=enabled&&!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(primary) }
                if(secondary!=null)TextButton(onClick={ focus.clearFocus();onSecondary() },modifier=Modifier.fillMaxWidth()) { Text(secondary) }
            }
        }
    }
}
@Composable internal fun SaveStatus(state:UiState,vm:LhsViewModel) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(state.saved,style=MaterialTheme.typography.labelMedium,color=if(state.saveFailed)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
        if(state.saveFailed)TextButton(onClick=vm::retrySave) { Text("Coba lagi") }
    }
}
@Composable internal fun Panel(title:String,content:@Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(title.isNotBlank())Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            content()
        }
    }
}
@Composable internal fun Notice(text:String,error:Boolean=false) {
    Surface(color=if(error)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.small) {
        Text(text,Modifier.fillMaxWidth().padding(12.dp),style=MaterialTheme.typography.bodyMedium)
    }
}
@Composable internal fun TotalLine(label:String,value:String) { Text("$label: $value",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold) }
@Composable internal fun Confirmation(title:String,message:String,accept:String="Lanjutkan",onAccept:()->Unit,onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={ Text(title) },text={ Text(message) },confirmButton={ TextButton(onClick=onAccept) { Text(accept) } },dismissButton={ TextButton(onClick=onDismiss) { Text("Batal") } })
}
@Composable internal fun ConfirmButton(label:String,message:String,action:()->Unit) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick={ show=true }) { Text(label) }
    if(show)Confirmation(label,message,label,{ show=false;action() },{ show=false })
}
@Composable internal fun ProductTabs(selected:Int,onSelect:(Int)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) { Product.entries.forEachIndexed { p,product->
        FilterChip(selected=selected==p,onClick={ onSelect(p) },label={ Text(product.label,style=MaterialTheme.typography.labelMedium) },modifier=Modifier.weight(1f))
    } }
}

/** Whole-number paste normalization; decimal quantities are rejected rather than silently changed. */
object NumberInput {
    fun normalize(raw:String,money:Boolean=false,signed:Boolean=false):String? {
        var text=raw.trim()
        if(money)text=text.replace(Regex("(?i)^rp\\s*"),"").trim()
        if(text.isBlank())return ""
        if(signed && text=="-")return "-"
        if(text.contains('.')) {
            if(!text.matches(Regex("-?\\d{1,3}(\\.\\d{3})+")))return null
            text=text.replace(".","")
        }
        if(!text.matches(Regex(if(signed)"-?\\d+" else "\\d+")))return null
        val value=text.toLongOrNull() ?: return null
        if(!(if(signed)Calculation.validSigned(value) else Calculation.validInput(value)))return null
        return text
    }
}
internal object RupiahTransformation:VisualTransformation {
    override fun filter(text:AnnotatedString):TransformedText {
        val s=text.text;val negative=s.startsWith('-');val digits=s.removePrefix("-")
        val grouped=(if(negative)"-" else "")+digits.reversed().chunked(3).joinToString(".").reversed()
        return TransformedText(AnnotatedString(grouped),object:OffsetMapping {
            override fun originalToTransformed(offset:Int):Int {
                if(offset==0)return 0
                val n=(offset-if(negative)1 else 0).coerceAtLeast(0)
                return ((if(negative)1 else 0)+n+(digits.length-1).coerceAtLeast(0)/3-(digits.length-n)/3).coerceIn(0,grouped.length)
            }
            override fun transformedToOriginal(offset:Int)=grouped.take(offset).count { it!='.' }.coerceIn(0,s.length)
        })
    }
}
@Composable private fun fieldAccess(tag:String):Modifier {
    val request=LocalFieldFocus.current
    val commit=LocalInputCommit.current
    val requester=remember(tag) { FocusRequester() }
    val bring=remember(tag) { BringIntoViewRequester() }
    val scope=rememberCoroutineScope()
    var focused by remember(tag) { mutableStateOf(false) }
    val imeOpen=WindowInsets.ime.getBottom(LocalDensity.current)>0
    LaunchedEffect(imeOpen,focused) {
        if(imeOpen && focused) { delay(180);bring.bringIntoView() }
    }
    LaunchedEffect(request,tag) { if(request.tag==tag) { delay(60);runCatching { requester.requestFocus() };bring.bringIntoView() } }
    return Modifier.focusRequester(requester).bringIntoViewRequester(bring).onFocusChanged { f->
        if(focused&&!f.isFocused)commit()
        focused=f.isFocused
        if(f.isFocused)scope.launch { delay(100);bring.bringIntoView() }
    }
}
@Composable internal fun Numeric(label:String,value:Long?,money:Boolean=false,signed:Boolean=false,modifier:Modifier=Modifier,tag:String=label,readonly:Boolean=false,compact:Boolean=false,error:String?=null,done:Boolean=false,onChange:(Long?)->Unit={}) {
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current;val commit=LocalInputCommit.current
    var editor by remember(tag) { mutableStateOf(TextFieldValue(value?.toString() ?: "")) }
    var invalid by remember(tag) { mutableStateOf(false) }
    LaunchedEffect(value,tag) {
        if(editor.text.toLongOrNull()!=value || (value==null && editor.text.isNotEmpty() && editor.text!="-"))editor=TextFieldValue(value?.toString() ?: "")
    }
    OutlinedTextField(value=editor,onValueChange={ candidate->
        val normalized=NumberInput.normalize(candidate.text,money,signed)
        if(normalized==null)invalid=true else {
            invalid=false
            editor=if(normalized==candidate.text)candidate else TextFieldValue(normalized,TextRange(normalized.length))
            onChange(normalized.toLongOrNull())
        }
    },label=if(compact)null else ({ Text(label,style=MaterialTheme.typography.bodySmall) }),
        placeholder={ Text(if(readonly)"—" else if(money&&label.startsWith("Harga"))"Belum diatur" else "—") },
        modifier=modifier.fillMaxWidth().then(fieldAccess(tag)).testTag(tag).semantics { contentDescription=label },
        readOnly=readonly||LocalInputLocked.current,singleLine=true,isError=invalid||error!=null,
        prefix=if(money)({ Text("Rp ") })else null,
        trailingIcon=if(signed&&!readonly)({ TextButton(enabled=value!=null,onClick={ value?.let { onChange(-it) } }) { Text("±") } })else null,
        supportingText=when { invalid->({ Text("Gunakan angka bulat, maksimal 15 digit.") });error!=null->({ Text(error) });else->null },
        visualTransformation=if(money)RupiahTransformation else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number,imeAction=if(done)ImeAction.Done else ImeAction.Next),
        keyboardActions=KeyboardActions(onNext={ if(!focus.moveFocus(FocusDirection.Next)) { focus.clearFocus();keyboard?.hide() };commit() },onDone={ focus.clearFocus();keyboard?.hide();commit() }))
}
@Composable internal fun TextInput(label:String,value:String,multiline:Boolean=false,tag:String=label,error:String?=null,done:Boolean=false,onChange:(String)->Unit) {
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current;val commit=LocalInputCommit.current
    OutlinedTextField(value=value,onValueChange=onChange,readOnly=LocalInputLocked.current,label={ Text(label) },singleLine=!multiline,minLines=if(multiline)2 else 1,
        modifier=Modifier.fillMaxWidth().then(fieldAccess(tag)).testTag(tag),isError=error!=null,supportingText=error?.let { { Text(it) } },
        keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences,imeAction=if(multiline)ImeAction.Default else if(done)ImeAction.Done else ImeAction.Next),
        keyboardActions=KeyboardActions(onNext={ focus.moveFocus(FocusDirection.Next);commit() },onDone={ focus.clearFocus();keyboard?.hide();commit() }))
}
@Composable internal fun DateButton(i:Identity,change:(String)->Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick={ show=true },enabled=!LocalInputLocked.current) { Text("${i.day()}, ${i.displayDate()}") }
    if(show) {
        val date=rememberDatePickerState(initialSelectedDateMillis=LocalDate.parse(i.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={ show=false },confirmButton={ TextButton(onClick={ date.selectedDateMillis?.let { change(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) };show=false }) { Text("Pilih") } },dismissButton={ TextButton(onClick={ show=false }) { Text("Batal") } }) { DatePicker(date) }
    }
}
@Composable internal fun IdentityEditor(i:Identity,profile:Boolean=false,salesSuggestions:List<String> = emptyList(),onChange:(Identity)->Unit) {
    if(!profile)DateButton(i) { onChange(i.copy(date=it)) }
    TextInput("Nama Sales",i.sales) { onChange(i.copy(sales=it)) }
    TextInput("Join",i.join) { onChange(i.copy(join=it)) }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { TextInput("Area",i.area) { onChange(i.copy(area=it)) } }
        Box(Modifier.weight(1f)) { TextInput("Rute",i.route) { onChange(i.copy(route=it)) } }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { TextInput("Kendaraan",i.vehicle) { onChange(i.copy(vehicle=it)) } }
        Box(Modifier.weight(1f)) { TextInput("Nopol",i.plate,done=true) { onChange(i.copy(plate=it)) } }
    }
}

@Composable internal fun MainCard(title:String,description:String,action:()->Unit) {
    Surface(onClick=action,modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            Text(description,style=MaterialTheme.typography.bodySmall)
        }
    }
}
