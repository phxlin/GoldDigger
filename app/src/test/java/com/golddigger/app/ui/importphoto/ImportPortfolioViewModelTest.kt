package com.golddigger.app.ui.importphoto

import android.content.Context
import com.golddigger.app.data.ocr.PortfolioPhotoDecoder
import com.golddigger.app.data.ocr.TextRecognizerService
import com.golddigger.app.data.remote.SymbolSearchResult
import com.golddigger.app.data.repository.PortfolioRepository
import com.golddigger.app.domain.model.ParseConfidence
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Covers [ImportPortfolioViewModel.confirmImport]'s row bookkeeping — the
 * area two separate review passes flagged as bug-prone and untested: a
 * blank-ticker row must never reach the repository, a write failure must
 * never be reported as an import, and a row whose position was already
 * committed must never be reported as safe to retry just because a
 * secondary write (the ETF/stock-type flag) failed afterward.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportPortfolioViewModelTest {

    private val repository: PortfolioRepository = mockk(relaxed = true)

    private fun viewModel() = ImportPortfolioViewModel(
        context = mockk<Context>(relaxed = true),
        photoDecoder = mockk<PortfolioPhotoDecoder>(relaxed = true),
        recognizer = mockk<TextRecognizerService>(relaxed = true),
        repository = repository,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { repository.searchSymbols(any()) } returns Result.success(emptyList<SymbolSearchResult>())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Seeds the Review stage directly — the only real entry point is the OCR scan pipeline. */
    private fun ImportPortfolioViewModel.setReviewRows(rows: List<ImportRow>) {
        val field = ImportPortfolioViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(this) as MutableStateFlow<ImportUiState>).value =
            ImportUiState(stage = ImportStage.Review(rows))
    }

    private fun row(
        id: Long,
        ticker: String,
        included: Boolean = true,
        shares: String = "10",
        price: String = "100",
    ) = ImportRow(
        id = id,
        included = included,
        tickerText = ticker,
        sharesText = shares,
        priceText = price,
        confidence = ParseConfidence.HIGH,
        sourceText = "",
    )

    @Test
    fun `a blank-ticker row is excluded, never reaches the repository`() = runTest {
        val vm = viewModel()
        vm.setReviewRows(
            listOf(
                row(1, ticker = ""),
                row(2, ticker = "AAPL"),
            ),
        )

        vm.confirmImport()

        coVerify(exactly = 0) { repository.addHolding(ticker = "", any(), any(), any(), any()) }
        val done = vm.state.value.stage as ImportStage.Done
        assertThat(done.imported).isEqualTo(1)
        assertThat(done.excluded).isEqualTo(1)
        assertThat(done.failed).isEqualTo(0)
    }

    @Test
    fun `a row whose write throws is reported as failed, not imported`() = runTest {
        coEvery {
            repository.addHolding("BADTICKER", any(), any(), any(), any())
        } throws RuntimeException("boom")
        val vm = viewModel()
        vm.setReviewRows(listOf(row(1, ticker = "BADTICKER")))

        vm.confirmImport()

        val done = vm.state.value.stage as ImportStage.Done
        assertThat(done.imported).isEqualTo(0)
        assertThat(done.failed).isEqualTo(1)
    }

    @Test
    fun `a committed position is reported as imported even if setInstrumentType fails afterward`() = runTest {
        coEvery { repository.addHolding("MSFT", any(), any(), any(), any()) } returns 1L
        coEvery { repository.setInstrumentType("MSFT", any()) } throws RuntimeException("boom")
        val vm = viewModel()
        vm.setReviewRows(listOf(row(1, ticker = "MSFT")))

        vm.confirmImport()

        // The position was already committed by addHolding — reporting this
        // row as "failed" would invite the user to retry, and retrying
        // addHolding for an already-imported ticker merges in a second lot
        // of shares on top of the first rather than replacing anything.
        val done = vm.state.value.stage as ImportStage.Done
        assertThat(done.imported).isEqualTo(1)
        assertThat(done.failed).isEqualTo(0)
    }
}
