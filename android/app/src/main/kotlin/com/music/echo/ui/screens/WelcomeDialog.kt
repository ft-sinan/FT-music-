package echo.music.iad1tya.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import echo.music.iad1tya.R

@Composable
fun WelcomeDialog(onDismissRequest: () -> Unit) {
  Dialog(
    onDismissRequest = onDismissRequest,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Card(
      modifier = Modifier.fillMaxWidth().padding(24.dp),
      shape = RoundedCornerShape(28.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        AsyncImage(
          model = R.mipmap.ic_launcher,
          contentDescription = null,
          modifier = Modifier.size(96.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer),
        )
        Spacer(Modifier.height(20.dp))
        Text(
          text = "Welcome to FT Music",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
          onClick = onDismissRequest,
          modifier = Modifier.fillMaxWidth().height(50.dp),
          shape = RoundedCornerShape(16.dp),
        ) {
          Text("Continue", fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}
