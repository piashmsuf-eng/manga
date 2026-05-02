package com.piashmsuf.manga.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Transparent helper that asks the user for screen-capture consent. The
 * resulting [Intent] is forwarded to [ScreenCaptureService].
 */
class MediaProjectionRequestActivity : AppCompatActivity() {

    private var continuous: Boolean = false
    private var intervalMs: Int = 1500

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(
                this,
                result.resultCode,
                result.data!!,
                continuous = continuous,
                intervalMs = intervalMs,
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        continuous = intent.getBooleanExtra(EXTRA_CONTINUOUS, false)
        intervalMs = intent.getIntExtra(EXTRA_INTERVAL_MS, 1500)
        // Avoid re-prompting the user if the activity is recreated (e.g. config change)
        // while the launcher is still waiting for a result.
        if (savedInstanceState == null) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(mpm.createScreenCaptureIntent())
        }
    }

    companion object {
        private const val EXTRA_CONTINUOUS = "extra_continuous"
        private const val EXTRA_INTERVAL_MS = "extra_interval_ms"

        fun intent(ctx: Context, continuous: Boolean = false, intervalMs: Int = 1500): Intent =
            Intent(ctx, MediaProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_CONTINUOUS, continuous)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs)
    }
}
