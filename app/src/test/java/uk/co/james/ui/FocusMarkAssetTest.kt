package uk.co.james.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A lightweight visual-reference contract.  The approved raster is traced in
 * branding/focus-mark.svg; product surfaces must reuse that geometry rather
 * than reintroduce a J hook in a one-off icon variant. */
class FocusMarkAssetTest {
    @Test fun canonical_focus_mark_has_one_straight_stem_and_no_lower_right_hook() {
        val master=read("branding/focus-mark.svg")
        assertTrue(master.contains("M46 19A36 36 0 1 0 66 19"))
        assertTrue(master.contains("M54 27V66"))
        assertTrue(master.contains("<circle cx=\"41\" cy=\"77\" r=\"10\""))
        assertFalse(master.contains("A13"))
        assertFalse(master.contains("M54 27V66A"))
        val vector=read("app/src/main/res/drawable/ic_james_focus_symbol.xml")
        assertTrue(vector.contains("M54,27L54,66"))
        assertFalse(vector.contains("A13"))
    }
    @Test fun all_phone_variants_reference_the_canonical_focus_vector() {
        listOf(
            "app/src/main/res/drawable/ic_james_foreground.xml",
            "app/src/main/res/drawable/ic_james_monochrome.xml",
            "app/src/main/res/drawable/ic_james_notification.xml"
        ).forEach {assertTrue("$it must reference the canonical mark",read(it).contains("@drawable/ic_james_focus_symbol"))}
        assertTrue(read("app/src/main/java/uk/co/james/ui/Design.kt").contains("R.drawable.ic_james_focus_symbol"))
    }
    @Test fun wear_uses_the_same_canonical_coordinates() {
        val wear=read("wear/src/main/res/drawable/ic_james_wear_focus_symbol.xml")
        assertTrue(wear.contains("M46,19A36,36 0,1 0,66,19"))
        assertTrue(wear.contains("M54,27L54,66"))
        assertTrue(read("wear/src/main/res/drawable/ic_james_wear.xml").contains("@drawable/ic_james_wear_focus_symbol"))
        assertTrue(read("wear/src/main/res/drawable/ic_james_wear_notification.xml").contains("@drawable/ic_james_wear_focus_symbol"))
    }

    private fun read(relative:String):String {
        val roots=generateSequence(File(System.getProperty("user.dir")).absoluteFile) {it.parentFile}.toList()
        return roots.map {File(it,relative)}.firstOrNull {it.isFile}?.readText()
            ?: error("Could not find $relative from ${System.getProperty("user.dir")}")
    }
}
