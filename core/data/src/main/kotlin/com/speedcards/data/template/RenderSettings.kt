package com.speedcards.data.template

import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Current display choices; the app keeps this in sync with the user's settings. */
@Singleton
class RenderSettings @Inject constructor() {
    val options = MutableStateFlow(RenderOptions())
}
