// android/app/src/main/java/com/rezvani/mesh/ui/components/SeverityPicker.kt (new file)

package com.rezvani.mesh.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SeverityPicker(
    selectedLevel: Int,
    onLevelSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val levels = listOf(
        1 to "Advisory",
        2 to "Watch",
        3 to "Warning",
        4 to "Critical",
        5 to "Emergency"
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Select Severity Level",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        levels.forEach { (level, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedLevel == level,
                    onClick = { onLevelSelected(level) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "$level - $label",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}