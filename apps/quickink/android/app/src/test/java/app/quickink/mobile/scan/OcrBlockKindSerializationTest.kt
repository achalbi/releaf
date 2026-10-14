/*
 * OcrBlockKindSerializationTest — pins the wire form of
 * `OcrBlock.Kind` in `ocr_results.blocks_json`.
 *
 * Canonical form is lowercase (`"line"`), matching iOS. Rows written
 * by older Android builds carry PascalCase (`"Line"`) and must still
 * decode, both locally and after a Drive round-trip to the other
 * platform.
 */

package app.quickink.mobile.scan

import app.releaf.shared.scan.OcrBbox
import app.releaf.shared.scan.OcrBlock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrBlockKindSerializationTest {

    // Same config as CaptureRepository's encoder and the
    // business-card reader's decoder.
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(OcrBlock.serializer())

    private fun blockJson(kind: String) =
        """[{"text":"Ada","bbox":{"x":0.1,"y":0.2,"width":0.3,"height":0.04},""" +
            """"confidence":null,"language":"en","kind":"$kind"}]"""

    @Test
    fun decodesLowercaseKinds() {
        assertEquals(OcrBlock.Kind.Line, json.decodeFromString(listSerializer, blockJson("line")).single().kind)
        assertEquals(OcrBlock.Kind.Paragraph, json.decodeFromString(listSerializer, blockJson("paragraph")).single().kind)
        assertEquals(OcrBlock.Kind.Word, json.decodeFromString(listSerializer, blockJson("word")).single().kind)
    }

    @Test
    fun decodesLegacyPascalCaseKinds() {
        assertEquals(OcrBlock.Kind.Line, json.decodeFromString(listSerializer, blockJson("Line")).single().kind)
        assertEquals(OcrBlock.Kind.Paragraph, json.decodeFromString(listSerializer, blockJson("Paragraph")).single().kind)
        assertEquals(OcrBlock.Kind.Word, json.decodeFromString(listSerializer, blockJson("Word")).single().kind)
    }

    @Test
    fun encodesLowercaseKind() {
        val block = OcrBlock(
            text       = "Ada",
            bbox       = OcrBbox(0.1, 0.2, 0.3, 0.04),
            confidence = null,
            language   = "en",
            kind       = OcrBlock.Kind.Line,
        )
        val encoded = json.encodeToString(listSerializer, listOf(block))
        assertTrue(encoded, encoded.contains("\"kind\":\"line\""))
        assertEquals(listOf(block), json.decodeFromString(listSerializer, encoded))
    }

    @Test(expected = SerializationException::class)
    fun rejectsUnknownKind() {
        json.decodeFromString(listSerializer, blockJson("glyph"))
    }
}
