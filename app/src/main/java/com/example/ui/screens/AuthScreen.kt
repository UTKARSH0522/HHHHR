package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import com.example.core.BrandConfig
import com.example.data.auth.attemptAutoSignIn
import com.example.data.auth.onGoogleSignInClicked
import com.example.ui.components.DarkBlueGoldChecklistCard
import com.example.ui.components.HRConnectMonogramBadge
import com.example.ui.components.LegalPoliciesDialog
import com.example.ui.theme.HRConnectThemeExtras

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showLegalDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = onAuthSuccess,
            onUnauthenticated = {},
            scope = scope
        )
    }

    fun triggerGoogleSignIn() {
        isLoading = true
        errorMessage = null
        onGoogleSignInClicked(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = {
                isLoading = false
                onAuthSuccess()
            },
            onAuthError = { msg ->
                isLoading = false
                errorMessage = msg
            },
            scope = scope,
            onAuthCancelled = { isLoading = false }
        )
    }

    val ext = HRConnectThemeExtras.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Clean Editorial Top Header: Dark Blue [H] Gold Monogram + HRConnect + White Pill Sign In
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HRConnectMonogramBadge(size = 40.dp)
                        Text(
                            text = BrandConfig.APP_NAME,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = { if (!isLoading) triggerGoogleSignIn() },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, ext.cardBorder),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                        modifier = Modifier.testTag("header_sign_in_button")
                    ) {
                        Text(
                            text = if (isLoading) "..." else "Sign In",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Editorial Headline & Primary Sign-In CTA on Warm White Canvas
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        color = ext.goldContainer,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, ext.gold.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = "INDIA-FIRST HR ADVISORY MARKETPLACE",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = ext.onGoldContainer,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    Text(
                        text = "Your HR Expertise Deserves to Earn More.",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Book verified HR professionals for 1:1 consultations in INR, receive a meeting link on your booking, then leave a verified review — or monetise your own HR knowledge.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = ext.mutedText
                    )

                    if (!errorMessage.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Button(
                        onClick = { triggerGoogleSignIn() },
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("google_sign_in_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ext.navy,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(50)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = ext.gold,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Signing in with Google...")
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Login,
                                contentDescription = null,
                                tint = ext.gold
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Sign in with Google to Explore & Earn",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Signature Dark Blue, Golden & White Card matching reference UI
                DarkBlueGoldChecklistCard(
                    eyebrow = "FOR HR PROFESSIONALS",
                    title = "Monetise what you already know",
                    bullets = listOf(
                        "Set your own prices and schedule",
                        "No audience-building needed — customers discover you here",
                        "Transparent commission: 15% platform-sourced, 10% for customers you refer",
                        "Track earnings and request payouts in INR"
                    )
                )

                // How HRConnect Works for Customers (Clean White Cards)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "How HRConnect Works",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold
                    )
                    FeatureRowCard(
                        icon = Icons.Filled.VerifiedUser,
                        title = "1. Admin-Verified HR Specialists",
                        body = "Every HR professional undergoes profile verification before publishing services across 15 HR domains."
                    )
                    FeatureRowCard(
                        icon = Icons.Filled.CalendarMonth,
                        title = "2. Live IST Slot Reservation & Razorpay Checkout",
                        body = "10-minute concurrency-safe slot holds, instant INR checkout in Test Mode, and verified session links."
                    )
                    FeatureRowCard(
                        icon = Icons.Filled.CurrencyRupee,
                        title = "3. Transparent Income & Commission Ledger",
                        body = "15% standard platform commission (10% on expert referrals), snapshotted per booking with audit-tracked payouts."
                    )
                }

                // Specialisations preview
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, ext.cardBorder),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "15 HR Expertise Categories",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BrandConfig.defaultSpecialisations.take(9).forEach { (name, _) ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Trust & Confidentiality Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = ext.goldContainer),
                    border = BorderStroke(1.dp, ext.gold.copy(alpha = 0.5f)),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = ext.onGoldContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Trust, Verification & Confidentiality",
                                style = MaterialTheme.typography.titleSmall,
                                color = ext.onGoldContainer,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Experts are prohibited from disclosing confidential employer records or candidate PII. Reviews are tied 1:1 to completed consultations.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ext.onGoldContainer
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showLegalDialog = true },
                        border = BorderStroke(1.dp, ext.cardBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Terms, Privacy & Expert Agreement")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { showLegalDialog = true }) {
                        Icon(
                            Icons.Outlined.Gavel,
                            contentDescription = "Legal Policies",
                            tint = ext.navy,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Policies", color = ext.navy)
                    }
                }
            }
        }
    }

    if (showLegalDialog) {
        LegalPoliciesDialog(onDismiss = { showLegalDialog = false })
    }
}

@Composable
private fun FeatureRowCard(
    icon: ImageVector,
    title: String,
    body: String
) {
    val ext = HRConnectThemeExtras.colors
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, ext.cardBorder),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                color = ext.navy,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = ext.gold, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
            }
        }
    }
}
