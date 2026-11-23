package com.example.msgsaver

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

@Composable
fun BannerAd(
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    if (!enabled) return
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val adWidth = (configuration.screenWidthDp - 32).coerceAtLeast(1)

    val adView = remember(context, adWidth) {
        AdView(context).apply {
            // Google's official test banner ID. Replace only after testing.
            adUnitId = "ca-app-pub-3940256099942544/9214589741"
            setAdSize(
                AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                    context,
                    adWidth
                )
            )
        }
    }

    LaunchedEffect(adView) {
        adView.loadAd(AdRequest.Builder().build())
    }

    DisposableEffect(adView) {
        onDispose {
            adView.destroy()
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            factory = { adView },
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        )
    }
}
