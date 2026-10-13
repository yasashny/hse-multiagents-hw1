package com.yasashny.agentshw1

import com.yasashny.agentshw1.settings.LlmSettings
import com.yasashny.agentshw1.settings.Settings
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsTest {

    private fun loadSettings(json: String?): Settings {
        val file = createTempDirectory("settings-test").resolve("settings.json").toFile()
        json?.let(file::writeText)
        return Settings.load(file)
    }

    @Test
    fun `missing settings file falls back to defaults`() {
        val settings = loadSettings(null)
        assertEquals(LlmSettings(), settings.llm)
    }

    @Test
    fun `llm section is parsed and blank values are ignored`() {
        val settings = loadSettings(
            """
            {
              "llm": {
                "baseUrl": "https://api.z.ai/api/paas/v4",
                "apiKey": "key-123",
                "model": "  "
              }
            }
            """.trimIndent(),
        )
        assertEquals("https://api.z.ai/api/paas/v4", settings.llm.baseUrl)
        assertEquals("key-123", settings.llm.apiKey)
        assertNull(settings.llm.model)
    }

    @Test
    fun `unknown sections and missing llm section keep defaults`() {
        val settings = loadSettings("""{"other": {"x": 1}}""")
        assertEquals(LlmSettings(), settings.llm)
    }
}
