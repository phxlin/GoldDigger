package com.golddigger.app.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Today's date as the device currently sees it — formatted in the device locale
 * and the device's *current* time zone, with the zone's short name appended
 * (e.g. "Sunday, September 7 · PDT").
 *
 * It re-reads the zone whenever Android broadcasts a time / time-zone / locale
 * change (so it follows the phone when it travels and auto-updates its zone),
 * and also re-checks every 30 s as a safety net for the midnight rollover.
 */
@Composable
fun rememberDeviceDate(): String {
    val context = LocalContext.current
    val formatter = remember {
        DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
    }

    fun currentLabel(): String {
        val zone = ZoneId.systemDefault()
        val date = LocalDate.now(zone).format(formatter)
        val tz = TimeZone.getDefault()
        val abbreviation = tz.getDisplayName(tz.inDaylightTime(Date()), TimeZone.SHORT)
        return "$date · $abbreviation"
    }

    var label by remember { mutableStateOf(currentLabel()) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                label = currentLabel()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            val fresh = currentLabel()
            if (fresh != label) label = fresh
        }
    }

    return label
}
