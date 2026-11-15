package app.varco.data

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The move that a drag makes in the server list, and old store files with fields that are now gone. */
class ServerMoveTest {
    private val a = Server(id = "a", name = "a", host = "a.example.org", user = "u")
    private val b = Server(id = "b", name = "b", host = "b.example.org", user = "u")
    private val c = Server(id = "c", name = "c", host = "c.example.org", user = "u")
    private val list = listOf(a, b, c)

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
    fun aFileWithLastUsedAndServerOrderOpensInTheSavedOrder() {
        // Version 0.2.6 and earlier wrote these fields.
        val server = { id: String -> JSONObject().put("id", id).put("name", id).put("host", "$id.example.org").put("user", "u").put("lastUsed", 5L) }
        val file = JSONObject()
            .put("servers", org.json.JSONArray().put(server("b")).put(server("a")))
            .put("settings", JSONObject().put("serverOrder", "NAME"))
        val state = Store.decode(file)
        assertEquals(listOf("b", "a"), state.servers.map { it.id })
        assertEquals(Settings(), state.settings)
    }
}
