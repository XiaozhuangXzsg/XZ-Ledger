package com.xzledger.app.data

/**
 * 极简 JSON 解析器与构造器。
 *
 * 为什么不用第三方库：本应用需要与用户自填的 OpenAI 兼容接口通信，但只用到很小的一个子集
 * （对象、数组、字符串、数字、布尔、null）。自己实现可以避免引入额外依赖，也不会因为反射
 * 或注解处理器影响打包。解析失败会抛异常，由调用方明确报错，不会静默返回空数据。
 */
object Json {

    fun parse(text: String): Any? {
        val p = P(text)
        p.ws()
        return p.value()
    }

    /** 安全解析：解析失败返回 null（用于第三方接口返回非 JSON 时的降级处理）。 */
    fun parseOrNull(text: String): Any? = try {
        parse(text)
    } catch (e: Exception) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    fun map(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun list(v: Any?): List<Any?> = (v as? List<Any?>) ?: emptyList()

    fun get(v: Any?, key: String): Any? = map(v)[key]

    fun str(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        else -> v.toString()
    }

    fun long(v: Any?): Long? = when (v) {
        null -> null
        is Double -> v.toLong()
        is Long -> v
        is Number -> v.toLong()
        is String -> v.trim().toDoubleOrNull()?.toLong()
        else -> null
    }

    fun bool(v: Any?): Boolean? = when (v) {
        null -> null
        is Boolean -> v
        is String -> v.equals("true", true)
        is Double -> v != 0.0
        else -> null
    }

    fun esc(s: String): String {
        val sb = StringBuilder()
        s.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(' ') else sb.append(c)
            }
        }
        return sb.toString()
    }

    fun str(value: String?): String = if (value == null) "null" else "\"" + esc(value) + "\""
    fun num(value: Long): String = value.toString()
    fun boolLit(value: Boolean): String = if (value) "true" else "false"

    /** 构造 {"k":v,...}；值需自行用 str/num 包裹。 */
    fun obj(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { str(it.first) + ":" + it.second }

    /** 构造 JSON 数组；元素需自行包裹。 */
    fun arr(items: List<String>): String = items.joinToString(",", "[", "]")

    private class P(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> {
                    expect("true"); true
                }
                'f' -> {
                    expect("false"); false
                }
                'n' -> {
                    expect("null"); null
                }
                else -> num()
            }
        }

        fun expect(w: String) {
            if (!s.startsWith(w, i)) throw IllegalArgumentException("JSON 格式错误：位置 " + i)
            i += w.length
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("JSON 格式错误：缺少冒号，位置 " + i)
                i++
                m[k] = value()
                ws()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                if (i < s.length && s[i] == '}') {
                    i++
                    break
                }
                throw IllegalArgumentException("JSON 格式错误：对象未闭合，位置 " + i)
            }
            return m
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return l
            }
            while (true) {
                l.add(value())
                ws()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                if (i < s.length && s[i] == ']') {
                    i++
                    break
                }
                throw IllegalArgumentException("JSON 格式错误：数组未闭合，位置 " + i)
            }
            return l
        }

        fun str(): String {
            ws()
            if (i >= s.length || s[i] != '"') throw IllegalArgumentException("JSON 格式错误：缺少字符串，位置 " + i)
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> {
                        i++
                        return sb.toString()
                    }
                    c == '\\' -> {
                        i++
                        if (i >= s.length) break
                        when (s[i]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 < s.length) {
                                    sb.append(s.substring(i + 1, i + 5).toIntOrNull(16)?.toChar() ?: '?')
                                    i += 4
                                }
                            }
                            else -> sb.append(s[i])
                        }
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return sb.toString()
        }

        fun num(): Any? {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            if (start == i) {
                i++
                return null
            }
            return s.substring(start, i).toDoubleOrNull()
        }
    }
}
