package com.golddigger.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.golddigger.app.MainActivity
import com.golddigger.app.data.repository.PortfolioRepository
import com.golddigger.app.di.RepositoryModule
import com.golddigger.app.domain.model.HoldingValuation
import com.golddigger.app.domain.model.PortfolioSummary
import com.golddigger.app.util.FakePortfolioRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import javax.inject.Singleton

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [RepositoryModule::class])
abstract class TestRepositoryModule {
    @Binds
    @Singleton
    abstract fun bind(impl: FakePortfolioRepository): PortfolioRepository
}

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var repository: PortfolioRepository

    private val fake get() = repository as FakePortfolioRepository

    @Test
    fun emptyPortfolioShowsFirstRunPrompt() {
        hiltRule.inject()
        composeRule.onNodeWithText("Track your first holding").assertIsDisplayed()
    }

    @Test
    fun populatedPortfolioShowsTotalValueAndHolding() {
        hiltRule.inject()
        fake.portfolio.value = PortfolioSummary(
            totalMarketValue = 1_500.0,
            totalCostBasis = 1_000.0,
            totalGainLoss = 500.0,
            totalGainLossPct = 50.0,
            dayChangeValue = 12.0,
            holdingCount = 1,
            pricedHoldingCount = 1,
            holdings = listOf(
                HoldingValuation(
                    holdingId = 1,
                    ticker = "AAA",
                    companyName = "AAA Inc",
                    sector = "Tech",
                    shares = 10.0,
                    costBasis = 1_000.0,
                    avgCost = 100.0,
                    price = 150.0,
                    dayChangePct = 1.0,
                    priceUpdatedAt = System.currentTimeMillis(),
                    marketValue = 1_500.0,
                    gainLoss = 500.0,
                    gainLossPct = 50.0,
                    portfolioWeightPct = 100.0,
                ),
            ),
        )

        // "AAA" shows in both the holdings row and the allocation legend. It isn't
        // an ETF, so it lands in the "Individual Stocks" section/chart.
        composeRule.onAllNodesWithText("AAA").onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("Individual Stocks").onFirst().assertIsDisplayed()
    }
}
