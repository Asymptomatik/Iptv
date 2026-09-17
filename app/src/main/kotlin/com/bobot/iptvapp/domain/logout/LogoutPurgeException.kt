package com.bobot.iptvapp.domain.logout

/**
 * A step of the logout purge could not be completed.
 *
 * Thrown rather than swallowed on purpose: the orchestrator's contract is that a failed step leaves
 * the pending marker set, keeps the credentials in place, and surfaces a retryable error — none of
 * which can happen if the failure is invisible.
 */
class LogoutPurgeException(message: String, cause: Throwable? = null) : Exception(message, cause)
