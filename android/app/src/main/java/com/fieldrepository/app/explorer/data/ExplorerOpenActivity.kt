package com.fieldrepository.app.explorer.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

enum class ExplorerOpenAction { VIEW, SHARE }

fun explorerOpenAction(action: String?): ExplorerOpenAction? = when (action) {
    ExplorerOpenActivity.ACTION_VIEW -> ExplorerOpenAction.VIEW
    ExplorerOpenActivity.ACTION_SHARE -> ExplorerOpenAction.SHARE
    else -> null
}

class ExplorerOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val id = intent?.getStringExtra(ExplorerDownloads.KEY_ID) ?: return
        val action = explorerOpenAction(intent.action) ?: return
        val job = ExplorerDownloads.store(applicationContext).get(id) ?: return
        when (action) {
            ExplorerOpenAction.VIEW -> ExplorerOpen.open(this, job)
            ExplorerOpenAction.SHARE -> ExplorerOpen.share(this, job)
        }
    }

    companion object {
        const val ACTION_VIEW = "explorer.OPEN_FILE"
        const val ACTION_SHARE = "explorer.SHARE_FILE"

        fun intent(context: Context, jobId: String, action: ExplorerOpenAction): Intent =
            Intent(context, ExplorerOpenActivity::class.java)
                .setAction(if (action == ExplorerOpenAction.SHARE) ACTION_SHARE else ACTION_VIEW)
                .putExtra(ExplorerDownloads.KEY_ID, jobId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
