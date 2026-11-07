package app.sofabrowser.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class BackupCryptoTest {

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** RFC 7914 §11, the PBKDF2-HMAC-SHA256 vectors. */
    @Test fun pbkdf2MatchesRfc7914() {
        assertArrayEquals(hex("55 ac 04 6e 56 e3 08 9f ec 16 91 c2 25 44 b6 05 f9 41 85 21 6d de 04 65 e6 8b 9d 57 c2 0d ac bc" +
            "49 ca 9c cc f1 79 b6 45 99 16 64 b3 9d 77 ef 31 7c 71 b8 45 b1 e3 0b d5 09 11 20 41 d3 a1 97 83"),
            BackupCrypto.pbkdf2("HmacSHA256", "passwd".toByteArray(), "salt".toByteArray(), 1, 64))
        assertArrayEquals(hex("4d dc d8 f6 0b 98 be 21 83 0c ee 5e f2 27 01 f9 64 1a 44 18 d0 4c 04 14 ae ff 08 87 6b 34 ab 56" +
            "a1 d4 25 a1 22 58 33 54 9a db 84 1b 51 c9 b3 17 6a 27 2b de bb a1 d0 78 47 8f 62 b3 97 f3 3c 8d"),
            BackupCrypto.pbkdf2("HmacSHA256", "Password".toByteArray(), "NaCl".toByteArray(), 80_000, 64))
    }

    @Test fun pbkdf2MatchesTheJdk() {
        val random = java.util.Random(7)
        for (sha in listOf("SHA256", "SHA512")) repeat(20) {
            val password = String(CharArray(1 + random.nextInt(40)) { 'a' + random.nextInt(26) })
            val salt = ByteArray(1 + random.nextInt(32)).also(random::nextBytes)
            val iterations = 1 + random.nextInt(3000)
            val length = 1 + random.nextInt(80)
            val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmac$sha")
                .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, length * 8)).encoded
            assertArrayEquals(jdk, BackupCrypto.pbkdf2("Hmac$sha", password.toByteArray(), salt, iterations, length))
        }
    }

    private val fast = BackupCrypto.ITERATION_RANGE.first

    @Test fun roundTrip() {
        val plain = "{\"format\":\"sofa-settings\"} é ✓".toByteArray()
        val sealed = BackupCrypto.seal(plain, "correct horse", fast)
        assertEquals(16, sealed.salt.size)
        assertEquals(12, sealed.iv.size)
        assertEquals(plain.size + 16, sealed.data.size)
        assertArrayEquals(plain, BackupCrypto.open(sealed, "correct horse"))
    }

    @Test fun eachFileHasItsOwnSaltAndIv() {
        val a = BackupCrypto.seal(ByteArray(10), "password1", fast, SecureRandom())
        val b = BackupCrypto.seal(ByteArray(10), "password1", fast, SecureRandom())
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.iv.contentEquals(b.iv))
        assertFalse(a.data.contentEquals(b.data))
    }

    @Test fun wrongPasswordFails() {
        val sealed = BackupCrypto.seal(ByteArray(100), "password1", fast)
        try { BackupCrypto.open(sealed, "password2"); fail() } catch (e: BackupCrypto.WrongPassword) { }
        try { BackupCrypto.open(sealed, ""); fail() } catch (e: BackupCrypto.WrongPassword) { }
    }

    @Test fun aChangedFileFails() {
        val sealed = BackupCrypto.seal(ByteArray(100), "password1", fast)
        sealed.data[3] = (sealed.data[3].toInt() xor 1).toByte()
        try { BackupCrypto.open(sealed, "password1"); fail() } catch (e: BackupCrypto.WrongPassword) { }
        val other = BackupCrypto.seal(ByteArray(100), "password1", fast)
        // The iteration count is part of the key: a file that asks for fewer opens with a different key.
        try { BackupCrypto.open(BackupCrypto.Sealed(fast + 1, other.salt, other.iv, other.data), "password1"); fail() }
        catch (e: BackupCrypto.WrongPassword) { }
    }

    @Test fun theSamePasswordInAnotherUnicodeFormOpens() {
        val composed = "café-password"
        val decomposed = "café-password"
        val sealed = BackupCrypto.seal(ByteArray(5), composed, fast)
        assertArrayEquals(ByteArray(5), BackupCrypto.open(sealed, decomposed))
    }

    @Test fun refusesWhatSofaNeverWrites() {
        val s = BackupCrypto.seal(ByteArray(5), "password1", fast)
        for (bad in listOf(
            BackupCrypto.Sealed(1_000, s.salt, s.iv, s.data),
            BackupCrypto.Sealed(Int.MAX_VALUE, s.salt, s.iv, s.data),
            BackupCrypto.Sealed(fast, ByteArray(4), s.iv, s.data),
            BackupCrypto.Sealed(fast, s.salt, ByteArray(16), s.data),
            BackupCrypto.Sealed(fast, s.salt, s.iv, ByteArray(3)),
        )) {
            try { BackupCrypto.open(bad, "password1"); fail() } catch (e: IllegalArgumentException) { }
        }
    }

    @Test fun refusesShortPasswordsWhenSealing() {
        try { BackupCrypto.seal(ByteArray(5), "short", fast); fail() } catch (e: IllegalArgumentException) { }
    }
}
