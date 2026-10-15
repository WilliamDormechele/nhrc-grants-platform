package org.nhrc.grants.notifications
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path
import java.nio.file.Files
import java.util.Base64
class MailSecretVaultTest {
 @TempDir lateinit var directory:Path
 @Test fun `password is encrypted with fresh nonces and survives service recreation`() {
  val vault=MailSecretVault(directory);val plain="abcdefghijklmnop";val first=vault.encrypt(plain);val second=vault.encrypt(plain)
  assertNotEquals(first,second);assertFalse(first.contains(plain));assertEquals(plain,MailSecretVault(directory).decrypt(first));assertEquals(32,Files.size(directory.resolve("encryption.key")))
 }
 @Test fun `tampered credentials cannot be decrypted`() {val vault=MailSecretVault(directory);val bytes=Base64.getDecoder().decode(vault.encrypt("abcdefghijklmnop"));bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();assertThrows(Exception::class.java){vault.decrypt(Base64.getEncoder().encodeToString(bytes))}}
}
