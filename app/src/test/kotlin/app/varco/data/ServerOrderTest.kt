package app.varco.data

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The server list order: the sort modes, and the move that a drag makes in the custom order. */
class ServerOrderTest {
    private val a = Server(id = "a", name = "beta", host = "b.example.org", user = "u", lastUsed = 10)
    private val b = Server(id = "b", name = "Alpha", host = "a.example.org", user = "u", lastUsed = 30)
    private val c = Server(id = "c", name = "gamma", host = "g.example.org", user = "u", lastUsed = 10)
    private val list = listOf(a, b, c)

    @Test
    fun recentPutsTheLastUsedFirstThenSortsByName() = assertEquals(listOf(b, a, c), list.ordered(ServerOrder.RECENT))

    @Test
    fun nameIgnoresCase() = assertEquals(listOf(b, a, c), listOf(c, a, b).ordered(ServerOrder.NAME))

    @Test
    fun customKeepsTheSavedOrder() = assertSame(list, list.ordered(ServerOrder.CUSTOM))

    @Test
    fun moveDownTakesThePlaceOfTheTarget() = assertEquals(listOf(b, c, a), list.moved("a", "c"))

    @Test
    fun moveUpTakesThePlaceOfTheTarget() = assertEquals(listOf(c, a, b), list.moved("c", "a"))

    @Test
    fun moveToANeighbourSwaps() = assertEquals(listOf(b, a, c), list.moved("a", "b"))

    @Test
    fun anUnknownIdOrTheSameIdChangesNothing() {
        assertSame(list, list.moved("x", "a"))
        assertSame(list, list.moved("a", "x"))
        assertSame(list, list.moved("b", "b"))
    }

    @Test
    fun anOldOrUnknownValueInTheFileGivesTheDefault() {
        val old = Store.decode(JSONObject().put("settings", JSONObject()))
        assertEquals(ServerOrder.RECENT, old.settings.serverOrder)
        val unknown = Store.decode(JSONObject().put("settings", JSONObject().put("serverOrder", "SIZE")))
        assertEquals(ServerOrder.RECENT, unknown.settings.serverOrder)
    }
}
