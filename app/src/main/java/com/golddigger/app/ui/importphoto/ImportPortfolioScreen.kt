package com.golddigger.app.ui.importphoto

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.golddigger.app.domain.model.ParseConfidence
import com.golddigger.app.ui.components.SectionCard
import com.golddigger.app.ui.theme.PortfolioColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPortfolioScreen(
    onBack: () -> Unit,
    onImported: () -> Unit,
    viewModel: ImportPortfolioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import from photo") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val stage = state.stage) {
                ImportStage.PickSource -> PickSourceStep(error = state.error, viewModel = viewModel)
                ImportStage.Scanning -> ScanningStep()
                is ImportStage.Review -> ReviewStep(stage = stage, viewModel = viewModel)
                is ImportStage.Importing -> ImportingStep(stage)
                is ImportStage.Done -> DoneStep(stage, onImported)
            }
        }
    }
}

@Composable
private fun PickSourceStep(error: String?, viewModel: ImportPortfolioViewModel) {
    val context = LocalContext.current
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        viewModel.onPhotoCaptured(success)
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.onPhotoPicked(uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Snap your portfolio",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Take a photo or pick a screenshot of your positions list — Symbol, " +
                "Shares, Avg cost. We read it on-device and let you check everything " +
                "before it's added.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        error?.let {
            SectionCard(modifier = Modifier.padding(top = 20.dp)) {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(28.dp))

        if (hasCamera) {
            Button(
                onClick = { cameraLauncher.launch(viewModel.newCameraOutputUri()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Take a photo")
            }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(
            onClick = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Choose from gallery")
        }
    }
}

@Composable
private fun ScanningStep() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            "Reading your portfolio…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun ReviewStep(stage: ImportStage.Review, viewModel: ImportPortfolioViewModel) {
    val importCount = stage.rows.count { it.included && it.isComplete }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)) {
            Text("Check what we found", style = MaterialTheme.typography.titleMedium)
            Text(
                "Uncheck anything that's wrong, fix a field by tapping it, or add a " +
                    "row we missed. Nothing is saved until you tap Import.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(stage.rows, key = { it.id }) { row ->
                ImportRowCard(
                    row = row,
                    onToggle = { viewModel.toggleIncluded(row.id) },
                    onTicker = { viewModel.setTicker(row.id, it) },
                    onShares = { viewModel.setShares(row.id, it) },
                    onPrice = { viewModel.setPrice(row.id, it) },
                    onIsEtf = { viewModel.setIsEtf(row.id, it) },
                    onRemove = { viewModel.removeRow(row.id) },
                )
            }
            item(key = "add-row") {
                TextButton(onClick = viewModel::addBlankRow) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add a holding manually")
                }
            }
        }

        SectionCard(modifier = Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = viewModel::retake) { Text("Retake") }
                Button(onClick = viewModel::confirmImport, enabled = importCount > 0) {
                    Text(if (importCount == 1) "Import 1 holding" else "Import $importCount holdings")
                }
            }
        }
    }
}

@Composable
private fun ImportRowCard(
    row: ImportRow,
    onToggle: () -> Unit,
    onTicker: (String) -> Unit,
    onShares: (String) -> Unit,
    onPrice: (String) -> Unit,
    onIsEtf: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = row.included, onCheckedChange = { onToggle() })
            OutlinedTextField(
                value = row.tickerText,
                onValueChange = onTicker,
                label = { Text("Ticker") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            ConfidenceBadge(row)
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove row")
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = row.sharesText,
                onValueChange = onShares,
                label = { Text("Shares") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = row.priceText,
                onValueChange = onPrice,
                label = { Text("Avg cost") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("This is an ETF", style = MaterialTheme.typography.bodySmall)
            Switch(checked = row.isEtf, onCheckedChange = onIsEtf)
        }
        if (row.alreadyHeld) {
            Text(
                "Already in your portfolio — checking this combines these shares " +
                    "into your existing position, so double-check the numbers first.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (row.verified == false) {
            Text(
                "No exact symbol match found — double-check this one.",
                style = MaterialTheme.typography.labelSmall,
                color = PortfolioColors.loss,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ConfidenceBadge(row: ImportRow) {
    when {
        row.verified == true -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "Symbol verified",
            tint = PortfolioColors.gain,
            modifier = Modifier.size(20.dp),
        )
        row.verified == false -> Icon(
            Icons.Filled.WarningAmber,
            contentDescription = "Symbol not found",
            tint = PortfolioColors.loss,
            modifier = Modifier.size(20.dp),
        )
        row.confidence == ParseConfidence.LOW -> Icon(
            Icons.Filled.WarningAmber,
            contentDescription = "Needs review",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        else -> Spacer(Modifier.size(20.dp))
    }
}

@Composable
private fun ImportingStep(stage: ImportStage.Importing) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Importing ${stage.done + 1} of ${stage.total}…",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { if (stage.total > 0) stage.done / stage.total.toFloat() else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DoneStep(stage: ImportStage.Done, onImported: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.TaskAlt,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = PortfolioColors.gain,
        )
        Text(
            if (stage.imported == 1) "Imported 1 holding" else "Imported ${stage.imported} holdings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (stage.excluded > 0) {
            Text(
                "${stage.excluded} unchecked or incomplete row(s) were left out.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (stage.failed > 0) {
            Text(
                if (stage.failed == 1) {
                    "1 row failed to save — check your connection and try adding it again."
                } else {
                    "${stage.failed} rows failed to save — check your connection and try " +
                        "adding them again."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onImported, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Done")
        }
    }
}
