package cz.majkey.prepis

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

@Composable
internal fun PrivacyLink() {
    val context = LocalContext.current
    TextButton(onClick = {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://majkey25.github.io/transcriber-android/")))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.browser_unavailable, Toast.LENGTH_LONG).show()
        }
    }) { Text(stringResource(R.string.privacy_and_terms)) }
}
