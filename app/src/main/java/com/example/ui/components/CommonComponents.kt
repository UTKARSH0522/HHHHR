package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppNotification
import com.example.ui.theme.HRConnectThemeExtras

@Composable
fun HRConnectMonogramBadge(
    size: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    val ext = HRConnectThemeExtras.colors
    Surface(
        color = ext.navy,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, ext.gold.copy(alpha = 0.35f)),
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "H",
                style = MaterialTheme.typography.titleMedium,
                color = ext.gold,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
fun DarkBlueGoldChecklistCard(
    eyebrow: String,
    title: String,
    bullets: List<String>,
    modifier: Modifier = Modifier,
    footerContent: (@Composable () -> Unit)? = null
) {
    val ext = HRConnectThemeExtras.colors
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ext.navy),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, ext.gold.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = ext.gold,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            bullets.forEach { bullet ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = ext.gold,
                        shape = CircleShape,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = ext.navy,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Text(
                        text = bullet,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.94f),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (footerContent != null) {
                Spacer(modifier = Modifier.height(4.dp))
                footerContent()
            }
        }
    }
}

@Composable
fun ExpertAvatar(
    fullName: String,
    size: Dp = 52.dp,
    isDemo: Boolean = false
) {
    val initials = fullName
        .replace("[DEMO]", "")
        .trim()
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifBlank { "HR" }

    val ext = HRConnectThemeExtras.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (isDemo) ext.navyElevated else MaterialTheme.colorScheme.secondary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            color = ext.gold,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun VerifiedBadge(label: String = "Verified Expert") {
    val ext = HRConnectThemeExtras.colors
    Surface(
        color = ext.verifiedBg,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, ext.verified.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Verified,
                contentDescription = label,
                tint = ext.verified,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ext.verified,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun DemoBadge() {
    val ext = HRConnectThemeExtras.colors
    Surface(
        color = ext.goldContainer,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, ext.gold.copy(alpha = 0.5f))
    ) {
        Text(
            text = "DEMO PROFILE",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = ext.onGoldContainer,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    bgColor: Color,
    textColor: Color
) {
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MetricStatCard(
    label: String,
    value: String,
    supporting: String? = null,
    modifier: Modifier = Modifier
) {
    val ext = HRConnectThemeExtras.colors
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, ext.cardBorder),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = ext.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!supporting.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = ext.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = HRConnectThemeExtras.colors.mutedText
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier
) {
    val ext = HRConnectThemeExtras.colors
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, ext.cardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = ext.mutedText
            )
        }
    }
}

@Composable
fun NotificationsDialog(
    notifications: List<AppNotification>,
    onMarkRead: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Notifications (${notifications.count { !it.read }} unread)")
            }
        },
        text = {
            if (notifications.isEmpty()) {
                Text(
                    "No notifications yet. Booking confirmations, meeting links, reviews, and payout updates appear here.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    notifications.take(20).forEach { notif ->
                        Surface(
                            color = if (notif.read) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = notif.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (!notif.read) {
                                        TextButton(onClick = { onMarkRead(notif.id) }) {
                                            Text("Mark read")
                                        }
                                    }
                                }
                                Text(
                                    text = notif.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_notifications_button")
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
fun LegalPoliciesDialog(onDismiss: () -> Unit) {
    val ext = HRConnectThemeExtras.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Gavel, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Legal & Platform Policies")
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = ext.goldContainer,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, ext.gold)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Outlined.Shield, contentDescription = null, tint = ext.onGoldContainer, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Confidentiality Mandate: Experts must NEVER share confidential employer data, candidate records, or third-party private information during consultations.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.onGoldContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                PolicyBlock(
                    "1. Terms & Conditions (Draft Template)",
                    "HRConnect operates as an advisory marketplace connecting customers with independent verified HR professionals. Consultations do not constitute formal legal representation. Subject to professional legal review prior to commercial launch."
                )
                PolicyBlock(
                    "2. Privacy Policy",
                    "We collect only essential profile and booking data via Google Sign-In. Private PII (email, phone, city) is strictly isolated from public expert directory records."
                )
                PolicyBlock(
                    "3. Cancellation & Refund Policy",
                    "Eligible upcoming bookings may be cancelled prior to completion. Refunds are recorded only after verified payment processor settlement—never automatically assumed."
                )
                PolicyBlock(
                    "4. Expert Agreement & Commission",
                    "Standard platform commission is 15% (or 10% for verified expert referrals), snapshotted per transaction. Payouts are reviewed and settled manually by administrators with full UTR audit tracking."
                )
                PolicyBlock(
                    "5. Community Guidelines",
                    "Respectful professional conduct is mandatory. Reviews are verified against completed bookings and moderated when flagged."
                )
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = ext.mutedText)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Note: Policy text above includes template placeholders awaiting formal legal sign-off.",
                        style = MaterialTheme.typography.labelSmall,
                        color = ext.mutedText
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun PolicyBlock(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
