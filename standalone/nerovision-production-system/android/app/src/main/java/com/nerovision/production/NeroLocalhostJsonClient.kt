package com.nerovision.production

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class NeroLocalhostJsonClient(
    private val host: String = NeroContract.LOCALHOST,
    private val port: Int,
    private val timeoutMs: Int = 4_000,
) {
    init {
        require(host == NeroContract.LOCALHOST) { "Only localhost sockets are allowed" }
    }

    fun request(payload: JSONObject): JSONObject {
        return NeroRetry.run {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs)
                socket.soTimeout = timeoutMs
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                writer.write(payload.toString())
                writer.write("\n")
                writer.flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: "{}"
                JSONObject(line)
            }
        }
    }
}
