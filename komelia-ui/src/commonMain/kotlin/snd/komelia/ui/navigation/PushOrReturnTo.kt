package snd.komelia.ui.navigation

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator

/**
 * Goes to [screen], reusing the instance already on the stack if there is one.
 *
 * Voyager keys a screen by its identity, and for a series, a book or a library
 * that identity is the id it was opened with. Pushing a second screen with a key
 * already on the stack does not make a second page: it makes
 * `SaveableStateHolder` throw `Key <id>:screen was used multiple times`, which
 * on Android is an uncaught exception on the main thread — the app dies.
 *
 * The path is ordinary, not exotic. Open a series, open one of its books, tap
 * the parent series on that book's page: the series is already underneath, and
 * the app closes. Same for the "other editions" links between series, and for
 * anything that offers a library you have already been through.
 *
 * The guard was written once before, inline, for the reader's exit intents. It
 * belongs here instead, because every one of those callers needs it and nothing
 * about the reader made it special.
 *
 * Returning is also the better behaviour on its own merits. The screen the
 * reader wants is the one they left — scrolled where they left it, with the
 * state it had — and a fresh copy would throw that away while quietly growing a
 * back stack that has to be walked twice on the way out.
 */
fun Navigator.pushOrReturnTo(screen: Screen) {
    if (items.any { it.key == screen.key }) popUntil { it.key == screen.key }
    else push(screen)
}
