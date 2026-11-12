package me.rerere.rikkahub.theme.packageio

import org.junit.Assert.assertThrows
import org.junit.Test

class StrictJsonTest {
    @Test fun acceptsNestedDocument() {
        accepts("""{"a":1,"b":[true,false,null,{"c":"d"}],"e":{"f":-1.25e+3}}""")
    }

    @Test fun acceptsSameKeyInDifferentObjects() {
        accepts("""[{"a":1},{"a":2}]""")
    }

    @Test fun acceptsNumbers() {
        accepts("""{"a":0,"b":-0,"c":1.5,"d":2e10,"e":-3.2E-4}""")
    }

    @Test fun acceptsEscapedKeysThatStayDistinct() {
        accepts("{\"a\\u0062\":1,\"ac\":2}")
    }

    @Test fun acceptsSingleEscapedKey() {
        accepts("{\"\\u0069d\":1}")
    }

    @Test fun acceptsNestingUpToLimit() {
        accepts("[".repeat(30) + "0" + "]".repeat(30))
    }

    @Test fun rejectsDuplicateTopLevelKey() {
        rejects("""{"a":1,"a":2}""")
    }

    @Test fun rejectsDuplicateNestedKey() {
        rejects("""{"a":{"b":1,"b":2}}""")
    }

    @Test fun rejectsEscapedEquivalentDuplicateKey() {
        rejects("{\"na\\u006de\":1,\"name\":2}")
    }

    @Test fun rejectsCaseDistinctUnicodeDuplicateKey() {
        rejects("{\"A\":1,\"\\u0041\":2}")
    }

    @Test fun rejectsSurrogateDuplicateKey() {
        rejects("{\"\\uD83D\\uDE00\":1,\"\\uD83D\\uDE00\":2}")
    }

    @Test fun rejectsDuplicatesInArrayElements() {
        rejects("""[{"a":1,"a":2}]""")
    }

    @Test fun rejectsControlCharacterInString() {
        rejects("{\"a\":\"b\nc\"}")
    }

    @Test fun rejectsInvalidEscape() {
        rejects("{\"a\":\"\\x\"}")
    }

    @Test fun rejectsUnterminatedString() {
        rejects("{\"a\":\"b}")
    }

    @Test fun rejectsTrailingCommaInObject() {
        rejects("""{"a":1,}""")
    }

    @Test fun rejectsTrailingCommaInArray() {
        rejects("""[1,]""")
    }

    @Test fun rejectsMissingColon() {
        rejects("""{"a" 1}""")
    }

    @Test fun rejectsUnquotedKey() {
        rejects("""{a:1}""")
    }

    @Test fun rejectsTrailingContent() {
        rejects("""{}{}""")
    }

    @Test fun rejectsEmptyDocument() {
        rejects("")
    }

    @Test fun rejectsExcessiveNesting() {
        rejects("[".repeat(40) + "0" + "]".repeat(40))
    }

    @Test fun rejectsLeadingZeroNumber() {
        rejects("""{"a":01}""")
    }

    @Test fun rejectsIncompleteLiteral() {
        rejects("""{"a":tru}""")
    }

    @Test fun rejectsNonJsonDocument() {
        rejects("not json")
    }

    private fun accepts(json: String) {
        StrictJson.requireUniqueKeys(json)
    }

    private fun rejects(json: String) {
        assertThrows(IllegalArgumentException::class.java) { StrictJson.requireUniqueKeys(json) }
    }
}
