package org.nhrc.grants.operations

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object DocumentDownloads {
 private val types=mapOf("pdf" to "application/pdf","docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document","xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","csv" to "text/csv; charset=UTF-8","txt" to "text/plain; charset=UTF-8","png" to "image/png","jpg" to "image/jpeg","jpeg" to "image/jpeg")
 fun response(name:String,bytes:ByteArray,original:Boolean):ResponseEntity<ByteArray> {
  val suffix=name.substringAfterLast('.').lowercase();val convert=suffix=="txt" && !original
  val filename=if(convert)name.substringBeforeLast('.')+".pdf" else name
  return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename,StandardCharsets.UTF_8).build().toString()).header(HttpHeaders.CONTENT_TYPE,if(convert)"application/pdf" else types[suffix]?:"application/octet-stream").header("X-Content-Type-Options","nosniff").header("Cache-Control","private, no-store").body(if(convert)pdf(name,String(bytes,Charsets.UTF_8)) else bytes)
 }
 fun pdf(name:String,text:String):ByteArray {
  PDDocument().use { doc ->
   val font=DocumentDownloads::class.java.getResourceAsStream("/fonts/NotoSans-Regular.ttf").use {PDType0Font.load(doc,it)}
   fun printable(value:String)=buildString {value.codePoints().forEach {cp->if(cp==9)append("    ") else if(cp>=32 && cp!=127) {val ch=String(Character.toChars(cp));append(if(runCatching{font.encode(ch)}.isSuccess)ch else "?")}}}
   val lines=mutableListOf<String>();val width=499f;val size=10f
   for(raw in text.replace("\r\n","\n").replace('\r','\n').split('\n')) {
    var line=""
    for(cp in printable(raw).codePoints().toArray()) {val ch=String(Character.toChars(cp));if(line.isNotEmpty() && font.getStringWidth(line+ch)/1000*size>width){lines.add(line);line=""};line+=ch}
    lines.add(line)
   }
   val chunks=lines.chunked(43).ifEmpty{listOf(listOf(""))}
   chunks.forEachIndexed {index,chunk ->
    val page=PDPage(PDRectangle.A4);doc.addPage(page)
    PDPageContentStream(doc,page).use {out ->
     fun line(value:String,x:Float,y:Float,pt:Float){out.beginText();out.setFont(font,pt);out.newLineAtOffset(x,y);out.showText(printable(value));out.endText()}
     line("NHRC eGMS",48f,795f,16f);line("Electronic Grant Management System",48f,774f,10f)
     val label=printable(name).take(80);line(label,48f,748f,9f)
     chunk.forEachIndexed {i,value->line(value,48f,719f-i*14f,size)}
     line("Document copy - original upload retained",48f,50f,8f);line("Page ${index+1} of ${chunks.size}",450f,50f,8f)
    }
   }
   doc.documentInformation.title=name;doc.documentInformation.creator="NHRC eGMS"
   return ByteArrayOutputStream().also {doc.save(it)}.toByteArray()
  }
 }
}
