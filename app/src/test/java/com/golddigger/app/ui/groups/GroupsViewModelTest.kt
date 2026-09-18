package com.golddigger.app.ui.groups

import app.cash.turbine.test
import com.golddigger.app.data.repository.PortfolioRepository
import com.golddigger.app.domain.model.GroupAllocation
import com.golddigger.app.domain.model.PortfolioSummary
import com.google.common.truth.Truth.assertThat
import io.mockk.every
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
 * Covers the Groups screen's split-and-sort behaviour: stock-type and ETF-type
 * groups must land in separate lists (each measured against its own 100%, per
 * [com.golddigger.app.domain.PortfolioCalculator]), and each list must be
 * ordered by current value, largest first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupsViewModelTest {

    private val allocations = MutableStateFlow<List<GroupAllocation>>(emptyList())
    private val summary = MutableStateFlow(emptySummary())
    private val repository: PortfolioRepository = mockk(relaxed = true) {
        every { observeGroupAllocations() } returns allocations
        every { observePortfolio() } returns summary
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stock-type and ETF-type groups land in separate lists`() = runTest {
        allocations.value = listOf(
            allocation("Big Tech", value = 100.0, isEtf = false),
            allocation("Total Market", value = 50.0, isEtf = true),
        )

        GroupsViewModel(repository).uiState.test {
            val state = expectMostRecentItem()
            assertThat(state.stockGroups.map { it.name }).containsExactly("Big Tech")
            assertThat(state.etfGroups.map { it.name }).containsExactly("Total Market")
        }
    }

    @Test
    fun `each list is ordered by current value, largest first`() = runTest {
        allocations.value = listOf(
            allocation("Cash", value = 10.0, isEtf = false),
            allocation("Big Tech", value = 300.0, isEtf = false),
            allocation("AI", value = 150.0, isEtf = false),
            allocation("Bonds", value = 20.0, isEtf = true),
            allocation("Nasdaq 100", value = 400.0, isEtf = true),
        )

        GroupsViewModel(repository).uiState.test {
            val state = expectMostRecentItem()
            assertThat(state.stockGroups.map { it.name })
                .containsExactly("Big Tech", "AI", "Cash").inOrder()
            assertThat(state.etfGroups.map { it.name })
                .containsExactly("Nasdaq 100", "Bonds").inOrder()
        }
    }

    @Test
    fun `no groups yields an empty state`() = runTest {
        GroupsViewModel(repository).uiState.test {
            val state = expectMostRecentItem()
            assertThat(state.isEmpty).isTrue()
        }
    }

    private fun allocation(name: String, value: Double, isEtf: Boolean) = GroupAllocation(
        groupId = name.hashCode().toLong(),
        name = name,
        targetPct = null,
        currentValue = value,
        currentPct = 0.0,
        amountToTarget = null,
        tickers = emptyList(),
        isEtfGroup = isEtf,
    )

    private fun emptySummary() = PortfolioSummary(
        totalMarketValue = 0.0,
        totalCostBasis = 0.0,
        totalGainLoss = 0.0,
        totalGainLossPct = 0.0,
        dayChangeValue = 0.0,
        holdingCount = 0,
        pricedHoldingCount = 0,
        holdings = emptyList(),
    )
}
