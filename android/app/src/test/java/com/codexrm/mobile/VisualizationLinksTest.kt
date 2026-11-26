package com.codexrm.mobile

import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualizationLinksTest {
    private fun ref(path: String) = "\uE200visualize\uE202" + JSONObject().put("path", path)
        .put("title", "月度用量").put("mode", "wide") + "\uE201"

    @Test fun parsesOfficialReferencesAndLegacyDirective() {
        assertEquals(VisualizationRef("C:\\work\\visuals\\usage.html", "月度用量", true),
            VisualizationRef.parse(ref("C:\\work\\visuals\\usage.html")))
        assertEquals(VisualizationRef("outputs/monthly-usage.html", "monthly usage", false),
            VisualizationRef.parse("::codex-inline-vis{file=\"outputs/monthly-usage.html\"}"))
        assertNotNull(VisualizationRef.parse(ref("/home/user/.codex/visualizations/a.html")))
        for (path in listOf("./chart.html", "https://example.com/chart.html", "/a/../chart.html", "//nas/chart.html",
            "C:\\work\\file:other.html", "/a/chart.svg", "/a/\nchart.html")) assertNull(path, VisualizationRef.parse(ref(path)))
    }

    @Test fun markdownOnlyRecognizesStandaloneTopLevelReferencesWhenEnabled() {
        fun parse(text: String, enabled: Boolean = true): List<VisualizationRef> {
            val document = Parser.builder().customBlockParserFactory(VisualizationBlock.Factory { enabled }).build().parse(text)
            val found = mutableListOf<VisualizationRef>()
            fun walk(node: Node?) {
                if (node is VisualizationBlock) found += node.ref
                if (node != null) { walk(node.firstChild); walk(node.next) }
            }
            walk(document)
            return found
        }
        val reference = ref("/workspace/chart.html")
        assertEquals(1, parse("上文\n$reference\n下文").size)
        assertEquals(2, parse("$reference\n\n::codex-inline-vis{file=\"another.html\"}").size)
        assertTrue(parse(reference, enabled = false).isEmpty())
        for (source in listOf("`$reference`", "````md\n$reference\n````", "    $reference", "> $reference",
            "- entry\n  $reference", "before $reference", "<div>\n$reference\n</div>")) assertTrue(source, parse(source).isEmpty())
    }

    @Test fun chartRequestsCannotAddQueriesRedirectTargetsOrOtherPaths() {
        val root = "https://persistent.oaistatic.com/plugins/visualize/charts/"
        assertTrue(VisualizationPreview.allowsChartResource(root + "1.8.1/element.js"))
        assertTrue(VisualizationPreview.allowsChartResource(root + "4".repeat(64) + "/runtime.js"))
        for (url in listOf(root + "1.8.1/element.js?x=1", root + "1.8.1/element.js#x", root + "1.8.1/../element.js",
            root + "1.8.1/other.js", root.replace(".com/", ".com.example/") + "1.8.1/element.js")) {
            assertFalse(url, VisualizationPreview.allowsChartResource(url))
        }
    }
}
