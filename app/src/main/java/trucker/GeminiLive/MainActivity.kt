package trucker.GeminiLive

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import trucker.GeminiLive.network.GeminiState
import trucker.GeminiLive.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                KeepScreenOn()
                val viewModel: GeminiViewModel = viewModel()
                val uiState = viewModel.uiState.value

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        // Permission granted
                    }
                }

                LaunchedEffect(Unit) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CopilotScreen(
                        uiState = uiState,
                        onToggle = { viewModel.toggleConnection() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
fun CopilotScreen(
    uiState: GeminiUiState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Truck AI Logo
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Truck AI Logo",
            modifier = Modifier
                .size(180.dp)
                .padding(bottom = 8.dp)
        )

        // Header
        Text(
            text = "Swift Copilot",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Main Interaction Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (!uiState.isConnected) {
                Button(
                    onClick = onToggle,
                    modifier = Modifier.size(160.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
                ) {
                    Text("START", fontSize = 28.sp, fontWeight = FontWeight.Black)
                }
            } else {
                StateIndicator(uiState.aiState, uiState.currentTool, onToggle)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Debug Log Console (Re-added for troubleshooting)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f),
            shape = MaterialTheme.shapes.small,
            color = Color.Black
        ) {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(uiState.log.size) {
                if (uiState.log.isNotEmpty()) {
                    listState.animateScrollToItem(uiState.log.size - 1)
                }
            }
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                items(
                    count = uiState.log.size,
                    itemContent = { index ->
                        Text(
                            text = uiState.log[index],
                            color = Color(0xFF00FF00), // Terminal green
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 12.sp
                        )
                    }
                )
            }
        }

        // Mini status and toggle button at bottom
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = uiState.status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (uiState.lastError.isNotEmpty()) Color.Red else Color.Gray
                )
                if (uiState.userText.isNotEmpty()) {
                    Text(
                        text = uiState.userText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (uiState.isConnected) {
                TextButton(onClick = onToggle) {
                    Text("STOP", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun StateIndicator(state: GeminiState, currentTool: String, onStop: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    val color = when (state) {
        GeminiState.IDLE -> Color.Gray
        GeminiState.LISTENING -> Color(0xFF4CAF50) // Green
        GeminiState.THINKING -> Color(0xFFFFC107) // Amber
        GeminiState.WORKING -> Color(0xFF2196F3)  // Blue
        GeminiState.SPEAKING -> Color.White
    }

    val icon = when (state) {
        GeminiState.IDLE -> Icons.Default.Info
        GeminiState.LISTENING -> Icons.Default.Mic
        GeminiState.THINKING -> Icons.Default.Settings // Gear-like
        GeminiState.WORKING -> Icons.Default.Settings // Gear-like
        GeminiState.SPEAKING -> Icons.AutoMirrored.Filled.VolumeUp
    }

    val label = when (state) {
        GeminiState.WORKING -> if (currentTool.isNotEmpty()) "Using $currentTool..." else "Checking Data..."
        else -> state.label
    }

    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == GeminiState.LISTENING || state == GeminiState.SPEAKING) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (state == GeminiState.WORKING || state == GeminiState.THINKING) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(220.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.2f))
                .border(8.dp, color, CircleShape)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(80.dp)
                    .rotate(rotation),
                tint = if (color == Color.White) Color.Black else color
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = if (color == Color.White) Color.DarkGray else color
        )
    }
}
