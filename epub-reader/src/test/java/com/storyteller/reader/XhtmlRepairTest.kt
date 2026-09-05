package com.storyteller.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The repair has to be invisible on the 99% of files that are already fine —
 * a byte changed in a healthy chapter is a worse bug than the one being fixed —
 * so "unchanged" is asserted by identity, not by equality.
 */
class XhtmlRepairTest {

    private fun repair(xhtml: String) = XhtmlRepair.repair(xhtml.encodeToByteArray()).decodeToString()

    @Test
    fun `well-formed markup comes back as the same array`() {
        val bytes = "<p class=\"a\" style=\"color:red\">bonjour</p>".encodeToByteArray()
        assertSame(bytes, XhtmlRepair.repair(bytes))
    }

    @Test
    fun `a repeated attribute keeps the first value`() {
        assertEquals(
            """<p class="a" style="color:red">bonjour</p>""",
            repair("""<p class="a" style="color:red" style="color:blue">bonjour</p>"""),
        )
    }

    @Test
    fun `the repeat is matched whatever its case`() {
        assertEquals(
            """<span STYLE="a">x</span>""",
            repair("""<span STYLE="a" style="b">x</span>"""),
        )
    }

    @Test
    fun `single quotes and a self-closing tag are handled`() {
        assertEquals(
            """<img src='a.png'/>""",
            repair("""<img src='a.png' src='b.png'/>"""),
        )
    }

    @Test
    fun `three copies leave one`() {
        assertEquals(
            """<div id="x">y</div>""",
            repair("""<div id="x" id="y" id="z">y</div>"""),
        )
    }

    @Test
    fun `only the offending tag is touched`() {
        assertEquals(
            """<a href="1">un</a><b style="s">deux</b><a href="2">trois</a>""",
            repair("""<a href="1">un</a><b style="s" style="t">deux</b><a href="2">trois</a>"""),
        )
    }

    @Test
    fun `an attribute value containing an equals sign is not mistaken for one`() {
        val ok = """<p style="background:url(a?b=c)">x</p>"""
        assertEquals(ok, repair(ok))
    }

    @Test
    fun `closing tags and comments are left alone`() {
        val ok = "<!-- style=\"a\" style=\"b\" --><p>x</p>"
        assertEquals(ok, repair(ok))
    }
}
