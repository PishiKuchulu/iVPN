package autoconnect.app

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Minimal, dependency-free Ed25519 signature *verification* (RFC 8032),
 * implemented over [BigInteger] field arithmetic.
 *
 * Why hand-rolled: the app supports minSdk 24, and Android's platform
 * Ed25519 (`Signature.getInstance("Ed25519")`) is not available across that
 * range. Verification is the only operation the app ever needs — the
 * private signing key never exists on the client (security boundary: the
 * app embeds ONLY the bundle-signing public key).
 *
 * Fail-closed contract: any malformed input, out-of-range scalar, or
 * non-decodable point returns `false`. There is no "accept anyway" path.
 *
 * Accepted-risk decision (AUD-012, recorded 2026-11-21): this verifier
 * performs no small-order/subgroup check on the decoded points and no
 * cofactor multiplication, and its BigInteger arithmetic is not
 * constant-time. That is accepted — deliberately — for the current key
 * model: verification only, over public data, against a SINGLE pinned
 * public key chosen by the developer. An attacker who does not hold the
 * private key gains no usable forgery path from cofactored points, and
 * timing leaks nothing (all inputs are public). REVISIT this decision
 * before the key model ever changes (e.g. accepting multiple keys
 * during a rotation window, doc §9.2).
 *
 * Correctness evidence: this exact file is exercised on the JVM against the
 * live production bundle and tampered variants (see BUILD-REPORT.md).
 */
object Ed25519 {

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

    /** Group order L = 2^252 + 27742317777372353535851937790883648493. */
    private val L: BigInteger =
        BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))

    /** Curve constant d = -121665/121666 (mod p), for a = -1 twisted Edwards. */
    private val D: BigInteger = run {
        val num = BigInteger.valueOf(-121665).mod(P)
        val den = BigInteger.valueOf(121666).modInverse(P)
        num.multiply(den).mod(P)
    }

    /** sqrt(-1) mod p = 2^((p-1)/4). */
    private val I: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    /** Extended twisted-Edwards point (X, Y, Z, T) with x = X/Z, y = Y/Z, T = XY/Z. */
    private data class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private val BASE: Point = run {
        // Base point: y = 4/5, x even (sign bit 0).
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        recoverPoint(y, sign = false)!!
    }

    private fun mod(v: BigInteger): BigInteger = v.mod(P)

    private fun add(p1: Point, p2: Point): Point {
        val a = mod((p1.y - p1.x) * (p2.y - p2.x))
        val b = mod((p1.y + p1.x) * (p2.y + p2.x))
        val c = mod(BigInteger.TWO * D * p1.t * p2.t)
        val dd = mod(BigInteger.TWO * p1.z * p2.z)
        val e = mod(b - a)
        val f = mod(dd - c)
        val g = mod(dd + c)
        val h = mod(b + a)
        return Point(mod(e * f), mod(g * h), mod(f * g), mod(e * h))
    }

    private fun scalarMul(point: Point, scalar: BigInteger): Point {
        var result = IDENTITY
        var addend = point
        var s = scalar
        while (s > BigInteger.ZERO) {
            if (s.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            s = s.shiftRight(1)
        }
        return result
    }

    private fun pointsEqual(p1: Point, p2: Point): Boolean =
        mod(p1.x * p2.z) == mod(p2.x * p1.z) && mod(p1.y * p2.z) == mod(p2.y * p1.z)

    /** Recover the full point from a y coordinate and the x sign bit. */
    private fun recoverPoint(y: BigInteger, sign: Boolean): Point? {
        if (y >= P) return null
        val yy = mod(y * y)
        // x^2 = (y^2 - 1) / (d*y^2 + 1)
        val xx = mod((yy - BigInteger.ONE) * mod(D * yy + BigInteger.ONE).modInverse(P))
        var x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (mod(x * x) != xx) {
            x = mod(x * I)
            if (mod(x * x) != xx) return null
        }
        if (x.testBit(0) != sign) x = P - x
        return Point(x, y, BigInteger.ONE, mod(x * y))
    }

    /** Decode a 32-byte little-endian point encoding (RFC 8032 §5.1.3). */
    private fun decodePoint(bytes: ByteArray, offset: Int): Point? {
        if (bytes.size < offset + 32) return null
        val encoded = ByteArray(32)
        for (i in 0 until 32) encoded[i] = bytes[offset + 31 - i]
        val sign = (bytes[offset + 31].toInt() and 0x80) != 0
        encoded[0] = (encoded[0].toInt() and 0x7F).toByte()
        val y = BigInteger(1, encoded)
        return recoverPoint(y, sign)
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int, length: Int): BigInteger {
        val be = ByteArray(length)
        for (i in 0 until length) be[i] = bytes[offset + length - 1 - i]
        return BigInteger(1, be)
    }

    /**
     * Verify [signature] (64 bytes: R || s) over [message] under
     * [publicKey] (32 bytes). Returns false for ANY invalid input.
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val aPoint = decodePoint(publicKey, 0) ?: return false
        val rPoint = decodePoint(signature, 0) ?: return false
        val s = littleEndianInt(signature, 32, 32)
        if (s >= L) return false

        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(signature, 0, 32) // R encoding, as transmitted
        digest.update(publicKey)
        digest.update(message)
        val h = BigInteger(1, digest.digest().reversedArray()).mod(L)

        val left = scalarMul(BASE, s)
        val right = add(rPoint, scalarMul(aPoint, h))
        return pointsEqual(left, right)
    }
}
