package org.nhrc.grants.notifications

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

@Service
class AccountInvitations(private val jdbc:JdbcTemplate,private val encoder:PasswordEncoder,@Value("\${NHRC_MAIL_CONFIG_DIRECTORY:/app/mail-config}") directory:String) {
 private val vault=MailSecretVault(Path.of(directory))
 companion object {
  fun hash(token:String)=MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
  fun validPassword(password:String)=password.length>=12 && password.toByteArray(Charsets.UTF_8).size<=72
 }
 fun issue(user:UUID,actor:UUID):UUID {
  val bytes=ByteArray(32);SecureRandom().nextBytes(bytes);val token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);val id=UUID.randomUUID()
  jdbc.update("update account_invitations set revoked_at=now() where user_id=? and used_at is null and revoked_at is null",user)
  jdbc.update("insert into account_invitations(id,user_id,token_hash,encrypted_token,created_by,email_at_issue) select ?,?,?,?,?,email from users where id=?",id,user,hash(token),vault.encrypt(token),actor,user)
  jdbc.update("insert into notifications(user_id,type,title,message,entity_type,entity_id) values (?,'ACCOUNT','Welcome to NHRC eGMS','Your institutional account has been created. Set your password using the secure link in your welcome email, then review your assigned roles and workspace.','account_invitations',?)",user,id)
  return id
 }
 fun actionToken(id:UUID,user:UUID):String? {
  val row=jdbc.queryForList("select i.encrypted_token from account_invitations i join users u on u.id=i.user_id where i.id=? and i.user_id=? and i.used_at is null and i.revoked_at is null and i.expires_at>now() and u.active and lower(i.email_at_issue)=lower(u.email)",id,user).firstOrNull()?:return null
  return vault.decrypt(row["encrypted_token"].toString())
 }
 fun inspect(token:String):Map<String,Any?> {
  require(token.length in 40..100){"This setup link is invalid"}
  val r=jdbc.queryForList("select i.id,i.expires_at,u.display_name from account_invitations i join users u on u.id=i.user_id where i.token_hash=? and i.used_at is null and i.revoked_at is null and i.expires_at>now() and u.active and lower(i.email_at_issue)=lower(u.email)",hash(token)).firstOrNull()?:throw ResponseStatusException(HttpStatus.GONE,"This setup link has expired or has already been used. Contact your administrator for help.")
  return mapOf("name" to r["display_name"],"expiresAt" to r["expires_at"])
 }
 @Transactional fun accept(token:String,password:String):Map<String,Boolean> {
  require(token.length in 40..100 && validPassword(password)){"Use at least 12 characters, up to 72 UTF-8 bytes"}
  val candidate=jdbc.queryForList("select user_id from account_invitations where token_hash=?",hash(token)).firstOrNull()?:throw ResponseStatusException(HttpStatus.GONE,"This setup link has expired or has already been used")
  // Use the same user-first lock order as administrator welcome reissue, then recheck the token.
  jdbc.queryForObject("select id from users where id=? for update",UUID::class.java,candidate["user_id"])
  val r=jdbc.queryForList("select i.id,i.user_id from account_invitations i join users u on u.id=i.user_id where i.token_hash=? and i.used_at is null and i.revoked_at is null and i.expires_at>now() and u.active and lower(i.email_at_issue)=lower(u.email) for update of i,u",hash(token)).firstOrNull()?:throw ResponseStatusException(HttpStatus.GONE,"This setup link has expired or has already been used")
  jdbc.update("update uat_credentials set password_hash=? where user_id=?",encoder.encode(password),r["user_id"])
  jdbc.update("update account_invitations set used_at=now() where user_id=? and used_at is null",r["user_id"])
  jdbc.update("insert into operational_history(dataset,record_id,actor_id,action,note) values ('accounts',?,?,'ACCOUNT_SETUP_COMPLETED','Account holder completed the single-use welcome link')",r["user_id"],r["user_id"])
  jdbc.update("insert into notifications(user_id,type,title,message) values (?,'ACCOUNT','Your NHRC eGMS password is ready','Your account setup is complete. Sign in with your registered email and the password you chose. If you did not complete this setup, contact your administrator immediately.')",r["user_id"])
  return mapOf("completed" to true)
 }
}
data class InvitationInspect(val token:String)
data class InvitationAccept(val token:String,val password:String,val confirmation:String)
@RestController
@RequestMapping("/api/public/account-invitations")
class AccountInvitationController(private val service:AccountInvitations) {
 @PostMapping("/inspect") fun inspect(@RequestBody r:InvitationInspect)=service.inspect(r.token)
 @PostMapping("/accept") fun accept(@RequestBody r:InvitationAccept):Map<String,Boolean> {require(r.password==r.confirmation){"The passwords must match"};return service.accept(r.token,r.password)}
}
