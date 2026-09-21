package com.golddigger.app.ui.settings

/** What the confirmation dialog for importing a backup says. */
data class ImportPrompt(
    val title: String,
    val text: String,
    val confirmLabel: String,
    /** True when confirming throws away data the user has now. */
    val destructive: Boolean,
)

/**
 * Importing replaces everything, but that only matters if there is something to replace. With no
 * holdings yet it is a plain import; otherwise it keeps the warning. [hasData] is null while the
 * holdings are still loading, and that counts as "has data": showing the warning by mistake is
 * harmless, hiding it by mistake isn't.
 */
fun importPrompt(hasData: Boolean?): ImportPrompt =
    if (hasData == false) {
        ImportPrompt(
            title = "Import this backup?",
            text = "This loads the holdings, groups and price history from the backup file into GoldDigger.",
            confirmLabel = "Import",
            destructive = false,
        )
    } else {
        ImportPrompt(
            title = "Replace your data?",
            text = "Importing replaces all holdings, groups and price history currently on this device " +
                "with the contents of the backup file.",
            confirmLabel = "Replace",
            destructive = true,
        )
    }
