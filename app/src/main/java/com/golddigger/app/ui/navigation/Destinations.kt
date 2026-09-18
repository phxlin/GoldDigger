package com.golddigger.app.ui.navigation

/** Every navigable location in the app. */
object Routes {
    /**
     * The single NavHost destination hosting the four swipeable top-level tabs
     * (Portfolio, Formation, Groups, Settings) in a [androidx.compose.foundation.pager.HorizontalPager] —
     * see [com.golddigger.app.ui.navigation.GoldDiggerApp]. Everything else
     * (holding detail, add/edit, group detail, photo import) is pushed on top
     * of it as an ordinary NavHost destination.
     */
    const val HOME = "home"

    const val ADD_HOLDING = "holdings/new"
    const val ADD_HOLDING_WITH_TICKER = "holdings/new?ticker={ticker}"
    fun addHolding(ticker: String? = null) =
        if (ticker.isNullOrBlank()) ADD_HOLDING else "holdings/new?ticker=$ticker"

    const val IMPORT_PHOTO = "holdings/import-photo"

    const val EDIT_HOLDING = "holding/{holdingId}/edit"
    fun editHolding(holdingId: Long) = "holding/$holdingId/edit"

    const val HOLDING_DETAIL = "holding/{holdingId}"
    fun holdingDetail(holdingId: Long) = "holding/$holdingId"

    const val GROUP_DETAIL = "groups/{groupId}"
    fun groupDetail(groupId: Long) = "groups/$groupId"

    const val ARG_HOLDING_ID = "holdingId"
    const val ARG_GROUP_ID = "groupId"
    const val ARG_TICKER = "ticker"
}
