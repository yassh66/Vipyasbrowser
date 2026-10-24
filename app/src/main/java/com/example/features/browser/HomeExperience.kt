package com.example.features.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.YASLogoEmblem
import com.example.ui.theme.InstagramGradientEnd
import com.example.ui.theme.InstagramGradientStart
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.YasPurple
import com.example.ui.theme.extendedColors

data class SpeedDialItem(
    val title: String,
    val url: String,
    val iconColor: Color,
    val badge: String? = null
)

@Composable
fun HomeExperience(
    searchEngineName: String = "Google",
    isIncognito: Boolean = false,
    onNavigateToUrl: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenMediaDownload: () -> Unit,
    onOpenVipTools: () -> Unit = {},
    onToggleIncognito: () -> Unit
) {
    val accentColor = LocalAccentColor.current
    val colors = MaterialTheme.extendedColors

    val speedDials = listOf(
        SpeedDialItem("Instagram", "https://www.instagram.com", Color(0xFFE1306C), "Downloader"),
        SpeedDialItem("YouTube", "https://www.youtube.com", Color(0xFFFF0000), "Video/Audio"),
        SpeedDialItem("TikTok", "https://www.tiktok.com", Color(0xFF00F2FE)),
        SpeedDialItem("X / Twitter", "https://x.com", Color(0xFF1DA1F2)),
        SpeedDialItem("Reddit", "https://www.reddit.com", Color(0xFFFF4500)),
        SpeedDialItem("Wikipedia", "https://www.wikipedia.org", Color(0xFF94A3B8)),
        SpeedDialItem("GitHub", "https://github.com", Color(0xFF8B5CF6)),
        SpeedDialItem("Google", "https://www.google.com", Color(0xFF4285F4))
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_experience_container"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // Luxury Header with Emblem (No redundant duplicate text)
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            ) {
                // Atmospheric ambient radial glow behind emblem
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    accentColor.copy(alpha = if (colors.isDark) 0.25f else 0.18f),
                                    Color.Transparent
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    YASLogoEmblem(
                        size = 56.dp,
                        primaryColor = accentColor,
                        secondaryColor = YasPurple
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "YAS BROWSER",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp,
                    letterSpacing = 2.sp
                )

                Text(
                    text = if (isIncognito) "Incognito Private Session" else "Fast • Private • Media Downloader",
                    color = if (isIncognito) Color(0xFF10B981) else accentColor,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isIncognito) Color(0xFF10B981).copy(alpha = 0.15f) else colors.surfaceVariant)
                        .border(
                            width = 1.dp,
                            color = if (isIncognito) Color(0xFF10B981).copy(alpha = 0.5f) else colors.cardBorder,
                            shape = RoundedCornerShape(20.dp)
                        )
                        .clickable { onToggleIncognito() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("home_incognito_pill"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isIncognito) Icons.Default.Shield else Icons.Default.Security,
                            contentDescription = null,
                            tint = if (isIncognito) Color(0xFF10B981) else colors.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isIncognito) "Incognito Active" else "Private Browsing",
                            color = if (isIncognito) Color(0xFF10B981) else colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Central Home Search Input
        item {
            var homeSearchQuery by remember { mutableStateOf("") }
            val homeFocusRequester = remember { FocusRequester() }

            OutlinedTextField(
                value = homeSearchQuery,
                onValueChange = { homeSearchQuery = it },
                placeholder = {
                    Text(
                        text = "Search with $searchEngineName or enter URL...",
                        color = colors.textMuted,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .focusRequester(homeFocusRequester)
                    .testTag("home_search_input"),
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    color = colors.textPrimary,
                    textDirection = TextDirection.ContentOrLtr
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        val query = homeSearchQuery.trim()
                        if (query.isNotBlank()) {
                            onNavigateToUrl(query)
                            homeSearchQuery = ""
                        }
                    }
                ),
                shape = RoundedCornerShape(27.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = colors.cardBorder,
                    focusedContainerColor = colors.cardBackground,
                    unfocusedContainerColor = colors.cardBackground
                ),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(54.dp)
                            .padding(end = 6.dp)
                    ) {
                        if (homeSearchQuery.isNotBlank()) {
                            IconButton(
                                onClick = { homeSearchQuery = "" },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("home_search_clear_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    val query = homeSearchQuery.trim()
                                    if (query.isNotBlank()) {
                                        onNavigateToUrl(query)
                                        homeSearchQuery = ""
                                    }
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("home_search_go_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Go",
                                    tint = accentColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(colors.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = searchEngineName.take(1).uppercase(),
                                    color = colors.textSecondary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            )
        }

        // Feature Highlight Cards (Media Downloader & Privacy)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Media Downloader Hero Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenMediaDownload() }
                        .testTag("home_media_downloader_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(
                            listOf(
                                InstagramGradientStart.copy(alpha = 0.6f),
                                InstagramGradientEnd.copy(alpha = 0.4f)
                            )
                        )
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (colors.isDark) 0.dp else 2.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(InstagramGradientStart, InstagramGradientEnd)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Media Grabber",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Reels, Posts & MP3",
                            color = colors.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                // VIP Tools Hero Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenVipTools() }
                        .testTag("home_vip_tools_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(
                            listOf(
                                accentColor.copy(alpha = 0.6f),
                                YasPurple.copy(alpha = 0.4f)
                            )
                        )
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (colors.isDark) 0.dp else 2.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(accentColor, YasPurple)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "VIP Tools",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Media Analyzer & Streams",
                            color = colors.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // Section Title: Speed Dials
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quick Access",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = "Speed Dials",
                    color = colors.textMuted,
                    fontSize = 12.sp
                )
            }
        }

        // Speed Dials Grid (8 items in 4 columns)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    speedDials.take(4).forEach { dial ->
                        SpeedDialCell(dial = dial, onSelect = { onNavigateToUrl(dial.url) })
                    }
                }
                // Row 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    speedDials.drop(4).take(4).forEach { dial ->
                        SpeedDialCell(dial = dial, onSelect = { onNavigateToUrl(dial.url) })
                    }
                }
            }
        }
    }
}

@Composable
fun SpeedDialCell(
    dial: SpeedDialItem,
    onSelect: () -> Unit
) {
    val colors = MaterialTheme.extendedColors

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable { onSelect() }
            .testTag("speed_dial_${dial.title}")
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(colors.cardBackground)
                .border(
                    width = 1.2.dp,
                    color = dial.iconColor.copy(alpha = if (colors.isDark) 0.45f else 0.6f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = dial.title.take(2).uppercase(),
                color = dial.iconColor,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = dial.title,
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
