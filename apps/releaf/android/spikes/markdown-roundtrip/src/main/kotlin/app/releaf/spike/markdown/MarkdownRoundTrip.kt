package app.releaf.spike.markdown

//
// MarkdownRoundTrip — Android spike for docs/MARKDOWN_EDITOR.md
//
// Proves:
//   1. commonmark-java parses our full fixture corpus.
//   2. flexmark-java can emit canonical CommonMark from the same source.
//   3. The HTML rendering of source and of flexmark-formatted source are
//      byte-equal when both are rendered by commonmark-java — i.e. the
//      production stack preserves semantic meaning across one round-trip.
//   4. flexmark's formatter is idempotent after one pass.
//
// Exit 0 on full pass, 1 on any assertion failure.
//

import com.vladsch.flexmark.formatter.Formatter as FlexFormatter
import com.vladsch.flexmark.parser.Parser as FlexParser
import com.vladsch.flexmark.util.data.MutableDataSet
import org.commonmark.Extension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser as CmParser
import org.commonmark.renderer.html.HtmlRenderer
import kotlin.system.exitProcess

private data class Fixture(val name: String, val source: String)

private val fixtures = listOf(
    Fixture("plain-paragraph", "Hello, world.\n\nA second paragraph.\n"),
    Fixture(
        "headings",
        """
        # H1
        ## H2
        ### H3
        #### H4
        ##### H5
        ###### H6

        Body text after headings.
        """.trimIndent() + "\n",
    ),
    Fixture(
        "emphasis-and-strong",
        "A *star em* and a _underscore em_. A **star strong** and a __underscore strong__.\n",
    ),
    Fixture(
        "inline-code-and-links",
        "See `Parser.builder()` and [commonmark-java](https://github.com/commonmark/commonmark-java).\n",
    ),
    Fixture(
        "fenced-code-block",
        """
        ```kotlin
        val ast = parser.parse(source)
        println(formatter.render(ast))
        ```
        """.trimIndent() + "\n",
    ),
    Fixture("indented-code-block", "    let x = 1\n    let y = 2\n"),
    Fixture(
        "blockquote-nested",
        """
        > A quote
        > > nested
        > back out
        """.trimIndent() + "\n",
    ),
    Fixture(
        "unordered-list",
        """
        - first
        - second
          - nested
          - nested two
        - third
        """.trimIndent() + "\n",
    ),
    Fixture("ordered-list", "1. one\n2. two\n3. three\n"),
    Fixture("gfm-task-list", "- [ ] pending\n- [x] done\n- [ ] another\n"),
    Fixture("horizontal-rule", "before\n\n---\n\nafter\n"),
    Fixture("image", "![alt text](https://example.com/img.png \"title\")\n"),
    Fixture("html-block", "<div class=\"callout\">inline html</div>\n"),
    Fixture(
        "utf8-emoji-cjk-accents",
        "Emoji \uD83C\uDF3F, CJK 日本語, accents café, combining a\u0301.\n",
    ),
    Fixture(
        "gfm-table",
        """
        | H1 | H2 |
        | -- | -- |
        | a  | b  |
        | c  | d  |
        """.trimIndent() + "\n",
    ),
    Fixture("gfm-strikethrough", "Some ~~strikethrough~~ text.\n"),
)

// ---------- Parser / formatter / renderer setup ----------

private val cmExtensions: List<Extension> = listOf(
    TablesExtension.create(),
    StrikethroughExtension.create(),
    TaskListItemsExtension.create(),
)

private val cmParser: CmParser = CmParser.builder()
    .extensions(cmExtensions)
    .build()

private val cmHtmlRenderer: HtmlRenderer = HtmlRenderer.builder()
    .extensions(cmExtensions)
    .build()

private val flexOptions: MutableDataSet = MutableDataSet()

private val flexParser: FlexParser = FlexParser.builder(flexOptions).build()
private val flexFormatter: FlexFormatter = FlexFormatter.builder(flexOptions).build()

// ---------- Assertions ----------

private var failureCount = 0

private fun check(label: String, ok: Boolean, detail: () -> String = { "" }) {
    if (ok) {
        println("  ✓ $label")
    } else {
        failureCount++
        val d = detail()
        if (d.isEmpty()) println("  ✗ $label") else println("  ✗ $label\n    $d")
    }
}

// ---------- Runner ----------

fun main() {
    println("Releaf Android — Markdown round-trip spike")
    println("commonmark-java: 0.22.0")
    println("flexmark-java: 0.64.8")
    println()

    for (fixture in fixtures) {
        println("[${fixture.name}]")

        val astCmBaseline = cmParser.parse(fixture.source)
        val htmlBaseline = cmHtmlRenderer.render(astCmBaseline)

        val source1 = flexFormatter.render(flexParser.parse(fixture.source))
        val astCmReparsed = cmParser.parse(source1)
        val htmlReparsed = cmHtmlRenderer.render(astCmReparsed)

        check("commonmark-java HTML is stable across flexmark round-trip", htmlBaseline == htmlReparsed) {
            "baseline:\n${htmlBaseline}\nafter round-trip:\n${htmlReparsed}"
        }

        val source2 = flexFormatter.render(flexParser.parse(source1))
        check("flexmark formatter is idempotent after one pass", source1 == source2) {
            "pass 1:\n${source1}\npass 2:\n${source2}"
        }
    }

    println()
    if (failureCount == 0) {
        println("PASS — all round-trip assertions held.")
        exitProcess(0)
    } else {
        println("FAIL — $failureCount assertion(s) failed.")
        exitProcess(1)
    }
}
