package app.sofabrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTreeTest {

    /** News › Tech › Phones, News › Sport, Recipes at the top. */
    private fun sample() = FolderTree(listOf(
        Folder(1, null, "News", 0), Folder(2, 1, "Tech", 0), Folder(3, 2, "Phones", 0),
        Folder(4, 1, "Sport", 0), Folder(5, null, "Recipes", 0),
    ))

    /** A chain of [n] folders, each in the one before: ids 1 to [n], [n] deepest. */
    private fun chain(n: Int) = FolderTree((1..n).map { Folder(it.toLong(), if (it == 1) null else it - 1L, "Level $it", 0) })

    @Test fun childrenAToZWhateverTheirCaseAndAccents() {
        val t = FolderTree(listOf("banana", "Éclair", "Apple", "cherry").mapIndexed { i, n -> Folder(i + 1L, null, n, 0) })
        assertEquals(listOf("Apple", "banana", "cherry", "Éclair"), t.children(null).map { it.title })
        assertTrue(t.children(99).isEmpty())
    }

    @Test fun pathsAndDepths() {
        val t = sample()
        assertEquals(listOf("News", "Tech", "Phones"), t.pathNames(3))
        assertEquals(3, t.depth(3))
        assertEquals(0, t.depth(null))
        assertTrue(t.path(null).isEmpty())
        assertTrue(t.path(42).isEmpty())
        assertEquals(setOf(1L, 2L, 3L, 4L), t.subtree(1))
        assertEquals(setOf(5L), t.subtree(5))
    }

    @Test fun aLoopFromADamagedDatabaseEnds() {
        val t = FolderTree(listOf(Folder(1, 2, "A", 0), Folder(2, 1, "B", 0)))
        assertTrue(t.path(1).size <= 3)
        assertEquals(setOf(1L, 2L), t.subtree(1))
    }

    @Test fun findIgnoresCaseAndSpaces() {
        val t = sample()
        assertEquals(2L, t.find(1, "  tech ")?.id)
        assertNull(t.find(null, "Tech"))
    }

    @Test fun aFolderNeverGoesIntoItself() {
        val t = sample()
        assertFalse(t.canMove(1, 1))
        assertFalse(t.canMove(1, 2))
        assertFalse(t.canMove(1, 3))
        assertTrue(t.canMove(2, null))
        assertTrue(t.canMove(2, 5))
        assertTrue(t.canMove(5, 3))
        assertFalse(t.canMove(99, null))
        assertFalse(t.canMove(5, 99))
    }

    @Test fun nothingGoesPastTheDeepestLevel() {
        val t = chain(FolderTree.MAX_DEPTH)
        assertFalse(t.canHold(FolderTree.MAX_DEPTH.toLong()))
        assertTrue(t.canHold(FolderTree.MAX_DEPTH - 1L))
        assertTrue(t.canHold(null))
        assertFalse(t.canHold(99))
        // Level 19 holds Level 20; moving it (with nothing inside) under Level 18's neighbour is fine...
        t.add(Folder(100, null, "Two deep", 0))
        t.add(Folder(101, 100, "Inside", 0))
        assertTrue(t.canMove(100, FolderTree.MAX_DEPTH - 2L))
        // ...but a folder two levels tall can't go under level 19: it would reach level 21.
        assertFalse(t.canMove(100, FolderTree.MAX_DEPTH - 1L))
    }

    @Test fun outlineIsDepthFirstWithDepths() {
        val out = sample().outline().map { (f, d) -> f.title to d }
        assertEquals(listOf("News" to 1, "Sport" to 2, "Tech" to 2, "Phones" to 3, "Recipes" to 1), out)
    }

    @Test fun resolveReusesFoldersAndMakesWhatIsMissing() {
        val t = sample()
        var next = 10L
        val made = ArrayList<Pair<Long?, String>>()
        val make = { parent: Long?, title: String -> made += parent to title; next++ }
        assertEquals(2L, t.resolve(listOf("news", "TECH"), make))
        assertTrue(made.isEmpty())
        val games = t.resolve(listOf("News", "Tech", "Games"), make)
        assertEquals(10L, games)
        assertEquals(games, t.resolve(listOf("News", "Tech", " games "), make))
        assertEquals(listOf<Pair<Long?, String>>(2L to "Games"), made)
        assertNull(t.resolve(emptyList(), make))
        // Names that clean to nothing are passed over.
        assertEquals(5L, t.resolve(listOf("  ", "Recipes"), make))
    }

    @Test fun resolveStopsAtTheDeepestLevel() {
        val t = FolderTree(emptyList())
        var next = 1L
        val id = t.resolve((1..30).map { "L$it" }) { _, _ -> next++ }
        assertEquals(FolderTree.MAX_DEPTH, t.depth(id))
        assertEquals("L20", t[id]?.title)
    }

    @Test fun addAndRemoveKeepTheTreeInStep() {
        val t = sample()
        t.add(Folder(2, 5, "Tech", 0)) // moved into Recipes
        assertEquals(listOf("Sport"), t.children(1).map { it.title })
        assertEquals(listOf("Recipes", "Tech", "Phones"), t.pathNames(3))
        t.remove(4)
        assertTrue(t.children(1).isEmpty())
        assertEquals(4, t.size)
    }

    @Test fun cleanNameIsOneTrimmedLine() {
        assertEquals("News and weather", FolderTree.cleanName("  News\n and\tweather  "))
        assertEquals("", FolderTree.cleanName(" \n "))
        assertEquals(FolderTree.MAX_NAME, FolderTree.cleanName("x".repeat(500)).length)
    }
}
