package io.github.ynkcc.moredisplay.eye

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Invisible trampoline behind the Quick Settings tile and the home-screen widget: toggles the eye on the last
 * watched display, then disappears. (An activity is needed because only a foreground activity may start the
 * viewer.)
 */
class EyeToggleActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_SOURCE = "source"

        fun intent(context: Context, source: EyeControl): Intent =
            Intent(context, EyeToggleActivity::class.java)
                .putExtra(EXTRA_SOURCE, source.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.getStringExtra(EXTRA_SOURCE)
            ?.let { name -> EyeControl.entries.firstOrNull { it.name == name } }
            ?: EyeControl.WIDGET

        if (!EyeSettings.isEnabled(source)) {
            toast("\"${source.label}\" is turned off in MoreDisplay → Settings")
            finish()
            return
        }
        lifecycleScope.launch {
            val watching = ViewerController.watching.value
            val target = watching ?: runCatching { ViewerController.lastTarget() }.getOrNull()
            if (target == null) {
                toast("No display to show yet: tap the eye on a display card in MoreDisplay first")
            } else {
                toast(ViewerController.apply(this@EyeToggleActivity, EyeRequest.TOGGLE, target))
            }
            finish()
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
}
