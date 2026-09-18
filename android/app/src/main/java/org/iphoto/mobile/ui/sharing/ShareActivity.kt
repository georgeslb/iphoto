package org.iphoto.mobile.ui.sharing

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.iphoto.mobile.IPhotoApplication
import org.iphoto.mobile.data.repository.SpaceRepository
import org.iphoto.mobile.model.SpaceDTO
import org.iphoto.mobile.ui.theme.IPhotoTheme

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.mapNotNull { it as? Uri } ?: emptyList()
            else -> emptyList()
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "No media to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val container = (application as IPhotoApplication).container
        val spaceRepository = SpaceRepository(container.spaceApi)
        val uploadManager = container.uploadManager

        setContent {
            IPhotoTheme {
                ShareScreen(
                    spaceRepository = spaceRepository,
                    onUpload = { spaceId ->
                        uploadManager.addToQueue(uris, spaceId)
                        Toast.makeText(this, "Uploads started", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(spaceRepository: SpaceRepository, onUpload: (String) -> Unit) {
    var spaces by remember { mutableStateOf<List<SpaceDTO>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedSpaceId by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                spaces = spaceRepository.getSpaces()
            } catch (e: Exception) {
                error = "Failed to load spaces"
            } finally {
                isLoading = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            isLoading -> CircularProgressIndicator()
            error != null -> Text(error!!)
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Select a space to upload")

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded },
                        modifier = Modifier.padding(vertical = 16.dp)
                    ) {
                        TextField(
                            value = spaces.find { it.id == selectedSpaceId }?.name ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Space") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            spaces.forEach { space ->
                                DropdownMenuItem(
                                    text = { Text(space.name) },
                                    onClick = {
                                        selectedSpaceId = space.id
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    Button(onClick = { selectedSpaceId?.let { onUpload(it) } }, enabled = selectedSpaceId != null) {
                        Text("Upload")
                    }
                }
            }
        }
    }
}
