package com.posecam.core.sync

/**
 * Who the upload queue works for. Every queued recording belongs to one marketplace account; a pass over the queue only
 * touches the signed-in account's rows. Rows of any other account stay exactly as they are until that account signs in
 * again (and are shown as "Belongs to another account").
 */
class AccountScope(
    /** The signed-in account's id, or null when nobody is: then there is nothing to upload. */
    val accountId: () -> String?,
    /** Run before each pass over the queue: refreshes an access token that is about to expire. Best effort, never throws. */
    val refreshIfExpiring: suspend () -> Unit = {},
) {
    companion object {
        /** A fixed signed-in account (tests, and the single-account case). */
        fun fixed(accountId: String?) = AccountScope({ accountId })
    }
}
