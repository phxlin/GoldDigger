package com.golddigger.app.ui.holding

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.golddigger.app.data.local.entity.GroupEntity
import com.golddigger.app.data.repository.PortfolioRepository
import com.golddigger.app.domain.model.HoldingValuation
import com.golddigger.app.ui.navigation.Routes
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Covers the group-membership picker's type filter: a holding should only be
 * offered groups of its own type (stock <-> stocks-type group, ETF <-> ETFs-
 * type group) to add, mirroring [com.golddigger.app.ui.groups.GroupDetailViewModel]'s
 * own filter the other way round — otherwise a stock can get dragged into an
 * ETF-type group's percentage (or vice versa) with nothing to stop it. A
 * membership that predates this rule must still show up so there's a way to
 * remove it, even though its type no longer matches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HoldingDetailViewModelTest {

    private val groups = MutableStateFlow<List<GroupEntity>>(emptyList())
    private val memberIds = MutableStateFlow<List<Long>>(emptyList())
    private val repository: PortfolioRepository = mockk(relaxed = true) {
        every { observeHoldingValuation(1L) } returns flowOf(holding(isEtf = false))
        every { observeGroups() } returns groups
        every { observeGroupIdsForTicker("AVGO") } returns memberIds
        every { observePriceHistory(any(), any()) } returns flowOf(emptyList())
        every { observeNews(any()) } returns flowOf(emptyList())
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = HoldingDetailViewModel(
        repository,
        SavedStateHandle(mapOf(Routes.ARG_HOLDING_ID to 1L)),
    )

    private fun holding(isEtf: Boolean) = HoldingValuation(
        holdingId = 1L,
        ticker = "AVGO",
        companyName = "Broadcom Inc",
        sector = "Semiconductors",
        shares = 1.0,
        costBasis = 100.0,
        avgCost = 100.0,
        price = 100.0,
        dayChangePct = 0.0,
        priceUpdatedAt = 0L,
        marketValue = 100.0,
        gainLoss = 0.0,
        gainLossPct = 0.0,
        portfolioWeightPct = 0.0,
        isEtf = isEtf,
    )

    private fun group(id: Long, name: String, isEtfGroup: Boolean) =
        GroupEntity(id = id, name = name, targetAllocationPct = null, isEtfGroup = isEtfGroup)

    @Test
    fun `a stock is only offered stock-type groups to join`() = runTest {
        groups.value = listOf(
            group(1L, "Semis", isEtfGroup = false),
            group(2L, "Dow Jones", isEtfGroup = true),
        )

        viewModel().uiState.test {
            val state = expectMostRecentItem() as HoldingDetailUiState.Loaded
            assertThat(state.allGroups.map { it.name }).containsExactly("Semis")
        }
    }

    @Test
    fun `a pre-existing mismatched membership still shows up so it can be removed`() = runTest {
        groups.value = listOf(
            group(1L, "Semis", isEtfGroup = false),
            group(2L, "Dow Jones", isEtfGroup = true),
        )
        // AVGO (a stock) is already a member of the ETF-type "Dow Jones" group,
        // from before this rule existed.
        memberIds.value = listOf(2L)

        viewModel().uiState.test {
            val state = expectMostRecentItem() as HoldingDetailUiState.Loaded
            assertThat(state.allGroups.map { it.name }).containsExactly("Semis", "Dow Jones")
            assertThat(state.memberGroupIds).contains(2L)
        }
    }
}
