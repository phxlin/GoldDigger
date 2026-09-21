package com.golddigger.app.ui.settings

/** The word the user has to type before "Delete all data" can go ahead. */
const val DELETE_CONFIRM_PHRASE = "DELETE"

/**
 * Whether [typed] confirms a typed-confirmation dialog for [phrase]. Case and surrounding
 * whitespace are ignored: phone keyboards capitalise the first letter and append a space after a
 * suggestion, and neither should stop someone who plainly typed the word.
 */
fun matchesConfirmPhrase(typed: String, phrase: String = DELETE_CONFIRM_PHRASE): Boolean =
    typed.trim().equals(phrase, ignoreCase = true)
