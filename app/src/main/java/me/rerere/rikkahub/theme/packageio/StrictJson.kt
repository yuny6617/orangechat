package me.rerere.rikkahub.theme.packageio

/**
 * Minimal strict JSON walker used only to reject duplicate object keys before
 * kotlinx.serialization is allowed to decode a theme document.
 *
 * kotlinx.serialization decodes JSON objects into maps/classes with last-wins
 * semantics for repeated names, which would let a package hide a token value.
 * This walker decodes every key escape (including `\uXXXX`) so `"id"`,
 * `"\u0069d"` and `"i\u0064"` are recognised as the same key inside one object.
 * It does not build a DOM and never echoes a value.
 */
internal object StrictJson {
    private const val MAX_DEPTH = 32

    fun requireUniqueKeys(text: String) {
        require(text.isNotEmpty()) { "Theme JSON is empty" }
        Parser(text).parseDocument()
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parseDocument() {
            skipWhitespace()
            parseValue(0)
            skipWhitespace()
            require(index == text.length) { "Theme JSON has trailing content" }
        }

        private fun parseValue(depth: Int) {
            require(depth <= MAX_DEPTH) { "Theme JSON is nested too deeply" }
            require(index < text.length) { "Theme JSON ended unexpectedly" }
            when (text[index]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> parseLiteral("true")
                'f' -> parseLiteral("false")
                'n' -> parseLiteral("null")
                '-' -> parseNumber()
                in '0'..'9' -> parseNumber()
                else -> fail("Unexpected character in theme JSON")
            }
        }

        private fun parseObject(depth: Int) {
            index++ // '{'
            val keys = HashSet<String>()
            skipWhitespace()
            if (index < text.length && text[index] == '}') {
                index++
                return
            }
            while (true) {
                skipWhitespace()
                require(index < text.length && text[index] == '"') { "Object key must be a string" }
                val key = parseString()
                require(keys.add(key)) { "Duplicate JSON key in theme JSON" }
                skipWhitespace()
                require(index < text.length && text[index] == ':') { "Expected ':' after object key" }
                index++
                skipWhitespace()
                parseValue(depth)
                skipWhitespace()
                require(index < text.length) { "Theme JSON ended unexpectedly" }
                when (text[index]) {
                    ',' -> {
                        index++
                        skipWhitespace()
                        require(index < text.length && text[index] != '}') { "Trailing comma in object" }
                    }

                    '}' -> {
                        index++
                        return
                    }

                    else -> fail("Expected ',' or '}' in object")
                }
            }
        }

        private fun parseArray(depth: Int) {
            index++ // '['
            skipWhitespace()
            if (index < text.length && text[index] == ']') {
                index++
                return
            }
            while (true) {
                skipWhitespace()
                parseValue(depth)
                skipWhitespace()
                require(index < text.length) { "Theme JSON ended unexpectedly" }
                when (text[index]) {
                    ',' -> {
                        index++
                        skipWhitespace()
                        require(index < text.length && text[index] != ']') { "Trailing comma in array" }
                    }

                    ']' -> {
                        index++
                        return
                    }

                    else -> fail("Expected ',' or ']' in array")
                }
            }
        }

        /** Decodes a JSON string, including `\uXXXX`, and returns its logical value. */
        private fun parseString(): String {
            require(index < text.length && text[index] == '"') { "Expected string" }
            index++
            val value = StringBuilder()
            while (true) {
                require(index < text.length) { "Unterminated string in theme JSON" }
                val char = text[index++]
                when {
                    char == '"' -> return value.toString()
                    char == '\\' -> value.append(parseEscape())
                    char.code < 0x20 -> fail("Control character in string")
                    else -> value.append(char)
                }
            }
        }

        private fun parseEscape(): Char {
            require(index < text.length) { "Unterminated escape in theme JSON" }
            return when (val escape = text[index++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    require(index + 4 <= text.length) { "Truncated unicode escape" }
                    var code = 0
                    repeat(4) {
                        val digit = Character.digit(text[index++], 16)
                        require(digit >= 0) { "Invalid unicode escape" }
                        code = code * 16 + digit
                    }
                    code.toChar()
                }

                else -> fail("Invalid escape '\\$escape'")
            }
        }

        private fun parseLiteral(literal: String) {
            require(text.startsWith(literal, index)) { "Invalid literal in theme JSON" }
            index += literal.length
        }

        private fun parseNumber() {
            if (text[index] == '-') index++
            require(index < text.length && text[index] in '0'..'9') { "Invalid number in theme JSON" }
            if (text[index] == '0') {
                index++
            } else {
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (index < text.length && text[index] == '.') {
                index++
                require(index < text.length && text[index] in '0'..'9') { "Invalid number fraction" }
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                require(index < text.length && text[index] in '0'..'9') { "Invalid number exponent" }
                while (index < text.length && text[index] in '0'..'9') index++
            }
        }

        private fun skipWhitespace() {
            while (index < text.length) {
                when (text[index]) {
                    ' ', '\t', '\n', '\r' -> index++
                    else -> return
                }
            }
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    }
}
