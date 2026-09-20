package org.iphoto.mobile.upload

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun UploadQueueMenu(uploadManager: UploadManager) {
    val uploadQueue by uploadManager.uploadQueue.collectAsState()
    val isDismissed by uploadManager.isDismissed.collectAsState()

    if (uploadQueue.isNotEmpty() && !isDismissed) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Uploads", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { uploadManager.dismiss() }) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss")
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(uploadQueue) { item ->
                        UploadItemRow(item = item)
                    }
                }
            }
        }
    }
}

@Composable
fun UploadItemRow(item: UploadItem) {
    val statusLabel = when (item.status) {
        "UPLOADING" -> "Uploading..."
        "PROCESSING" -> "Processing..."
        "COMPLETED" -> "Completed"
        "FAILED" -> "Failed"
        "IGNORED" -> "Already imported"
        "TRASHED" -> "Already imported and trashed"
        "PENDING" -> "Pending..."
        else -> item.status
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.fileName, maxLines = 1)
            Text(statusLabel, style = MaterialTheme.typography.bodySmall)
            if (item.error != null) {
                Text(
                    item.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (item.status == "UPLOADING" || item.status == "PROCESSING") {
            LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.width(100.dp))
        }
    }
}
