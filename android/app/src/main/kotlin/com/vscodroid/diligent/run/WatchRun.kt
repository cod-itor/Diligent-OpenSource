package com.vscodroid.diligent.run

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/**
 * Keeps [run]'s state fresh while the calling composable is on screen and
 * the app is in the foreground; with [withLog], the log tail too. Leaving
 * the composition, or the app going to the background, stops the polling.
 */
@Composable
fun WatchRun(run: RunControl, withLog: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(run, withLog, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { run.watch(withLog) }
    }
}
