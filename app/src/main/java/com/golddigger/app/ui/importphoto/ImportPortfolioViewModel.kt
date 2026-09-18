package com.golddigger.app.ui.importphoto

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.golddigger.app.data.ocr.PortfolioPhotoDecoder
import com.golddigger.app.data.ocr.TextRecognizerService
import com.golddigger.app.data.remote.isLikelyEtf
import com.golddigger.app.data.repository.PortfolioRepository
import com.golddigger.app.domain.PortfolioOcrParser
import com.golddigger.app.domain.model.ParseConfidence
import com.golddigger.app.domain.model.ParsedHolding
import com.golddigger.app.ui.common.asEditableNumber
import com.golddigger.app.ui.common.filterToNumericInput
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** One editable holding candidate on the review screen. */
data class ImportRow(
    val id: Long,
    val included: Boolean,
    val tickerText: String,
    val sharesText: String,
    val priceText: String,
    val confidence: ParseConfidence,
    val sourceText: String,
    /** null = not checked yet / checking; true = an exact symbol match exists; false = none found. Advisory only — never auto-unchecks a row. */
    val verified: Boolean? = null,
    /** True when this ticker is already a holding in the portfolio — importing it adds a separate lot, not an update. */
    val alreadyHeld: Boolean = false,
    /** Whether this row is an ETF rather than an individual stock — drives which Portfolio section it lands in. */
    val isEtf: Boolean = false,
    /** Once the user has picked [isEtf] by hand, auto-verification stops overwriting it. */
    val isEtfTouched: Boolean = false,
) {
    val shares: Double? get() = sharesText.toDoubleOrNull()?.takeIf { it > 0 }
    val price: Double? get() = priceText.toDoubleOrNull()?.takeIf { it >= 0 }
    val isComplete: Boolean get() = tickerText.isNotBlank() && shares != null && price != null
}

sealed interface ImportStage {
    data object PickSource : ImportStage
    data object Scanning : ImportStage
    data class Review(val rows: List<ImportRow>) : ImportStage
    data class Importing(val done: Int, val total: Int) : ImportStage
    data class Done(
        val imported: Int,
        /** Rows never attempted — left unchecked or missing a ticker/shares/price. */
        val excluded: Int,
        /** Rows that were checked and complete but whose write failed (e.g. a DB error). */
        val failed: Int,
    ) : ImportStage
}

data class ImportUiState(
    val stage: ImportStage = ImportStage.PickSource,
    val error: String? = null,
)

/**
 * Drives the whole "import a portfolio photo" flow in one place — capture or
 * pick a photo, run on-device OCR, turn the result into editable candidates,
 * let the user confirm/fix them, then write the confirmed ones through the
 * exact same [PortfolioRepository.addHolding] path manual entry uses (so
 * price sync, Finnhub name/sector lookup, and the rate limiter all apply
 * unchanged). Nothing here is a shortcut around the review step: even a
 * HIGH-confidence row is just pre-checked, never written without the user
 * tapping Import. A candidate whose ticker is already a holding is flagged
 * and starts **unchecked**: importing it merges into the existing position
 * (see [PortfolioRepository.addHolding]) rather than creating a duplicate, so
 * the risk isn't a stray extra row — it's bad OCR data quietly blending into
 * a position you already track accurately. That still deserves an active
 * decision rather than a silent default, so re-importing an overlapping
 * photo (e.g. re-scanning the same positions list) doesn't just work through
 * unattended.
 */
@HiltViewModel
class ImportPortfolioViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoDecoder: PortfolioPhotoDecoder,
    private val recognizer: TextRecognizerService,
    private val repository: PortfolioRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state = _state.asStateFlow()

    private var pendingCameraFile: File? = null
    private var nextRowId = 0L
    private val verifyJobs = mutableMapOf<Long, Job>()
    /** Uppercased tickers already in the portfolio as of the last scan, for the "already held" flag. */
    private var heldTickers: Set<String> = emptySet()

    /** A fresh cache file's FileProvider URI, to hand the system camera app as its capture target. */
    fun newCameraOutputUri(): Uri {
        val dir = File(context.cacheDir, "import").apply { mkdirs() }
        val file = File(dir, "portfolio_${System.currentTimeMillis()}.jpg")
        pendingCameraFile = file
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun onPhotoCaptured(success: Boolean) {
        val file = pendingCameraFile
        pendingCameraFile = null
        if (!success || file == null || !file.exists()) {
            file?.delete()
            return
        }
        scan(Uri.fromFile(file), ownedFile = file)
    }

    fun onPhotoPicked(uri: Uri?) {
        if (uri != null) scan(uri, ownedFile = null)
    }

    /**
     * @param ownedFile the camera-capture temp file to delete once it's been
     *   read, if this scan came from [onPhotoCaptured] — a gallery pick's Uri
     *   is owned by the system, not us, so that path passes null.
     */
    private fun scan(uri: Uri, ownedFile: File?) {
        _state.value = ImportUiState(stage = ImportStage.Scanning)
        viewModelScope.launch {
            runCatching {
                val bitmap = withContext(Dispatchers.Default) { photoDecoder.decode(uri) }
                    ?: error("Couldn't read that photo.")
                try {
                    val tokens = recognizer.recognizeWords(bitmap)
                    PortfolioOcrParser.parse(tokens)
                } finally {
                    bitmap.recycle()
                }
            }.onSuccess { candidates -> onScanned(candidates) }
                .onFailure { e ->
                    _state.value = ImportUiState(
                        stage = ImportStage.PickSource,
                        error = e.message ?: "Couldn't scan that photo.",
                    )
                }
            ownedFile?.delete()
        }
    }

    private suspend fun onScanned(candidates: List<ParsedHolding>) {
        if (candidates.isEmpty()) {
            _state.value = ImportUiState(
                stage = ImportStage.PickSource,
                error = "Couldn't find anything that looks like a holding in that photo. " +
                    "Try a clearer, more zoomed-in shot of the positions list.",
            )
            return
        }
        heldTickers = runCatching { repository.observePortfolio().first() }
            .getOrNull()?.holdings.orEmpty()
            .map { it.ticker.uppercase() }
            .toSet()
        val rows = candidates.map(::toRow)
        _state.value = ImportUiState(stage = ImportStage.Review(rows))
        rows.forEach(::scheduleVerify)
    }

    private fun toRow(c: ParsedHolding): ImportRow {
        val alreadyHeld = c.ticker.uppercase() in heldTickers
        return ImportRow(
            id = nextRowId++,
            // Pre-check only what parsed cleanly and isn't already tracked —
            // a match against an existing holding needs an active decision.
            included = c.confidence == ParseConfidence.HIGH && !alreadyHeld,
            tickerText = c.ticker,
            sharesText = c.shares?.asEditableNumber() ?: "",
            priceText = c.avgPrice?.asEditableNumber() ?: "",
            confidence = c.confidence,
            sourceText = c.sourceText,
            alreadyHeld = alreadyHeld,
        )
    }

    fun retake() {
        verifyJobs.values.forEach { it.cancel() }
        verifyJobs.clear()
        _state.value = ImportUiState()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // --- Row editing -----------------------------------------------------

    fun toggleIncluded(id: Long) = updateRows { rows ->
        rows.map { if (it.id == id) it.copy(included = !it.included) else it }
    }

    fun setTicker(id: Long, value: String) {
        val cleaned = value.uppercase().trim()
        updateRows { rows ->
            rows.map {
                if (it.id == id) {
                    it.copy(tickerText = cleaned, verified = null, alreadyHeld = cleaned in heldTickers)
                } else {
                    it
                }
            }
        }
        reviewRows().firstOrNull { it.id == id }?.let(::scheduleVerify)
    }

    fun setShares(id: Long, value: String) = updateRows { rows ->
        rows.map { if (it.id == id) it.copy(sharesText = value.filterToNumericInput()) else it }
    }

    fun setPrice(id: Long, value: String) = updateRows { rows ->
        rows.map { if (it.id == id) it.copy(priceText = value.filterToNumericInput()) else it }
    }

    fun setIsEtf(id: Long, value: Boolean) = updateRows { rows ->
        rows.map { if (it.id == id) it.copy(isEtf = value, isEtfTouched = true) else it }
    }

    fun removeRow(id: Long) {
        verifyJobs.remove(id)?.cancel()
        updateRows { rows -> rows.filterNot { it.id == id } }
    }

    fun addBlankRow() = updateRows { rows ->
        rows + ImportRow(
            id = nextRowId++,
            included = true,
            tickerText = "",
            sharesText = "",
            priceText = "",
            confidence = ParseConfidence.LOW,
            sourceText = "",
        )
    }

    private fun reviewRows(): List<ImportRow> = (_state.value.stage as? ImportStage.Review)?.rows.orEmpty()

    private fun updateRows(transform: (List<ImportRow>) -> List<ImportRow>) {
        _state.update { s ->
            val stage = s.stage
            if (stage is ImportStage.Review) s.copy(stage = stage.copy(rows = transform(stage.rows))) else s
        }
    }

    /** Best-effort, debounced exact-symbol check via the existing throttled search — a hint, never a gate. */
    private fun scheduleVerify(row: ImportRow) {
        verifyJobs[row.id]?.cancel()
        verifyJobs[row.id] = viewModelScope.launch {
            delay(350)
            val ticker = row.tickerText
            if (ticker.isBlank()) return@launch
            val match = repository.searchSymbols(ticker).getOrDefault(emptyList())
                .firstOrNull { it.symbol.equals(ticker, ignoreCase = true) }
            updateRows { rows ->
                rows.map {
                    if (it.id != row.id) {
                        it
                    } else {
                        it.copy(
                            verified = match != null,
                            // Never overrides a choice the user already made by hand.
                            isEtf = if (!it.isEtfTouched && match != null) match.isLikelyEtf() else it.isEtf,
                        )
                    }
                }
            }
        }
    }

    // --- Import ------------------------------------------------------

    /** A row that has cleared [ImportRow.isComplete] — shares/price are resolved once here, not re-checked with `!!` at each use. */
    private data class PendingImport(
        val ticker: String,
        val shares: Double,
        val avgCost: Double,
        val isEtf: Boolean,
    )

    fun confirmImport() {
        val rows = reviewRows()
        // Gated on isComplete (not a hand-rolled subset of the same checks) so
        // this can never drift from ImportRow.isComplete and let an
        // included-but-blank-ticker row slip through as if it were valid —
        // that row would otherwise reach addHolding() and create a phantom
        // holding with an empty ticker.
        val toImport = rows.mapNotNull { row ->
            if (!row.included || !row.isComplete) return@mapNotNull null
            val shares = row.shares ?: return@mapNotNull null
            val price = row.price ?: return@mapNotNull null
            PendingImport(row.tickerText.trim().uppercase(), shares, price, row.isEtf)
        }
        val excluded = rows.size - toImport.size
        if (toImport.isEmpty()) return
        viewModelScope.launch {
            // Counts only writes that actually succeeded — the loop used to
            // report every attempted row as imported even when addHolding()
            // failed, so a write error silently vanished into the success
            // count instead of showing up as a distinct failure.
            var succeeded = 0
            toImport.forEachIndexed { index, item ->
                _state.value = ImportUiState(stage = ImportStage.Importing(index, toImport.size))
                val addResult = runCatching {
                    repository.addHolding(
                        ticker = item.ticker,
                        companyName = null,
                        sector = null,
                        shares = item.shares,
                        costBasis = item.shares * item.avgCost,
                    )
                }
                if (addResult.isSuccess) {
                    succeeded++
                    // The position is already committed at this point, so a
                    // failure here must never demote this row to "failed" —
                    // that would tell the user it's safe to retry, and
                    // retrying addHolding for an already-imported ticker
                    // merges in a second lot on top of the first rather than
                    // replacing anything. Worst case the type stays at its
                    // default (individual stock), fixable by hand afterward.
                    runCatching { repository.setInstrumentType(item.ticker, item.isEtf) }
                }
            }
            runCatching { repository.refreshPrices(force = true) }
            // Excluded (never attempted — unchecked/incomplete) and failed
            // (attempted, write threw) are reported separately: they call for
            // different user reactions — "go check that row" vs. "retry".
            val failed = toImport.size - succeeded
            _state.value = ImportUiState(stage = ImportStage.Done(succeeded, excluded, failed))
        }
    }
}
