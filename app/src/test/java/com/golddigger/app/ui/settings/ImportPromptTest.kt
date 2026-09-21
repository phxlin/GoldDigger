package com.golddigger.app.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImportPromptTest {

    @Test
    fun `with no data yet importing is a plain import`() {
        val prompt = importPrompt(hasData = false)

        assertThat(prompt.title).isEqualTo("Import this backup?")
        assertThat(prompt.confirmLabel).isEqualTo("Import")
        assertThat(prompt.destructive).isFalse()
    }

    @Test
    fun `with existing data it warns that everything is replaced`() {
        val prompt = importPrompt(hasData = true)

        assertThat(prompt.title).isEqualTo("Replace your data?")
        assertThat(prompt.confirmLabel).isEqualTo("Replace")
        assertThat(prompt.destructive).isTrue()
    }

    @Test
    fun `while it is still unknown the warning is kept`() {
        assertThat(importPrompt(hasData = null).destructive).isTrue()
    }
}
