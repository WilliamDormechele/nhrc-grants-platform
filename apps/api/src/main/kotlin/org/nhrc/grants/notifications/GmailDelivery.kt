package org.nhrc.grants.notifications

import org.nhrc.grants.config.PlatformIdentity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.ByteArrayResource
import jakarta.mail.internet.InternetAddress
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object MailAddress {
 fun deliverable(address:String):Boolean {
  if(!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(address))return false
  val domain=address.substringAfterLast('@').lowercase()
  return domain !in setOf("example.com","example.org","example.net") && listOf("local","system","invalid","test","localhost").none {domain==it || domain.endsWith(".$it")}
 }
}

@Service
class GmailDelivery(private val jdbc:JdbcTemplate,private val capture:JavaMailSender,private val emails:NotificationEmails) {
 companion object {const val ACCOUNT="williamdormechele@gmail.com"}
 private val directory=Path.of(System.getenv("NHRC_MAIL_CONFIG_DIRECTORY")?:"/app/mail-config")
 private val vault=MailSecretVault(directory)
 private fun config()=jdbc.queryForMap("select * from email_delivery_settings where id=true")
 fun mode()=config()["mode"].toString()
 fun details():Map<String,Any?> {val c=config();val recipients=jdbc.queryForList("select email from users where active",String::class.java);return mapOf("mode" to c["mode"],"sender" to ACCOUNT,"configured" to (c["encrypted_password"]!=null),"testedAt" to c["tested_at"],"dailyLimit" to if(c["mode"]=="BREVO")300 else 450,"brevoConfigured" to (c["brevo_encrypted_key"]!=null),"brevoTestedAt" to c["brevo_tested_at"],"sentToday" to sentCount(c["mode"].toString()),"eligibleRecipients" to recipients.count(MailAddress::deliverable),"addressesToUpdate" to recipients.count{!MailAddress.deliverable(it)})}
 private fun sentCount(provider:String="GMAIL")=jdbc.queryForObject("select count(*) from email_outbox where delivery_mode=? and sent_at>now()-interval '24 hours'",Long::class.java,provider)?:0L
 private fun smtp(password:String):JavaMailSenderImpl=JavaMailSenderImpl().apply {
  host="smtp.gmail.com";port=587;username=ACCOUNT;this.password=password;defaultEncoding="UTF-8"
  javaMailProperties.putAll(mapOf("mail.smtp.auth" to "true","mail.smtp.starttls.enable" to "true","mail.smtp.starttls.required" to "true","mail.smtp.ssl.checkserveridentity" to "true","mail.smtp.connectiontimeout" to "10000","mail.smtp.timeout" to "10000","mail.smtp.writetimeout" to "10000"))
 }
 private fun message(transport:JavaMailSender,recipient:String,content:BrandedEmail,local:Boolean=false):jakarta.mail.internet.MimeMessage {
  val rendered=EmailTemplates.render(content);val mime=transport.createMimeMessage();val helper=MimeMessageHelper(mime,true,"UTF-8")
  helper.setFrom(InternetAddress(if(local)"grants@nhrc.local" else ACCOUNT,"NHRC eGMS","UTF-8"));helper.setReplyTo(ACCOUNT);helper.setTo(recipient);helper.setSubject(rendered.subject);helper.setText(rendered.plain,rendered.html)
  val logo=ClassPathResource("branding/nhrc-logo.png").inputStream.use{it.readBytes()};helper.addInline("nhrc-logo",ByteArrayResource(logo),"image/png");return mime
 }
 @Synchronized fun connect(password:String) {
  val clean=password.replace(" ","");require(Regex("[A-Za-z]{16}").matches(clean)){"Enter the 16-letter Google app password, not your Google account password"}
  try {val transport=smtp(clean);transport.testConnection();transport.send(message(transport,ACCOUNT,emails.sample(false).let{it.copy(subject="NHRC eGMS | Email connection test",heading="Email connection test",message="Google accepted the NHRC eGMS connection request. This message tests the branded email layout and authenticated delivery.",details=listOf("Sender" to ACCOUNT,"Purpose" to "Email connection test"),nextStep="Check this message, then review your delivery settings before enabling live messages.",actionLabel="Open NHRC eGMS",actionUrl=it.portalUrl,secondaryUrl=null)}))}catch(ex:Exception){throw ResponseStatusException(HttpStatus.BAD_GATEWAY,"Google did not accept the connection or test message. Check your app password and network connection.")}
  jdbc.update("update email_delivery_settings set encrypted_password=?,tested_at=now(),mode='LOCAL_CAPTURE',updated_at=now() where id=true",vault.encrypt(clean))
 }
 private fun brevoSend(key:String,recipient:String,content:BrandedEmail) {
  val rendered=EmailTemplates.render(content)
  val payload=mapOf("sender" to mapOf("email" to ACCOUNT,"name" to "NHRC eGMS"),"to" to listOf(mapOf("email" to recipient)),"replyTo" to mapOf("email" to ACCOUNT),"subject" to rendered.subject,"htmlContent" to rendered.html.replace("cid:nhrc-logo",content.portalUrl.trimEnd('/')+"/nhrc-logo.png"),"textContent" to rendered.plain)
  val request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.brevo.com/v3/smtp/email")).timeout(java.time.Duration.ofSeconds(20)).header("api-key",key).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload))).build()
  val response=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build().send(request,java.net.http.HttpResponse.BodyHandlers.ofString())
  if(response.statusCode() !in 200..299)throw ResponseStatusException(HttpStatus.BAD_GATEWAY,"Brevo did not accept the message. Verify the API key, sender verification and account sending allowance in Brevo.")
 }
 @Synchronized fun connectBrevo(key:String) {
  require(key.length in 20..1000 && key.none{it.code==10 || it.code==13}){"Enter a valid Brevo API key"}
  brevoSend(key,ACCOUNT,emails.sample(false).let{it.copy(subject="NHRC eGMS | Brevo connection test",heading="Brevo connection test",message="Brevo accepted the NHRC eGMS HTTPS delivery request. This tests the branded template and sender connection.",details=listOf("Sender" to ACCOUNT,"Transport" to "Brevo HTTPS API"),nextStep="Check your inbox before enabling live delivery.",actionLabel="Open NHRC eGMS",actionUrl=it.portalUrl,secondaryUrl=null)})
  jdbc.update("update email_delivery_settings set brevo_encrypted_key=?,brevo_tested_at=now(),updated_at=now() where id=true",vault.encrypt(key))
 }
 fun enable(enabled:Boolean,provider:String="GMAIL") {
  require(provider in setOf("GMAIL","BREVO"));val c=config()
  if(enabled && (if(provider=="BREVO") c["brevo_tested_at"]==null || c["brevo_encrypted_key"]==null else c["tested_at"]==null || c["encrypted_password"]==null))throw ResponseStatusException(HttpStatus.CONFLICT,"Connect the selected provider and send a successful test before enabling live email")
  jdbc.update("update email_delivery_settings set mode=?,updated_at=now() where id=true",if(enabled)provider else "LOCAL_CAPTURE")
 }

 fun deliver(r:Map<String,Any?>):Boolean {
  val eligible=jdbc.queryForObject("""select exists(select 1 from notifications n join users u on u.id=n.user_id join user_roles ur on ur.user_id=u.id join roles ro on ro.id=ur.role_id join communication_policies p on p.event_code=case when n.type='MANUAL_REMINDER' then 'REMINDER' when n.type='NOTICE' then 'ANNOUNCEMENT' else n.type end where n.id=? and u.active and lower(u.email)=lower(?) and p.email and p.recipient_roles ?? ro.code and (ur.valid_from is null or ur.valid_from<=now()) and (ur.valid_until is null or ur.valid_until>=now()))""",Boolean::class.java,r["notification_id"],r["recipient"])==true
  if(!eligible){jdbc.update("update email_outbox set status='CANCELLED',last_error='Recipient or email policy is no longer eligible' where id=?",r["id"]);return false}
  val staleReminder=jdbc.queryForObject("""select exists(select 1 from notifications n where n.id=? and n.type in ('REMINDER','MANUAL_REMINDER') and not exists(select 1 from lifecycle_tasks t where t.kind=n.entity_type and t.id=n.entity_id and (t.owner_id=n.user_id or t.owner_id is null and exists(select 1 from user_roles ur join roles ro on ro.id=ur.role_id where ur.user_id=n.user_id and ro.code=t.responsible_role and (ur.valid_from is null or ur.valid_from<=now()) and (ur.valid_until is null or ur.valid_until>=now())))))""",Boolean::class.java,r["notification_id"])==true
  if(staleReminder){jdbc.update("update email_outbox set status='CANCELLED',last_error='Task completed or responsibility changed before delivery' where id=?",r["id"]);return false}
  if(r["delivery_mode"]=="BREVO") {
   if(mode()!="BREVO")return false
   if(!MailAddress.deliverable(r["recipient"].toString())){jdbc.update("update email_outbox set status='BLOCKED_RECIPIENT',last_error='Replace the local test address with an actual user email address' where id=?",r["id"]);return false}
   if(sentCount("BREVO")>=300){jdbc.update("update email_outbox set next_attempt_at=now()+interval '1 hour',last_error='Brevo application allowance reached; delivery deferred' where id=?",r["id"]);return false}
   brevoSend(vault.decrypt(config()["brevo_encrypted_key"].toString()),r["recipient"].toString(),emails.content(r));return true
  }
  val live=r["delivery_mode"]=="GMAIL"
  if(live) {
   if(mode()!="GMAIL")return false
   val email=r["recipient"].toString()
   if(!MailAddress.deliverable(email)){jdbc.update("update email_outbox set status='BLOCKED_RECIPIENT',last_error='Replace the local test address with an actual user email address' where id=?",r["id"]);return false}
   val current=jdbc.queryForObject("select exists(select 1 from notifications n join users u on u.id=n.user_id where n.id=? and u.active and lower(u.email)=lower(?))",Boolean::class.java,r["notification_id"],email)==true
   if(!current){jdbc.update("update email_outbox set status='CANCELLED',last_error='Recipient account or address changed' where id=?",r["id"]);return false}
   if(sentCount()>=450){jdbc.update("update email_outbox set next_attempt_at=now()+interval '1 hour',last_error='Daily sending allowance reached; delivery deferred' where id=?",r["id"]);return false}
   val transport=smtp(vault.decrypt(config()["encrypted_password"].toString()));transport.send(message(transport,email,emails.content(r)))
  }else {capture.send(message(capture,r["recipient"].toString(),emails.content(r),true))}
  return true
 }
}

data class GmailConnection(val appPassword:String,val reason:String)
data class GmailMode(val enabled:Boolean,val reason:String,val provider:String="GMAIL")
data class BrevoConnection(val apiKey:String,val reason:String)
@RestController
@RequestMapping("/api/operations/communications/email-delivery")
class GmailDeliveryController(private val delivery:GmailDelivery,private val identity:PlatformIdentity,private val jdbc:JdbcTemplate,private val emails:NotificationEmails) {
 private fun allowed(){if("SUPERADMIN" !in identity.current().roles)throw ResponseStatusException(HttpStatus.FORBIDDEN,"Superadmin is required")}
 private fun audit(action:String,reason:String){jdbc.update("insert into operational_history(dataset,record_id,actor_id,action,note) values ('email_delivery',?,?,?,?)",UUID.nameUUIDFromBytes("email_delivery".toByteArray()),identity.current().id,action,reason)}
 @GetMapping fun read():Map<String,Any?> {allowed();return delivery.details()}
 @GetMapping("/preview") fun preview(@RequestParam(defaultValue="notification") kind:String):Map<String,String> {allowed();require(kind in setOf("notification","onboarding"));val r=EmailTemplates.render(emails.sample(kind=="onboarding"));val logo=ClassPathResource("branding/nhrc-logo.png").inputStream.use{Base64.getEncoder().encodeToString(it.readBytes())};return mapOf("subject" to r.subject,"plain" to r.plain,"html" to r.html.replace("cid:nhrc-logo","data:image/png;base64,$logo"))}
 @PostMapping("/connect") fun connect(@RequestBody r:GmailConnection):Map<String,Any?> {allowed();require(r.reason.isNotBlank() && r.reason.length<=2000);delivery.connect(r.appPassword);audit("GMAIL_CONNECTION_TESTED",r.reason);return delivery.details()}
 @PostMapping("/brevo/connect") fun connectBrevo(@RequestBody r:BrevoConnection):Map<String,Any?> {allowed();require(r.reason.isNotBlank() && r.reason.length<=2000);delivery.connectBrevo(r.apiKey);audit("BREVO_CONNECTION_TESTED",r.reason);return delivery.details()}
 @PutMapping("/mode") fun mode(@RequestBody r:GmailMode):Map<String,Any?> {allowed();require(r.reason.isNotBlank() && r.reason.length<=2000);delivery.enable(r.enabled,r.provider);audit(if(r.enabled)"LIVE_EMAIL_ENABLED" else "LIVE_EMAIL_PAUSED",r.reason);return delivery.details()}
}

internal class MailSecretVault(private val directory:Path) {
 private fun key():ByteArray {
  Files.createDirectories(directory);val path=directory.resolve("encryption.key")
  if(!Files.exists(path)) {val bytes=ByteArray(32);SecureRandom().nextBytes(bytes);Files.write(path,bytes,java.nio.file.StandardOpenOption.CREATE_NEW);runCatching{Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))}}
  return Files.readAllBytes(path)
 }
 fun encrypt(value:String):String {val iv=ByteArray(12);SecureRandom().nextBytes(iv);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key(),"AES"),GCMParameterSpec(128,iv));return Base64.getEncoder().encodeToString(iv+c.doFinal(value.toByteArray(Charsets.UTF_8)))}
 fun decrypt(value:String):String {val bytes=Base64.getDecoder().decode(value);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key(),"AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12)));return String(c.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)}
}
