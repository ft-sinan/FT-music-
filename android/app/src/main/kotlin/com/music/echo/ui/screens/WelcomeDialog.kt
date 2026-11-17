package echo.music.iad1tya.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import echo.music.iad1tya.R
import kotlin.math.cos
import kotlin.math.sin

private val WebCardBackground = Color(0xFF101214)
private val WebRed = Color(0xFFE2190F)
private val WebTeal = Color(0xFF3E7C8F)
private val WebCream = Color(0xFFF6E7B8)

/**
 * A quarter spider web anchored in a corner of the card: straight spokes fanning inward and
 * concentric rings clipped by the canvas edge. Line art only - the card itself stays minimal.
 */
@Composable
private fun SpiderWebCorner(
  topEnd: Boolean,
  color: Color,
  modifier: Modifier = Modifier,
  spokes: Int = 7,
  rings: Int = 5,
) {
  Canvas(modifier) {
    val origin = if (topEnd) Offset(size.width, 0f) else Offset(0f, size.height)
    val dirX = if (topEnd) -1f else 1f
    val dirY = if (topEnd) 1f else -1f
    val maxRadius = size.width * 1.45f
    for (i in 0 until spokes) {
      val angle = (i.toFloat() / (spokes - 1)) * (Math.PI / 2).toFloat()
      drawLine(
        color = color,
        start = origin,
        end = origin + Offset(dirX * cos(angle) * maxRadius, dirY * sin(angle) * maxRadius),
        strokeWidth = 1.6.dp.toPx(),
      )
    }
    for (r in 1..rings) {
      drawCircle(
        color = color,
        radius = maxRadius * (r.toFloat() / (rings + 1.2f)),
        center = origin,
        style = Stroke(width = 1.4.dp.toPx()),
      )
    }
  }
}

@Composable
fun WelcomeDialog(onDismissRequest: () -> Unit) {
  Dialog(
    onDismissRequest = onDismissRequest,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth().padding(24.dp),
      shape = RoundedCornerShape(32.dp),
      color = WebCardBackground,
    ) {
      Box(modifier = Modifier.fillMaxWidth()) {
        SpiderWebCorner(
          topEnd = true,
          color = WebTeal.copy(alpha = 0.30f),
          modifier = Modifier.align(Alignment.TopEnd).size(190.dp),
        )
        SpiderWebCorner(
          topEnd = false,
          color = WebCream.copy(alpha = 0.14f),
          modifier = Modifier.align(Alignment.BottomStart).size(130.dp),
          spokes = 5,
          rings = 4,
        )
        Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          AsyncImage(
            model = R.mipmap.web_lines_icon,
            contentDescription = null,
            modifier =
              Modifier.size(88.dp)
                .background(WebCardBackground, RoundedCornerShape(22.dp))
                .border(1.dp, WebCream.copy(alpha = 0.35f), RoundedCornerShape(22.dp)),
          )
          Spacer(Modifier.height(24.dp))
          Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.Center,
          )
          Spacer(Modifier.height(10.dp))
          Text(
            text = stringResource(R.string.welcome_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.62f),
            textAlign = TextAlign.Center,
          )
          Spacer(Modifier.height(30.dp))
          Button(
            onClick = onDismissRequest,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = WebRed,
                contentColor = WebCream,
              ),
          ) {
            Text(stringResource(R.string.welcome_get_started), fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }
  }
}
