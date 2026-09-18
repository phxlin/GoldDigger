package com.golddigger.app.ui.holding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.golddigger.app.data.repository.PortfolioRepository
import com.golddigger.app.data.remote.SymbolSearchResult
import com.golddigger.app.data.remote.isLikelyEtf
import com.golddigger.app.ui.common.asEditableNumber
import com.golddigger.app.ui.common.filterToNumericInput
import com.golddigger.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CostMode { TOTAL, PER_SHARE }

data class AddEditUiState(
    val editing: Boolean = false,
    val query: String = "",
    val results: List<SymbolSearchResult> = emptyList(),
    val searching: Boolean = false,
    val selectedTicker: String? = null,
    val selectedName: String? = null,
    val selectedSector: String? = null,
    val sharesText: String = "",
    val costText: String = "",
    val costMode: CostMode = CostMode.PER_SHARE,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    /** True when [selectedTicker] is already a holding — saving will merge into it, not add a second lot. */
    val alreadyHeld: Boolean = false,
    /** Whether [selectedTicker] is an ETF rather than an individual stock — drives which Portfolio section it lands in. */
    val isEtf: Boolean = false,
) {
    val shares: Double? get() = sharesText.toDoubleOrNull()?.takeIf { it > 0 }
    val costInput: Double? get() = costText.toDoubleOrNull()?.takeIf { it >= 0 }
    val totalCostBasis: Double?
        get() {
            val c = costInput ?: return null
            return when (costMode) {
                CostMode.TOTAL -> c
                CostMode.PER_SHARE -> (shares ?: return null) * c
            }
        }
    val canSave: Boolean
        get() = selectedTicker != null && shares != null && totalCostBasis != null && !saving
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddEditHoldingViewModel @Inject constructor(
    private val repository: PortfolioRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val holdingId: Long? =
        savedStateHandle.get<Long>(Routes.ARG_HOLDING_ID)?.takeIf { it != 0L }
    private val presetTicker: String? = savedStateHandle.get<String>(Routes.ARG_TICKER)

    private val _state = MutableStateFlow(AddEditUiState(editing = holdingId != null))
    val state = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        holdingId?.let { preloadForEdit(it) }
        presetTicker?.let { onQueryChange(it) }

        queryFlow
            .debounce(300)
            .map { it.trim() }
            .distinctUntilChanged()
            .filter { it.length >= 1 && _state.value.selectedTicker == null }
            .onEach { _state.update { s -> s.copy(searching = true) } }
            .flatMapLatest { q -> flow { emit(repository.searchSymbols(q)) } }
            .onEach { result ->
                _state.update { s ->
                    s.copy(
                        searching = false,
                        results = result.getOrDefault(emptyList()).take(15),
                        error = result.exceptionOrNull()?.let { humanize(it) },
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun preloadForEdit(id: Long) {
        viewModelScope.launch {
            val v = repository.observeHoldingValuation(id).firstOrNull() ?: return@launch
            _state.update {
                it.copy(
                    selectedTicker = v.ticker,
                    selectedName = v.companyName,
                    selectedSector = v.sector,
                    sharesText = v.shares.asEditableNumber(),
                    costText = v.costBasis.asEditableNumber(),
                    costMode = CostMode.TOTAL,
                    isEtf = v.isEtf,
                )
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value, selectedTicker = null, selectedName = null) }
        queryFlow.value = value
    }

    fun onSelectSymbol(result: SymbolSearchResult) {
        _state.update {
            it.copy(
                query = result.symbol,
                selectedTicker = result.symbol,
                selectedName = result.description.ifBlank { result.symbol },
                results = emptyList(),
                alreadyHeld = false,
                isEtf = result.isLikelyEtf(),
            )
        }
        // Editing an existing holding trivially "already holds" itself, so this
        // is only meaningful for a brand-new Add.
        if (holdingId == null) checkAlreadyHeld(result.symbol)
    }

    fun onIsEtfChange(value: Boolean) = _state.update { it.copy(isEtf = value) }

    private fun checkAlreadyHeld(ticker: String) {
        viewModelScope.launch {
            val held = repository.observePortfolio().firstOrNull()
                ?.holdings.orEmpty()
                .any { it.ticker.equals(ticker, ignoreCase = true) }
            // Ignore a stale answer if the selection moved on while this was in flight.
            _state.update { if (it.selectedTicker == ticker) it.copy(alreadyHeld = held) else it }
        }
    }

    fun onSharesChange(v: String) = _state.update { it.copy(sharesText = v.filterToNumericInput()) }
    fun onCostChange(v: String) = _state.update { it.copy(costText = v.filterToNumericInput()) }

    /**
     * Switching modes relabels the same field between "total paid" and "price
     * per share" — without converting the number itself, the figure typed
     * under the old mode would be shown unchanged under the new label (e.g. a
     * $5,000 total cost reappearing as a $5,000 price per share). Convert it
     * using the current share count so the same cost basis is preserved.
     */
    fun onCostModeChange(mode: CostMode) = _state.update { s ->
        if (mode == s.costMode) return@update s
        val current = s.costInput
        val shares = s.shares
        val convertedText = if (current != null && shares != null && shares > 0) {
            when (mode) {
                CostMode.PER_SHARE -> current / shares
                CostMode.TOTAL -> current * shares
            }.asEditableNumber()
        } else {
            s.costText
        }
        s.copy(costMode = mode, costText = convertedText)
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val ticker = s.selectedTicker ?: return
        val shares = s.shares ?: return
        val costBasis = s.totalCostBasis ?: return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            // Only the primary write's failure is retryable. addHolding
            // merges into an existing position rather than erroring on one,
            // so if refreshPrices/setInstrumentType were in this same
            // runCatching and either threw, a retry would call addHolding
            // again and silently double the shares/cost basis just merged
            // in — or, if it had instead reconciled via updateHolding using
            // a remembered holding id, that id could belong to a position
            // that already existed before this add (overwriting the rest of
            // it with just the new lot) or to a since-changed ticker
            // (overwriting an unrelated holding). Once the primary write
            // succeeds the add is done; treat what's left as best-effort,
            // same as ImportPortfolioViewModel does for this exact flag.
            val primary = runCatching {
                if (holdingId != null) {
                    repository.updateHolding(holdingId, shares, costBasis)
                } else {
                    repository.addHolding(
                        ticker = ticker,
                        companyName = s.selectedName,
                        sector = s.selectedSector,
                        shares = shares,
                        costBasis = costBasis,
                    )
                }
            }
            primary.onSuccess {
                runCatching {
                    if (holdingId == null) repository.refreshPrices(force = true)
                    repository.setInstrumentType(ticker, s.isEtf)
                }
                _state.update { it.copy(saving = false, saved = true) }
            }.onFailure { e ->
                _state.update { it.copy(saving = false, error = humanize(e)) }
            }
        }
    }

    private fun humanize(e: Throwable): String = e.message ?: "Something went wrong"
}
