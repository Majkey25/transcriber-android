package cz.majkey.prepis

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

class HttpDeadlineTest {
    @Test
    fun `deadline disconnects a blocked response and refuses further requests`(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val releaseServer = CountDownLatch(1)
            val headersSent = CountDownLatch(1)
            val serving = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val request = socket.getInputStream().bufferedReader()
                    while (!request.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n".toByteArray())
                        flush()
                    }
                    headersSent.countDown()
                    releaseServer.await(5, TimeUnit.SECONDS)
                }
            }
            try {
                val deadline = HttpDeadline(300)
                val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()
                val started = System.nanoTime()
                assertFailsWith<IOException> {
                    deadline.execute(request) { it.body.byteStream().read() }
                }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
                assertTrue(headersSent.await(1, TimeUnit.SECONDS))
                assertFailsWith<SocketTimeoutException> { deadline.check() }
                assertFailsWith<SocketTimeoutException> { deadline.execute(request) { it.code } }
            } finally {
                releaseServer.countDown()
                serving.join(2_000)
            }
        }
    }

    @Test
    fun `job cancellation closes an active response read`(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val releaseServer = CountDownLatch(1)
            val serving = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val request = socket.getInputStream().bufferedReader()
                    while (!request.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n".toByteArray())
                        flush()
                    }
                    releaseServer.await(10, TimeUnit.SECONDS)
                }
            }
            val reading = CompletableDeferred<Unit>()
            val finishedReading = CountDownLatch(1)
            val job = launch {
                val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()
                HttpDeadline(10_000).execute(request) { response ->
                    reading.complete(Unit)
                    try { response.body.byteStream().read() } finally { finishedReading.countDown() }
                }
            }
            try {
                withTimeout(3_000) { reading.await() }
                withTimeout(3_000) { job.cancelAndJoin() }
                assertTrue(finishedReading.await(2, TimeUnit.SECONDS))
                assertTrue(job.isCancelled)
            } finally {
                job.cancelAndJoin()
                releaseServer.countDown()
                serving.join(2_000)
            }
        }
    }

    @Test
    fun `job cancellation stops an upload to a server that does not read`(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val releaseServer = CountDownLatch(1)
            val serving = thread(isDaemon = true) {
                server.accept().use { releaseServer.await(10, TimeUnit.SECONDS) }
            }
            val writing = CompletableDeferred<Unit>()
            val finishedWriting = CountDownLatch(1)
            val body = object : RequestBody() {
                override fun contentType() = null
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) {
                    val bytes = ByteArray(64 * 1024)
                    writing.complete(Unit)
                    try { repeat(4_096) { sink.write(bytes) } } finally { finishedWriting.countDown() }
                }
            }
            val job = launch {
                val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/").post(body).build()
                HttpDeadline(10_000).execute(request) { it.code }
            }
            try {
                withTimeout(3_000) { writing.await() }
                withTimeout(3_000) { job.cancelAndJoin() }
                assertTrue(finishedWriting.await(2, TimeUnit.SECONDS))
                assertTrue(job.isCancelled)
            } finally {
                job.cancelAndJoin()
                releaseServer.countDown()
                serving.join(2_000)
            }
        }
    }

    @Test
    fun `cleanup sends DELETE after operation expiry and job cancellation`(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val method = CompletableDeferred<String>()
            val serving = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val input = socket.getInputStream().bufferedReader()
                    method.complete(input.readLine().substringBefore(' '))
                    while (!input.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        flush()
                    }
                }
            }
            val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/files/test").delete().build()
            assertFailsWith<SocketTimeoutException> { HttpDeadline(0).execute(request) { it.code } }
            val ready = CompletableDeferred<Unit>()
            val job = launch {
                try {
                    ready.complete(Unit)
                    awaitCancellation()
                } finally {
                    deleteCloudFile(request)
                }
            }
            try {
                ready.await()
                withTimeout(3_000) { job.cancelAndJoin() }
                assertEquals("DELETE", withTimeout(1_000) { method.await() })
            } finally {
                job.cancelAndJoin()
                serving.join(2_000)
            }
        }
    }
}
