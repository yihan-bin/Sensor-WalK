package com.example.sensorwalk.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.sensorwalk.data.AnalysisResult
import com.example.sensorwalk.ui.Destinations
import com.example.sensorwalk.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    viewModel: MainViewModel
) {
    val historyList by viewModel.allResults.collectAsState(initial = emptyList())
    val context = LocalContext.current
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf<Long?>(null) }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("确认操作") },
            text = { Text("您确定要永久删除所有历史记录吗？此操作不可恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("全部删除")
                }
            },
            dismissButton = {
                Button(onClick = { showClearConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showDeleteConfirmDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("确认删除") },
            text = { Text("您确定要删除这条记录吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog?.let { viewModel.deleteHistoryItem(it) }
                        showDeleteConfirmDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                Button(onClick = { showDeleteConfirmDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分析历史记录") },
                actions = {
                    IconButton(onClick = {
                        if(historyList.isNotEmpty()) {
                            viewModel.exportAllHistory(context)
                        } else {
                            Toast.makeText(context, "没有记录可导出", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.IosShare, contentDescription = "导出全部")
                    }
                    IconButton(onClick = {
                        if(historyList.isNotEmpty()) {
                            showClearConfirmDialog = true
                        } else {
                            Toast.makeText(context, "没有记录可删除", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.DeleteForever, contentDescription = "清空全部")
                    }
                }
            )
        },
        bottomBar = { AppBottomNavBar(navController = navController, currentRoute = Destinations.HISTORY) }
    ) { paddingValues ->
        if (historyList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("还没有分析记录")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(historyList, key = { it.id }) { result ->
                    HistoryItem(
                        result = result,
                        onClick = {
                            navController.navigate("${Destinations.RESULT_DETAILS}/${result.id}")
                        },
                        onLongClick = {
                            showDeleteConfirmDialog = result.id
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryItem(result: AnalysisResult, onClick: () -> Unit, onLongClick: () -> Unit) {
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sdf.format(Date(result.timestamp)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                // ★★★ 修复 [需求 14]: 根据模式和腿部信息显示更清晰的标题 ★★★
                val title = when {
                    result.mode == "Paired" -> {
                        val local = if(result.localLegSide == "LEFT") "左" else "右"
                        val remote = if(result.remoteLegSide == "LEFT") "左" else "右"
                        "双腿对比 (本机:${local} / 远端:${remote})"
                    }
                    else -> "单腿分析 (${if(result.localLegSide == "LEFT") "左腿" else "右腿"})"
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if(result.mode == "Paired") "对称性" else "估算对称性", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "%.1f".format(result.overallScore),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
