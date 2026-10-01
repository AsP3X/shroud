package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.SystemAppClock
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.net.UserCardDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The share-code lookup with its username fallback (P10a): iOS
 * `ContactInviteParserTests.swift:134-201` (`aShareCodeFallsBackToAUsernameOnlyWhenItCouldBeOne`,
 * `aShareCodeThatIsNotFoundIsTriedAsAUsername`, `aFoundShareCodeIsNeverTriedAsAUsername`,
 * `onlyANotFoundWithAUsernameShapeFallsBack`) and web-parity §22.3's `MockWebServer` case, plus one
 * Add Contact round trip through the real `ShroudApi` wire.
 */
class InviteLookupTest {
    private lateinit var server: MockWebServer
    private val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val me = UUID.fromString("8f14e45f-ceea-467a-9575-3a6b7a1e6c0e")
    private val niklas = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath.removePrefix("/api/v1/")
                paths += "${request.method} $path"
                return when {
                    path == "users/by-code/NIKLASVORBERG" -> MockResponse(code = 404, body = """{"error":{"code":"NOT_FOUND","message":"User not found."}}""")
                    path == "users/by-username/niklasvorberg" -> MockResponse(body = """{"id":"$niklas","username":"niklasvorberg","share_code":"QWERTY2345"}""")
                    path == "contacts/requests" && request.method == "POST" -> MockResponse(
                        code = 201,
                        body = """{"id":"44444444-4444-4444-4444-444444444444","from_user_id":"$me","to_user_id":"$niklas","status":"pending","created_at":"2026-10-01T08:00:00.123456Z","user":{"id":"$me","username":"me"}}""",
                    )
                    path == "contacts" -> MockResponse(body = """{"contacts":[]}""")
                    path == "contacts/requests" -> MockResponse(body = """{"requests":[]}""")
                    else -> MockResponse(code = 500, body = """{"error":{"code":"INTERNAL_ERROR","message":"unexpected $path"}}""")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun api() = ShroudApi(ApiClient(baseUrl = { server.url("/api/v1").toString() }, json = json))

    @Test
    fun aShareCodeFallsBackToAUsernameOnlyWhenItCouldBeOne() {
        assertEquals("niklasvorberg", InviteLookup.usernameFallback("NIKLASVORBERG"))
        assertEquals("janecooper", InviteLookup.usernameFallback("JANECOOPER"))
        assertEquals("abcd234567", InviteLookup.usernameFallback("ABCD234567"))
        assertNull(InviteLookup.usernameFallback("MÜLLERHANS"))
        assertNull(InviteLookup.usernameFallback("ABCD-2345!"))
        assertNull(InviteLookup.usernameFallback("A".repeat(33)))
    }

    @Test
    fun aShareCodeThatIsNotFoundIsTriedAsAUsername() = runTest {
        val card = UserCardDto(UUID.randomUUID(), "niklasvorberg", "QWERTY2345")
        val calls = mutableListOf<String>()
        val found = InviteLookup.lookUpShareCode(
            "NIKLASVORBERG",
            byCode = { code ->
                calls += "by-code/$code"
                throw ApiError.Server("NOT_FOUND", "User not found.", 404)
            },
            byUsername = { name ->
                calls += "by-username/$name"
                card
            },
        )
        assertEquals(card, found)
        assertEquals(listOf("by-code/NIKLASVORBERG", "by-username/niklasvorberg"), calls)
    }

    @Test
    fun aFoundShareCodeIsNeverTriedAsAUsername() = runTest {
        val card = UserCardDto(UUID.randomUUID(), "jane_cooper", "ABCD234567")
        val calls = mutableListOf<String>()
        val found = InviteLookup.lookUpShareCode(
            "ABCD234567",
            byCode = { code ->
                calls += "by-code/$code"
                card
            },
            byUsername = { name ->
                calls += "by-username/$name"
                throw ApiError.Server("NOT_FOUND", "User not found.", 404)
            },
        )
        assertEquals(card, found)
        assertEquals(listOf("by-code/ABCD234567"), calls)
    }

    /** Offline, a rate limit or a server error is the answer; a code that cannot be a username keeps its 404. */
    @Test
    fun onlyANotFoundWithAUsernameShapeFallsBack() = runTest {
        val offline = ApiError.Transport("The Internet connection appears to be offline.")
        val limited = ApiError.Server("RATE_LIMITED", "Too many requests. Try again later.", 429)
        val notFound = ApiError.Server("NOT_FOUND", "User not found.", 404)
        for ((code, error) in listOf("NIKLASVORBERG" to offline, "NIKLASVORBERG" to limited, "MÜLLERHANS" to notFound)) {
            val calls = mutableListOf<String>()
            try {
                InviteLookup.lookUpShareCode(
                    code,
                    byCode = { throw error },
                    byUsername = { name ->
                        calls += "by-username/$name"
                        throw notFound
                    },
                )
                fail("expected $error")
            } catch (e: ApiError) {
                assertSame(error, e)
            }
            assertTrue(calls.isEmpty())
        }
    }

    /** web-parity §22.3: `by-code/NIKLASVORBERG` 404 → `by-username/niklasvorberg` 200 → the card. */
    @Test
    fun theFallbackGoesOverTheWire() = runTest {
        val backend = ShroudContactsBackend(api())
        val card = InviteLookup.card(backend, "tok", ContactInviteParser.Invite.ShareCode("NIKLASVORBERG"))
        assertEquals(UUID.fromString(niklas), card.id)
        assertEquals(listOf("GET users/by-code/NIKLASVORBERG", "GET users/by-username/niklasvorberg"), paths.toList())
    }

    /** Add Contact end to end on the real wire: lookup, fallback, request body, the refresh after it. */
    @Test
    fun addingAContactGoesOverTheWire() {
        val mainThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + mainThread)
        try {
            val contacts = ContactsController(
                backend = ShroudContactsBackend(api()),
                session = { session(me) },
                events = emptyFlow(),
                scope = scope,
                clock = SystemAppClock,
                main = mainThread,
                usernameOrder = icuOrder(),
            )
            val outcome = runBlocking { contacts.add("niklasvorberg") }
            assertEquals(AddContactOutcome.Requested("niklasvorberg"), outcome)
            assertEquals(
                listOf("GET users/by-code/NIKLASVORBERG", "GET users/by-username/niklasvorberg", "POST contacts/requests"),
                paths.take(3),
            )
            assertEquals(setOf("GET contacts", "GET contacts/requests"), paths.drop(3).toSet())
            server.takeRequest() // by-code
            server.takeRequest() // by-username
            val post = server.takeRequest()
            assertEquals("POST", post.method)
            assertEquals(niklas, Json.parseToJsonElement(post.body!!.utf8()).jsonObject["user_id"]!!.jsonPrimitive.content)
        } finally {
            scope.cancel()
            mainThread.close()
        }
    }

    @Test
    fun theNotFoundCodeIsTheServers() {
        assertEquals(ErrorCodes.NOT_FOUND, notFound().code)
    }
}
