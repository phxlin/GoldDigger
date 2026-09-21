package com.golddigger.app.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeleteConfirmationTest {

    @Test
    fun `the phrase confirms regardless of case or surrounding spaces`() {
        assertThat(matchesConfirmPhrase("DELETE")).isTrue()
        assertThat(matchesConfirmPhrase("delete")).isTrue()
        assertThat(matchesConfirmPhrase("Delete ")).isTrue()
        assertThat(matchesConfirmPhrase("  dElEtE  ")).isTrue()
    }

    @Test
    fun `anything else does not`() {
        assertThat(matchesConfirmPhrase("")).isFalse()
        assertThat(matchesConfirmPhrase("   ")).isFalse()
        assertThat(matchesConfirmPhrase("DELET")).isFalse()
        assertThat(matchesConfirmPhrase("DELETE ALL")).isFalse()
        assertThat(matchesConfirmPhrase("de lete")).isFalse()
    }

    @Test
    fun `a custom phrase is matched the same way`() {
        assertThat(matchesConfirmPhrase("erase", phrase = "ERASE")).isTrue()
        assertThat(matchesConfirmPhrase("delete", phrase = "ERASE")).isFalse()
    }
}
