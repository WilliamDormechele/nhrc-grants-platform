package org.nhrc.grants.operations

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.nhrc.grants.notifications.MailAddress

class Release03Test {
 @Test fun `text downloads are readable multipage PDFs with Unicode`() {
  val pdf=DocumentDownloads.pdf("Résumé evidence.txt",(1..120).joinToString("\n"){"Research evidence $it - café, £100 and €200"})
  assertTrue(String(pdf.take(5).toByteArray()).startsWith("%PDF-"))
  Loader.loadPDF(pdf).use {assertTrue(it.numberOfPages>=3);val text=PDFTextStripper().getText(it);assertTrue(text.contains("café"));assertTrue(text.contains("Research evidence 120"))}
 }
 @Test fun `original uploads preserve bytes and native document MIME types`() {
  val types=mapOf("pdf" to "application/pdf","docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document","xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","csv" to "text/csv","png" to "image/png","jpg" to "image/jpeg","txt" to "text/plain")
  types.forEach {(extension,mime)->val bytes=byteArrayOf(0,1,2,3);val r=DocumentDownloads.response("Evidence.$extension",bytes,true);assertArrayEquals(bytes,r.body);assertTrue(r.headers.getFirst("Content-Type")!!.startsWith(mime));assertTrue(r.headers.getFirst("Content-Disposition")!!.contains("Evidence.$extension"))}
 }
 @Test fun `only text is converted by default`() {val r=DocumentDownloads.response("notes.txt","Some evidence".toByteArray(),false);assertEquals("application/pdf",r.headers.getFirst("Content-Type"));assertTrue(r.headers.getFirst("Content-Disposition")!!.contains("notes.pdf"));val data=byteArrayOf(1,2);assertArrayEquals(data,DocumentDownloads.response("budget.xlsx",data,false).body)}
 @Test fun `test addresses cannot receive live Gmail messages`() {listOf("a@nhrc.local","a@nhrc.system","a@example.com","a@sub.example.test","bad").forEach{assertFalse(MailAddress.deliverable(it),it)};assertTrue(MailAddress.deliverable("williamdormechele@gmail.com"));assertTrue(MailAddress.deliverable("person@institution.org"))}
}
