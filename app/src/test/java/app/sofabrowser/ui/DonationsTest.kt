package app.sofabrowser.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DonationsTest {
    /** The repository root: unit tests run from the app module. */
    private val root = File("..").takeIf { File(it, "README.md").isFile } ?: File(".")

    @Test
    fun theQrScriptRendersExactlyTheseAddresses() {
        val rendered = File(root, "tools/render-donate-qr.sh").readLines()
            .filter { it.startsWith("render ") }.map { it.split(" ")[2] }
        assertEquals(Donations.coins.map { it.address }, rendered)
    }

    @Test
    fun theReadmeShowsEveryAddress() {
        val readme = File(root, "README.md").readText()
        Donations.coins.forEach { assertTrue(it.address, readme.contains("\n${it.address}\n")) }
    }

    @Test
    fun longAddressesAreCutInTheMiddle() {
        assertEquals("sonalder@cake.cash", Donations.short("sonalder@cake.cash"))
        assertEquals("0xC88455aa2b…C37C4D5a", Donations.short("0xC88455aa2b489Ba83Ed9606EA9627466C37C4D5a"))
    }
}
