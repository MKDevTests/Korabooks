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

    @Test
    fun `the style Calibre froze onto html is removed, because Readium adds its own`() {
        assertEquals(
            """<html xmlns="http://www.w3.org/1999/xhtml"><body/></html>""",
            repair("""<html xmlns="http://www.w3.org/1999/xhtml" style="font-size:1.136rem;"><body/></html>"""),
        )
    }

    @Test
    fun `an html tag with no style of its own comes back as the same array`() {
        val bytes = """<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="fr"><body/></html>"""
            .encodeToByteArray()
        assertSame(bytes, XhtmlRepair.repair(bytes))
    }

    @Test
    fun `html keeps every attribute but style`() {
        assertEquals(
            """<html lang="fr" dir="ltr">x</html>""",
            repair("""<html lang="fr" style="font-size:2rem" dir="ltr">x</html>"""),
        )
    }

    @Test
    fun `two styles on html both go, so the injected one stands alone`() {
        assertEquals(
            """<html>x</html>""",
            repair("""<html style="a" style="b">x</html>"""),
        )
    }

    @Test
    fun `a style on body is left where it is`() {
        assertEquals(
            """<html><body style="margin:0">x</body></html>""",
            repair("""<html style="font-size:1rem"><body style="margin:0">x</body></html>"""),
        )
    }

    @Test
    fun `a chapter that is not UTF-8 is handed back rather than re-encoded`() {
        val latin1 = """<html style="a">Le trésor</html>""".toByteArray(Charsets.ISO_8859_1)
        assertSame(latin1, XhtmlRepair.repair(latin1))
    }
}
