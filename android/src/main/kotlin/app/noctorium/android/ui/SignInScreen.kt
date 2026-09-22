package app.noctorium.android.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.noctorium.android.WebViewSignIn
import app.noctorium.core.AppState
import app.noctorium.domain.ProviderType
import kotlinx.coroutines.launch

/**
 * The service's own sign-in page, inside Noctorium.
 *
 * The password is typed into Google's or SoundCloud's real page, never into anything of ours, and the
 * session it produces is kept in this app's cookie store. That is the same bargain the desktop makes with
 * embedded Chromium; Android simply already has the browser.
 *
 * Finishing is a decision the listener makes, not one guessed from the URL. Both services bounce through
 * several addresses on the way in and land somewhere different depending on the account, so the honest
 * arrangement is a Done button — pressed when they can see they are signed in — which then checks whether
 * a usable session really was left behind rather than assuming it.
 */
@Composable
internal fun SignInScreen(provider: ProviderType, state: AppState, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else close() }

    // Always from a clean store. Reusing whatever was there opens a page already signed in as the previous
    // account, asks the listener for nothing, and harvests the same stale cookies again — the loop this
    // project has already had once, from the desktop browser.
    LaunchedEffect(provider) { WebViewSignIn.clearCookies() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.navigationBars),
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(close) { Icon(Icons.Default.Close, "Cancel") }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Sign in to ${provider.displayName}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    Text(
                        "Press Done when you are signed in.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                Button(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        problem = null
                        scope.launch {
                            val saved = WebViewSignIn.saveSession(provider)
                            if (saved == null) {
                                problem = "You are not signed in yet — the page gave back no session. " +
                                    "Finish signing in, then press Done."
                                saving = false
                                return@launch
                            }
                            if (provider == ProviderType.SOUNDCLOUD) {
                                state.completeSoundCloudSignIn(
                                    saved.toString(),
                                    WebViewSignIn.soundCloudToken(),
                                )
                            } else {
                                state.completeYouTubeSignIn(saved.toString())
                            }
                            saving = false
                            close()
                        }
                    },
                ) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Done")
                    }
                }
            }

            problem?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

            Box(Modifier.weight(1f)) {
                AndroidView(
                    factory = { context ->
                        WebView(context).also { view ->
                            WebViewSignIn.configure(view) { loading = false }
                            view.loadUrl(WebViewSignIn.startUrlFor(provider))
                            webView = view
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
