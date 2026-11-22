package autoconnect.adapter

import java.security.MessageDigest

/**
 * Minimal zero-dependency JSON model + parser + writer.
 *
 * The writer and [stableStringify] deliberately mirror the backend's
 * JavaScript semantics (autoconnect-backend/lib/bundle.mjs) byte-for-byte:
 * the bundle signature and the config-id rule both depend on the exact same
 * canonical JSON the Node builder produces.
 */
sealed interface JsonValue

data class JsonObj(val entries: LinkedHashMap<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = entries[key]
    fun str(key: String): String? = (entries[key] as? JsonStr)?.value
    fun bool(key: String): Boolean? = (entries[key] as? JsonBool)?.value
    fun num(key: String): Double? = (entries[key] as? JsonNum)?.toDouble()
    fun obj(key: String): JsonObj? = entries[key] as? JsonObj
    fun arr(key: String): JsonArr? = entries[key] as? JsonArr
    fun strList(key: String): List<String>? =
        (entries[key] as? JsonArr)?.items?.mapNotNull { (it as? JsonStr)?.value }
}

data class JsonArr(val items: List<JsonValue>) : JsonValue
data class JsonStr(val value: String) : JsonValue

data class JsonNum(val raw: String) : JsonValue {
    fun toDouble(): Double = raw.toDouble()
    fun toLong(): Long = toDouble().toLong()
    companion object {
        fun of(v: Long): JsonNum = JsonNum(v.toString())
        fun of(v: Int): JsonNum = JsonNum(v.toString())
        fun of(v: Double): JsonNum = JsonNum(jsNumberFormat(v.toString()))
    }
}

data class JsonBool(val value: Boolean) : JsonValue
object JsonNull : JsonValue

fun jstr(v: String): JsonStr = JsonStr(v)
fun jbool(v: Boolean): JsonBool = JsonBool(v)
fun jnum(v: Int): JsonNum = JsonNum.of(v)
fun jnum(v: Long): JsonNum = JsonNum.of(v)
fun jarr(items: List<JsonValue>): JsonArr = JsonArr(items)
fun jobj(vararg pairs: Pair<String, JsonValue?>): JsonObj {
    val map = LinkedHashMap<String, JsonValue>()
    for ((k, v) in pairs) if (v != null) map[k] = v
    return JsonObj(map)
}

/** JS Number → string, as JSON.stringify would print it (integers without ".0"). */
fun jsNumberFormat(raw: String): String {
    val d = raw.toDouble()
    return if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 9.007199254740992E15) {
        d.toLong().toString()
    } else {
        d.toString()
    }
}

/** JSON string escaping with exactly the escapes JS JSON.stringify emits. */
fun escapeJsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

/** Compact serialization of a [JsonValue] (insertion key order). */
fun JsonValue.toJsonString(): String = when (this) {
    is JsonStr -> escapeJsonString(value)
    is JsonNum -> jsNumberFormat(raw)
    is JsonBool -> if (value) "true" else "false"
    JsonNull -> "null"
    is JsonArr -> items.joinToString(",", "[", "]") { it.toJsonString() }
    is JsonObj -> entries.entries.joinToString(",", "{", "}") {
        escapeJsonString(it.key) + ":" + it.value.toJsonString()
    }
}

/**
 * Stable (recursively key-sorted, whitespace-free) JSON — a byte-exact port of
 * `stableStringify` in autoconnect-backend/lib/bundle.mjs. Object key sort is
 * by UTF-16 code unit, matching both JS `Object.keys().sort()` and Kotlin's
 * natural String ordering.
 */
fun stableStringify(value: JsonValue): String = when (value) {
    is JsonStr -> escapeJsonString(value.value)
    is JsonNum -> jsNumberFormat(value.raw)
    is JsonBool -> if (value.value) "true" else "false"
    JsonNull -> "null"
    is JsonArr -> value.items.joinToString(",", "[", "]") { stableStringify(it) }
    is JsonObj -> value.entries.keys.sorted().joinToString(",", "{", "}") { k ->
        escapeJsonString(k) + ":" + stableStringify(value.entries.getValue(k))
    }
}

fun sha256HexUtf8(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

class JsonParseException(message: String) : Exception(message)

/** Small recursive-descent JSON parser (objects, arrays, strings, numbers, literals). */
class JsonParser private constructor(private val text: String) {
    private var pos = 0

    companion object {
        fun parse(text: String): JsonValue {
            val p = JsonParser(text)
            p.skipWs()
            val v = p.parseValue()
            p.skipWs()
            if (p.pos != text.length) throw JsonParseException("trailing content at ${p.pos}")
            return v
        }
    }

    private fun skipWs() {
        while (pos < text.length && text[pos] in " \t\n\r") pos++
    }

    private fun peek(): Char =
        if (pos < text.length) text[pos] else throw JsonParseException("unexpected end of input")

    private fun parseValue(): JsonValue = when (peek()) {
        '{' -> parseObject()
        '[' -> parseArray()
        '"' -> JsonStr(parseString())
        't' -> { expect("true"); JsonBool(true) }
        'f' -> { expect("false"); JsonBool(false) }
        'n' -> { expect("null"); JsonNull }
        else -> parseNumber()
    }

    private fun expect(lit: String) {
        if (!text.startsWith(lit, pos)) throw JsonParseException("expected '$lit' at $pos")
        pos += lit.length
    }

    private fun parseObject(): JsonObj {
        pos++ // '{'
        val map = LinkedHashMap<String, JsonValue>()
        skipWs()
        if (peek() == '}') { pos++; return JsonObj(map) }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            if (peek() != ':') throw JsonParseException("expected ':' at $pos")
            pos++
            skipWs()
            map[key] = parseValue()
            skipWs()
            when (peek()) {
                ',' -> pos++
                '}' -> { pos++; return JsonObj(map) }
                else -> throw JsonParseException("expected ',' or '}' at $pos")
            }
        }
    }

    private fun parseArray(): JsonArr {
        pos++ // '['
        val items = mutableListOf<JsonValue>()
        skipWs()
        if (peek() == ']') { pos++; return JsonArr(items) }
        while (true) {
            skipWs()
            items.add(parseValue())
            skipWs()
            when (peek()) {
                ',' -> pos++
                ']' -> { pos++; return JsonArr(items) }
                else -> throw JsonParseException("expected ',' or ']' at $pos")
            }
        }
    }

    private fun parseString(): String {
        if (peek() != '"') throw JsonParseException("expected string at $pos")
        pos++
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) throw JsonParseException("unterminated string")
            val c = text[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= text.length) throw JsonParseException("unterminated escape")
                    when (val e = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) throw JsonParseException("bad \\u escape")
                            sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw JsonParseException("bad escape '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): JsonNum {
        val start = pos
        while (pos < text.length && text[pos] in "-+0123456789.eE") pos++
        if (start == pos) throw JsonParseException("invalid value at $pos")
        val raw = text.substring(start, pos)
        raw.toDoubleOrNull() ?: throw JsonParseException("invalid number '$raw'")
        return JsonNum(raw)
    }
}
