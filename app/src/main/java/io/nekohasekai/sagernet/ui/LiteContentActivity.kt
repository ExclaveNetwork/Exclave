package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R

/** Hosts full-screen tools that remain available from the focused Lite interface. */
class LiteContentActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_empty)

        if (savedInstanceState == null) {
            val content = when (intent.getIntExtra(EXTRA_DESTINATION, DESTINATION_TOOLS)) {
                DESTINATION_GROUPS -> GroupFragment()
                DESTINATION_ROUTES -> RouteFragment()
                DESTINATION_LOGS -> LogcatFragment()
                else -> ToolsFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_holder, content)
                .commit()
        }
    }

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(findViewById(R.id.fragment_holder), text, Snackbar.LENGTH_LONG)

    companion object {
        const val DESTINATION_ROUTES = 1
        const val DESTINATION_TOOLS = 2
        const val DESTINATION_GROUPS = 3
        const val DESTINATION_LOGS = 4
        private const val EXTRA_DESTINATION = "destination"

        fun intentFor(context: Context, destination: Int): Intent =
            Intent(context, LiteContentActivity::class.java)
                .putExtra(EXTRA_DESTINATION, destination)
    }
}
