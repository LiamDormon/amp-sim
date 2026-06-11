package org.example.audio

import org.junit.jupiter.api.Test

class JackClientTest {
    @Test
    fun testJackConnection() {
        val client = JackClient("TestClient")
        try {
            client.open(autoStart = false)
            println("Successfully connected to JACK!")
            client.close()
        } catch (e: Exception) {
            println("Connection failed: ${e.message}")
            e.printStackTrace()
        }
    }
}
