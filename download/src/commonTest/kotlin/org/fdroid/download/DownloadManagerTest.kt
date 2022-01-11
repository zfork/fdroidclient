package org.fdroid.download

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.features.ServerResponseException
import io.ktor.http.HttpHeaders.Authorization
import io.ktor.http.HttpHeaders.ETag
import io.ktor.http.HttpHeaders.Range
import io.ktor.http.HttpHeaders.UserAgent
import io.ktor.http.HttpStatusCode.Companion.InternalServerError
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.http.HttpStatusCode.Companion.PartialContent
import io.ktor.http.headersOf
import io.ktor.util.toByteArray
import org.fdroid.getRandomString
import org.fdroid.runSuspend
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class DownloadManagerTest {

    private val userAgent = getRandomString()
    private val mirrors = listOf(Mirror("http://example.org"), Mirror("http://example.net/"))
    private val downloadRequest = DownloadRequest("foo", mirrors)

    @Test
    fun testUserAgent() = runSuspend {
        val mockEngine = MockEngine { respondOk() }
        val downloadManager = DownloadManager(userAgent, null, httpClientEngine = mockEngine)

        downloadManager.head(downloadRequest)
        downloadManager.get(downloadRequest)

        mockEngine.requestHistory.forEach { request ->
            assertEquals(userAgent, request.headers[UserAgent])
        }
    }

    @Test
    fun testQueryString() = runSuspend {
        val id = getRandomString()
        val version = getRandomString()
        val queryString = "id=$id&client_version=$version"
        val mockEngine = MockEngine { respondOk() }
        val downloadManager = DownloadManager(userAgent, queryString, httpClientEngine = mockEngine)

        downloadManager.head(downloadRequest)
        downloadManager.get(downloadRequest)

        mockEngine.requestHistory.forEach { request ->
            assertEquals(id, request.url.parameters["id"])
            assertEquals(version, request.url.parameters["client_version"])
        }
    }

    @Test
    fun testBasicAuth() = runSuspend {
        val downloadRequest = DownloadRequest("foo", mirrors, "Foo", "Bar")

        val mockEngine = MockEngine { respondOk() }
        val downloadManager = DownloadManager(userAgent, null, httpClientEngine = mockEngine)

        downloadManager.head(downloadRequest)
        downloadManager.get(downloadRequest)

        mockEngine.requestHistory.forEach { request ->
            assertEquals("Basic Rm9vOkJhcg==", request.headers[Authorization])
        }
    }

    @Test
    fun testHeadETagCheck() = runSuspend {
        val downloadRequest = DownloadRequest("foo", mirrors, "Foo", "Bar")

        val eTag = getRandomString()
        val headers = headersOf(ETag, eTag)
        val mockEngine = MockEngine { respond("", headers = headers) }
        val downloadManager = DownloadManager(userAgent, null, httpClientEngine = mockEngine)

        // ETag is considered changed when none (null) passed into the request
        assertTrue(downloadManager.head(downloadRequest)!!.eTagChanged)
        // Random ETag will be different than what we expect
        assertTrue(downloadManager.head(downloadRequest, getRandomString())!!.eTagChanged)
        // Expected ETag should match response, so it hasn't changed
        assertFalse(downloadManager.head(downloadRequest, eTag)!!.eTagChanged)
    }

    @Test
    fun testDownload() = runSuspend {
        val content = Random.nextBytes(1024)
        val downloadRequest = DownloadRequest("foo", mirrors, "Foo", "Bar")

        val mockEngine = MockEngine { respond(content) }
        val downloadManager = DownloadManager(userAgent, null, httpClientEngine = mockEngine)

        assertContentEquals(content, downloadManager.get(downloadRequest).toByteArray())
    }

    @Test
    fun testResumeDownload() = runSuspend {
        val skipBytes = Random.nextInt(0, 1024)
        val content = Random.nextBytes(1024)
        val downloadRequest = DownloadRequest("foo", mirrors, "Foo", "Bar")

        var requestNum = 1
        val mockEngine = MockEngine { request ->
            assertNotNull(request.headers[Range])
            val (fromStr, endStr) = request.headers[Range]!!.replace("bytes=", "").split('-')
            val from = fromStr.toIntOrNull() ?: fail("No valid content range ${request.headers[Range]}")
            assertEquals("", endStr)
            assertEquals(skipBytes, from)
            if (requestNum++ == 1) respond(content.copyOfRange(from, content.size), PartialContent)
            else respond(content, OK)
        }
        val downloadManager = DownloadManager(userAgent, null, httpClientEngine = mockEngine)

        // first request gets only the skipped bytes
        assertContentEquals(content.copyOfRange(skipBytes, content.size),
            downloadManager.get(downloadRequest, skipBytes.toLong()).toByteArray())
        // second request fails, because it responds with OK and full content
        val exception = assertFailsWith<ServerResponseException> {
            downloadManager.get(downloadRequest, skipBytes.toLong())
        }
        assertEquals("Server error(http://example.net/foo: 200 OK. Text: \"expected 206\"", exception.message)
    }

    @Test
    fun testMirrorFallback() = runSuspend {
        val downloadRequest = DownloadRequest("foo", mirrors, "Foo", "Bar")

        val mockEngine = MockEngine { respondError(InternalServerError) }
        val downloadManager = DownloadManager(userAgent, null, httpClientEngine = mockEngine)

        assertNull(downloadManager.head(downloadRequest))
        assertFailsWith<ServerResponseException> {
            downloadManager.get(downloadRequest)
        }

        // assert that URLs for each mirror get tried
        val urls = mockEngine.requestHistory.map { request -> request.url.toString() }.toSet()
        assertEquals(setOf("http://example.org/foo", "http://example.net/foo"), urls)
    }

    @Test
    fun testFirstMirrorSuccess() = runSuspend {
        val downloadRequest = DownloadRequest("foo", mirrors, "Foo", "Bar")

        val mockEngine = MockEngine { respondOk() }
        val downloadManager = DownloadManager(userAgent, null, httpClientEngine = mockEngine)

        assertNotNull(downloadManager.head(downloadRequest))
        downloadManager.get(downloadRequest)

        // assert there is only one request per API call using one of the mirrors
        assertEquals(2, mockEngine.requestHistory.size)
        mockEngine.requestHistory.forEach { request ->
            val url = request.url.toString()
            assertTrue(url == "http://example.org/foo" || url == "http://example.net/foo")
        }
    }

}
