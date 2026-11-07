package app.sofabrowser.data

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password encryption for a settings file, from standard parts only: PBKDF2 with HMAC-SHA512 (RFC 8018) turns the
 * password and a random salt into an AES-256 key, and AES-GCM (NIST SP 800-38D) encrypts and authenticates the file
 * with a random 96-bit IV. A wrong password and a changed file both fail the same check. Each file has its own salt,
 * so its own key.
 */
object BackupCrypto {
    const val KDF = "PBKDF2-HMAC-SHA512"
    const val CIPHER = "AES-256-GCM"
    /**
     * OWASP's figure for PBKDF2-HMAC-SHA512 (2023), as strong as its 600,000 for SHA-256 and about twice as quick on a
     * TV (measured on one: 2.4 s against 5.2 s). Kept in each file, so a later Sofa can ask for more.
     */
    const val ITERATIONS = 210_000
    /** What a file may ask for: fewer is weaker than Sofa ever wrote, more would keep a TV busy for minutes. */
    val ITERATION_RANGE = 100_000..10_000_000
    const val MIN_PASSWORD = 8
    const val MAX_PASSWORD = 1024
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val HMAC = "HmacSHA512"

    /** An encrypted file's parts; [data] is the ciphertext with the GCM tag at its end. */
    class Sealed(val iterations: Int, val salt: ByteArray, val iv: ByteArray, val data: ByteArray)

    /** The password is wrong, or the file was changed since it was saved: GCM can't tell the two apart. */
    class WrongPassword : GeneralSecurityException()

    fun seal(plain: ByteArray, password: String, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): Sealed {
        require(password.length in MIN_PASSWORD..MAX_PASSWORD && iterations in ITERATION_RANGE)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        return Sealed(iterations, salt, iv, cipher.doFinal(plain))
    }

    /** The file's contents; throws [WrongPassword], or IllegalArgumentException for parts Sofa never writes. */
    fun open(sealed: Sealed, password: String): ByteArray {
        require(sealed.iterations in ITERATION_RANGE) { "iterations" }
        require(sealed.salt.size in SALT_BYTES..64 && sealed.iv.size == IV_BYTES && sealed.data.size >= TAG_BITS / 8) { "parts" }
        if (password.isEmpty() || password.length > MAX_PASSWORD) throw WrongPassword()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, sealed.salt, sealed.iterations), GCMParameterSpec(TAG_BITS, sealed.iv))
        return try { cipher.doFinal(sealed.data) } catch (e: AEADBadTagException) { throw WrongPassword() }
    }

    /** The same password typed on another keyboard can arrive with other Unicode code points: NFC makes them one. */
    private fun key(password: String, salt: ByteArray, iterations: Int) =
        SecretKeySpec(pbkdf2(HMAC, Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8), salt, iterations, 32), "AES")

    /**
     * PBKDF2 (RFC 8018 §5.2) over the platform's HMAC [hmac]: Android has no PBKDF2WithHmacSHA512 key factory before
     * 8.0, and Sofa runs from 7.0. Checked against RFC 7914's test vectors and the JDK's own factories.
     */
    fun pbkdf2(hmac: String, password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance(hmac).apply { init(SecretKeySpec(password, hmac)) }
        val h = mac.macLength
        val out = ByteArray(length)
        val u = ByteArray(h)
        val t = ByteArray(h)
        var block = 1
        var done = 0
        while (done < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            mac.doFinal(u, 0)
            u.copyInto(t)
            repeat(iterations - 1) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (i in 0 until h) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            t.copyInto(out, done, 0, minOf(h, length - done))
            done += h
            block++
        }
        return out
    }
}
