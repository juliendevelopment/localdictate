package be.arte.localdictate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.arte.localdictate.session.SessionMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { HomeScreen() } }
    }
}

@Composable
private fun HomeScreen() {
    var selectedName by rememberSaveable { mutableStateOf(SessionMode.DICTATION.name) }
    val mode = SessionMode.valueOf(selectedName)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.languages), style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { selectedName = SessionMode.DICTATION.name },
                    modifier = Modifier.weight(1f).testTag("mode-dictation")) {
                    Text(stringResource(R.string.dictation))
                }
                Button(onClick = { selectedName = SessionMode.MEETING.name },
                    modifier = Modifier.weight(1f).testTag("mode-meeting")) {
                    Text(stringResource(R.string.meeting))
                }
            }
            Text(stringResource(if (mode == SessionMode.DICTATION) R.string.dictation_duration
                else R.string.meeting_duration),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("mode-duration"))
            Text(stringResource(if (mode == SessionMode.DICTATION) R.string.dictation_description
                else R.string.meeting_description), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.foundation_notice), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.privacy_notice), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {}, enabled = false, modifier = Modifier.testTag("recording-unavailable")) {
                Text(stringResource(R.string.development_status))
            }
        }
    }
}
