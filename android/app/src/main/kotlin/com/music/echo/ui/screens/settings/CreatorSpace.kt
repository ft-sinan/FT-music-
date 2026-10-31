package echo.music.iad1tya.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import echo.music.iad1tya.BuildConfig
import echo.music.iad1tya.R

/** A small FT Music signature, inspired by FT Vault's Creator Space. */
@Composable
fun CreatorSpace(onCredits: () -> Unit) {
  val context = LocalContext.current
  var flipped by remember { mutableStateOf(false) }
  val rotation by animateFloatAsState(if (flipped) 180f else 0f, label = "creatorEmblem")
  fun open(uri: String) {
    try {
      context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    } catch (_: ActivityNotFoundException) {
      Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
  }

  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(28.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        "CREATOR SPACE",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.align(Alignment.Start),
      )
      Spacer(Modifier.height(14.dp))
      Box(
        modifier = Modifier.size(82.dp).graphicsLayer { rotationY = rotation }
          .clip(CircleShape)
          .background(
            Brush.linearGradient(
              listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
            ),
          )
          .clickable { flipped = !flipped },
        contentAlignment = Alignment.Center,
      ) {
        if (rotation <= 90f) {
          Text("S", color = MaterialTheme.colorScheme.onPrimary, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        } else {
          Icon(
            painterResource(R.drawable.music_note),
            contentDescription = "Music",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(35.dp).graphicsLayer { rotationY = 180f },
          )
        }
      }
      Spacer(Modifier.height(12.dp))
      Text("Sinan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
      Text("FT Music maintainer", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text("@ft-sinan · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      Spacer(Modifier.height(14.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CreatorLink("GitHub", R.drawable.github) { open("https://github.com/ft-sinan") }
        CreatorLink("Telegram", R.drawable.telegram) { open("https://t.me/Exotatic") }
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { open("mailto:Mail.tosinaan@gmail.com") }.padding(6.dp),
        ) {
          Icon(Icons.Rounded.Email, contentDescription = null, modifier = Modifier.size(22.dp))
          Text("Email", style = MaterialTheme.typography.labelSmall)
        }
        CreatorLink("Website", R.drawable.website) { open("https://sinanmk.my.id") }
      }
      Spacer(Modifier.height(12.dp))
      Text(
        "Built on Echo Music by Aditya and contributors.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(8.dp))
      OutlinedButton(onClick = onCredits) { Text("Credits & licences") }
    }
  }
}

@Composable
private fun CreatorLink(label: String, icon: Int, onClick: () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(6.dp),
  ) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
    Text(label, style = MaterialTheme.typography.labelSmall)
  }
}
