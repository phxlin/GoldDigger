package com.golddigger.app.ui.holding

import androidx.lifecycle.SavedStateHandle
import com.golddigger.app.data.remote.SymbolSearchResult
import com.golddigger.app.data.repository.PortfolioRepository
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
 * Covers switching between the "Cost / share" and "Total cost" chips on the
 * add/edit-holding form. Switching modes must convert the number already
 * typed, not just relabel it — otherwise, e.g., a $5,000 total cost basis
 * reappears unchanged as a $5,000 price per share.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddEditHoldingViewModelTest {

    private val repository: PortfolioRepository = mockk(relaxed = true)

    private fun viewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()) =
        AddEditHoldingViewModel(repository, savedStateHandle)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `switching from total cost to cost per share divides by shares`() = runTest {
        val vm = viewModel()
        vm.onSharesChange("50")
        vm.onCostModeChange(CostMode.TOTAL)
        vm.onCostChange("5000")

        vm.onCostModeChange(CostMode.PER_SHARE)

        assertThat(vm.state.value.costText).isEqualTo("100")
        assertThat(vm.state.value.totalCostBasis).isEqualTo(5000.0)
    }

    @Test
    fun `switching from cost per share to total cost multiplies by shares`() = runTest {
        val vm = viewModel()
        vm.onSharesChange("10")
        vm.onCostModeChange(CostMode.PER_SHARE)
        vm.onCostChange("25")

        vm.onCostModeChange(CostMode.TOTAL)

        assertThat(vm.state.value.costText).isEqualTo("250")
        assertThat(vm.state.value.totalCostBasis).isEqualTo(250.0)
    }

    @Test
    fun `preloading a holding for edit into cost-per-share mode still reflects the right price`() = runTest {
        val holdingId = 7L
        coEvery { repository.observeHoldingValuation(holdingId) } returns MutableStateFlow(
            com.golddigger.app.domain.model.HoldingValuation(
                holdingId = holdingId,
                ticker = "AAA",
                companyName = "AAA Inc",
                sector = null,
                shares = 20.0,
                costBasis = 1000.0,
                avgCost = 50.0,
                price = 55.0,
                dayChangePct = null,
                priceUpdatedAt = null,
                marketValue = 1100.0,
                gainLoss = 100.0,
                gainLossPct = 10.0,
                portfolioWeightPct = 100.0,
            ),
        )
        val vm = viewModel(SavedStateHandle(mapOf(com.golddigger.app.ui.navigation.Routes.ARG_HOLDING_ID to holdingId)))

        vm.onCostModeChange(CostMode.PER_SHARE)

        assertThat(vm.state.value.costText).isEqualTo("50")
        assertThat(vm.state.value.totalCostBasis).isEqualTo(1000.0)
    }

    @Test
    fun `switching to the same mode again leaves the text untouched`() = runTest {
        val vm = viewModel()
        vm.onSharesChange("10")
        vm.onCostModeChange(CostMode.PER_SHARE)
        vm.onCostChange("25")

        vm.onCostModeChange(CostMode.PER_SHARE)

        assertThat(vm.state.value.costText).isEqualTo("25")
    }

    /**
     * addHolding merges into an existing position rather than erroring on
     * one, so a save() that retried it after a secondary step (setInstrumentType,
     * refreshPrices) failed would silently double the shares/cost basis just
     * added — or, an earlier version of this fix that instead reconciled via
     * updateHolding using a remembered holding id could overwrite an
     * existing position with just the new lot, or (if the ticker had since
     * changed) overwrite an unrelated holding entirely. Once addHolding
     * itself succeeds, a secondary-step failure must not be retryable and
     * must never call addHolding or updateHolding again.
     */
    @Test
    fun `a secondary-step failure after addHolding succeeds still completes the save, without retrying the write`() = runTest {
        coEvery { repository.addHolding(any(), any(), any(), any(), any()) } returns 42L
        coEvery { repository.setInstrumentType(any(), any()) } throws RuntimeException("boom")
        val vm = viewModel()
        vm.onQueryChange("AAPL")
        vm.onSelectSymbol(SymbolSearchResult("AAPL", "AAPL", "Apple Inc", "Common Stock"))
        vm.onSharesChange("10")
        vm.onCostChange("100")

        vm.save()

        assertThat(vm.state.value.saved).isTrue()
        assertThat(vm.state.value.error).isNull()
        coVerify(exactly = 1) { repository.addHolding(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.updateHolding(any(), any(), any()) }
    }

    @Test
    fun `a failure in addHolding itself is retryable and reflects the form's latest values`() = runTest {
        coEvery { repository.addHolding(any(), any(), any(), any(), any()) } throws
            RuntimeException("network down") andThen 42L
        val vm = viewModel()
        vm.onQueryChange("AAPL")
        vm.onSelectSymbol(SymbolSearchResult("AAPL", "AAPL", "Apple Inc", "Common Stock"))
        vm.onSharesChange("10")
        vm.onCostChange("100")

        vm.save()
        assertThat(vm.state.value.saved).isFalse()
        assertThat(vm.state.value.error).isNotNull()

        vm.onSharesChange("15")
        vm.save()

        assertThat(vm.state.value.saved).isTrue()
        coVerify(exactly = 1) { repository.addHolding("AAPL", "Apple Inc", null, 15.0, 1500.0) }
        coVerify(exactly = 0) { repository.updateHolding(any(), any(), any()) }
    }
}
