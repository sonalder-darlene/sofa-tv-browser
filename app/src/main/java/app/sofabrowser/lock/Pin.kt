package app.sofabrowser.lock

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The parental PIN: four digits, kept only as a salted PBKDF2 hash, with a pause that grows after wrong tries.
 * [data] is a preferences file of its own, so the PIN never travels with other settings; [now] is the wall clock,
 * so a pause survives a restart. No PIN set means nothing is locked.
 */
class Pin(private val data: MutableMap<String, String>, private val now: () -> Long = System::currentTimeMillis) {

    val isSet get() = HASH in data

    /** Replaces any PIN; the wrong-try count starts again. */
    fun set(pin: String) {
        require(valid(pin))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        data.clear()
        data[SALT] = hex(salt)
        data[HASH] = hex(hash(pin, salt))
    }

    fun remove() = data.clear()

    private val fails get() = data[FAILS]?.toIntOrNull() ?: 0

    /** Milliseconds until another try is allowed, or 0. */
    fun waitLeft(): Long {
        val fails = fails
        if (fails < FREE_TRIES) return 0
        val last = data[LAST_FAIL]?.toLongOrNull() ?: 0L
        val t = now()
        // The clock was turned back: the pause starts again from now rather than being skipped.
        if (t < last) { data[LAST_FAIL] = t.toString(); return pause(fails) }
        return (last + pause(fails) - t).coerceAtLeast(0)
    }

    sealed interface Result {
        data object Right : Result
        /** [triesLeft] before a pause; [wait] is the pause that has just started, or 0. */
        data class Wrong(val triesLeft: Int, val wait: Long, val fails: Int) : Result
        /** A pause is running: the PIN wasn't checked. */
        data class Wait(val wait: Long) : Result
    }

    fun check(pin: String): Result {
        waitLeft().let { if (it > 0) return Result.Wait(it) }
        val salt = data[SALT]?.let(::unhex)
        val stored = data[HASH]?.let(::unhex)
        if (salt != null && stored != null && valid(pin) && MessageDigest.isEqual(hash(pin, salt), stored)) {
            data.remove(FAILS)
            data.remove(LAST_FAIL)
            return Result.Right
        }
        val f = fails + 1
        data[FAILS] = f.toString()
        data[LAST_FAIL] = now().toString()
        return Result.Wrong((FREE_TRIES - f).coerceAtLeast(0), waitLeft(), f)
    }

    companion object {
        const val LENGTH = 4
        /** Wrong tries allowed before the first pause, for typos. */
        const val FREE_TRIES = 5
        private const val FIRST_PAUSE = 30_000L
        private const val LONGEST_PAUSE = 30 * 60_000L
        private const val ITERATIONS = 10_000
        private const val HASH = "hash"
        private const val SALT = "salt"
        private const val FAILS = "fails"
        private const val LAST_FAIL = "last_fail"

        fun valid(pin: String) = pin.length == LENGTH && pin.all { it in '0'..'9' }

        /** 30 s after the fifth wrong try, doubling with each one after, up to 30 minutes. */
        fun pause(fails: Int): Long =
            if (fails < FREE_TRIES) 0 else (FIRST_PAUSE shl (fails - FREE_TRIES).coerceAtMost(6)).coerceAtMost(LONGEST_PAUSE)

        private fun hash(pin: String, salt: ByteArray): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)).encoded

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun unhex(s: String) = runCatching { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }
}
