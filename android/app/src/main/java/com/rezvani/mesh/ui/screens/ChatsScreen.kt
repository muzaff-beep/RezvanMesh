package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.R
import com.rezvani.mesh.ui.components.EmptyState
import com.rezvani.mesh.ui.components.PowerState
import com.rezvani.mesh.ui.components.PowerStateIndicator
import com.rezvani.mesh.ui.viewmodel.ChatsViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    onConversationClick: (String, String) -> Unit,
    onNewMessageClick: () -> Unit,
    onNewChannelClick: () -> Unit,
    onEmergencyClick: () -> Unit,
    viewModel: ChatsViewModel = viewModel()
) {
    val conversations by viewModel.conversations.collectAsState(initial = emptyList())
    val powerState by viewModel.powerState.collectAsState(initial = PowerState.BALANCED)
    val batteryLevel by viewModel.batteryLevel.collectAsState(initial = 100)
    val isRefreshing by viewModel.isRefreshing.collectAsState(initial = false)
    var showMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.chats),
                            style = MaterialTheme.typography.titleLarge
                        )
                        PowerStateIndicator(powerState = powerState)
                        BatteryIndicator(level = batteryLevel)
                    }
                },
                actions = {
                    IconButton(onClick = { onEmergencyClick() }) {
                        Icon(
                            imageVector = Icons.Default.Campaign,
                            contentDescription = stringResource(R.string.emergency),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(onClick = { showMenuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.new_conversation)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenuExpanded,
                        onDismissRequest = { showMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_message)) },
                            onClick = {
                                showMenuExpanded = false
                                onNewMessageClick()
                            },
                            leadingIcon = { Icon(Icons.Default.Person, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_channel)) },
                            onClick = {
                                showMenuExpanded = false
                                onNewChannelClick()
                            },
                            leadingIcon = { Icon(Icons.Default.PersonAdd, null) }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isRefreshing && conversations.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                conversations.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.Person,
                        message = stringResource(R.string.no_conversations_yet),
                        actionText = stringResource(R.string.start_conversation),
                        onAction = onNewMessageClick
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(
                            items = conversations,
                            key = { it.conversationId }
                        ) { conversation ->
                            ConversationListItem(
                                conversation = conversation,
                                onClick = {
                                    onConversationClick(
                                        conversation.conversationId,
                                        conversation.contactName
                                    )
                                }
                            )
                            Divider(
                                modifier = Modifier.padding(start = 72.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BatteryIndicator(level: Int) {
    val batteryColor = when {
        level <= 15 -> MaterialTheme.colorScheme.error
        level <= 30 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(22.dp)
                .height(12.dp)
                .border(1.dp, batteryColor, MaterialTheme.shapes.small)
                .padding(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(level / 100f)
                    .background(batteryColor, MaterialTheme.shapes.small)
            )
        }
        Text(
            text = "$level%",
            style = MaterialTheme.typography.labelSmall,
            color = batteryColor
        )
    }
}

@Composable
fun ConversationListItem(
    conversation: ConversationItem,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateString = dateFormat.format(Date(conversation.lastMessageTime))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (conversation.unreadCount > 0)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = if (conversation.unreadCount > 0)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = conversation.contactName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (conversation.unreadCount > 0)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Content
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.contactName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (conversation.unreadCount > 0)
                            FontWeight.Bold
                        else
                            FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = dateString,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.lastMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (conversation.unreadCount > 0)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (conversation.unreadCount > 0) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = conversation.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    } else if (conversation.status == MessageStatus.SENDING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else if (conversation.status == MessageStatus.FAILED) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = stringResource(R.string.failed),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

data class ConversationItem(
    val conversationId: String,
    val contactName: String,
    val lastMessage: String,
    val lastMessageTime: Long,
    val unreadCount: Int,
    val status: MessageStatus
)

enum class MessageStatus {
    SENDING, SENT, DELIVERED, READ, FAILED
}
