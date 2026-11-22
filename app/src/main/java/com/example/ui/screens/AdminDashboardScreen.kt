package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.CurrencyUtils
import com.example.core.FinancialLedgerEngine
import com.example.core.TimeSlotUtils
import com.example.data.model.AccountStatus
import com.example.data.model.AttendanceRecord
import com.example.data.model.Booking
import com.example.data.model.BookingStatus
import com.example.data.model.DisputeRecord
import com.example.data.model.DisputeStatus
import com.example.data.model.ExpertProfile
import com.example.data.model.LedgerEntry
import com.example.data.model.PaymentRecord
import com.example.data.model.PaymentStatus
import com.example.data.model.PayoutRequest
import com.example.data.model.PayoutStatus
import com.example.data.model.VerificationStatus
import com.example.ui.HRConnectViewModel
import com.example.ui.components.DemoBadge
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.MetricStatCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.theme.HRConnectThemeExtras

private enum class AdminTab(val label: String) {
    OVERVIEW("Analytics"),
    EXPERTS("Experts"),
    PAYOUTS("Payouts"),
    BOOKINGS("Bookings & Payments"),
    SETTINGS("Commission & Categories"),
    MODERATION("Reviews & Audit")
}

@Composable
fun AdminDashboardScreen(
    viewModel: HRConnectViewModel
) {
    if (!viewModel.isAdminUser) {
        EmptyStateCard(
            icon = Icons.Filled.AdminPanelSettings,
            title = "Administrator Access Required",
            message = "Admin permissions are strictly granted to verified platform administrators.",
            modifier = Modifier.padding(16.dp)
        )
        return
    }

    var selectedTab by remember { mutableStateOf(AdminTab.OVERVIEW) }
    val users by viewModel.adminUsers.collectAsStateWithLifecycle()
    val experts by viewModel.adminExperts.collectAsStateWithLifecycle()
    val bookings by viewModel.adminBookings.collectAsStateWithLifecycle()
    val payments by viewModel.adminPayments.collectAsStateWithLifecycle()
    val payouts by viewModel.adminPayouts.collectAsStateWithLifecycle()
    val reviews by viewModel.adminReviews.collectAsStateWithLifecycle()
    val specs by viewModel.adminSpecialisations.collectAsStateWithLifecycle()
    val auditLogs by viewModel.adminAuditLogs.collectAsStateWithLifecycle()
    val ledgerEntries by viewModel.adminLedgerEntries.collectAsStateWithLifecycle()
    val attendanceRecords by viewModel.adminAttendanceRecords.collectAsStateWithLifecycle()
    val disputes by viewModel.adminDisputes.collectAsStateWithLifecycle()
    val settings by viewModel.platformSettings.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var rejectTarget by remember { mutableStateOf<ExpertProfile?>(null) }
    var rejectReason by remember { mutableStateOf("") }
    var payoutProcessTarget by remember { mutableStateOf<PayoutRequest?>(null) }
    var payoutUtr by remember { mutableStateOf("") }
    var payoutNote by remember { mutableStateOf("") }
    var inspectBookingTarget by remember { mutableStateOf<Booking?>(null) }

    var newSpecName by remember { mutableStateOf("") }
    var newSpecDesc by remember { mutableStateOf("") }

    val ext = HRConnectThemeExtras.colors

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AdminTab.entries.forEach { tab ->
                FilterChip(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    label = { Text(tab.label) },
                    modifier = Modifier.testTag("admin_tab_${tab.name.lowercase()}")
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (selectedTab) {
                AdminTab.OVERVIEW -> {
                    val approvedExperts = experts.count { it.verificationEnum() == VerificationStatus.APPROVED }
                    val pendingExperts = experts.count { it.verificationEnum() == VerificationStatus.PENDING }
                    val successfulPayments = payments.filter { it.statusEnum() == PaymentStatus.SUCCESS }
                    val successVolumePaise = successfulPayments.sumOf { it.amountPaise }
                    val commissionEarnedPaise = successfulPayments.sumOf { it.commissionPaise }
                    val pendingPayouts = payouts.count { it.statusEnum() == PayoutStatus.PENDING }

                    item {
                        SectionHeader("Platform Operations & Analytics", "Live Firestore metrics (failed/pending excluded from revenue)")
                    }

                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = ext.goldContainer),
                            border = BorderStroke(1.dp, ext.gold)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Marketplace Bootstrap & Demo Data",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = ext.onGoldContainer,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Seed the 15 HR specialisations, platform commission defaults (15%/10%), and 4 clearly-badged [DEMO] HR experts with services & IST schedules.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ext.onGoldContainer
                                )
                                Button(
                                    onClick = { viewModel.adminSeedDemoMarketplace() },
                                    enabled = !isBusy,
                                    modifier = Modifier.testTag("seed_demo_marketplace_button")
                                ) {
                                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Seed Demo Marketplace Data")
                                }
                            }
                        }
                    }

                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricStatCard("Registered Users", users.size.toString(), modifier = Modifier.weight(1f))
                            MetricStatCard("Approved Experts", approvedExperts.toString(), supporting = "$pendingExperts pending review", modifier = Modifier.weight(1f))
                        }
                    }
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricStatCard("Total Bookings", bookings.size.toString(), supporting = "${bookings.count { it.bookingStatusEnum() == BookingStatus.COMPLETED }} completed", modifier = Modifier.weight(1f))
                            MetricStatCard("Pending Payouts", pendingPayouts.toString(), modifier = Modifier.weight(1f))
                        }
                    }
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricStatCard("Settled Volume", CurrencyUtils.formatInrFromPaise(successVolumePaise), modifier = Modifier.weight(1f))
                            MetricStatCard("Commission Earned", CurrencyUtils.formatInrFromPaise(commissionEarnedPaise), modifier = Modifier.weight(1f))
                        }
                    }
                }

                AdminTab.EXPERTS -> {
                    item { SectionHeader("Expert Applications & Accounts", "${experts.size} total expert profiles") }
                    items(experts, key = { it.id }) { exp ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, ext.cardBorder)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(exp.fullName, style = MaterialTheme.typography.titleMedium)
                                        Text("${exp.designation} • ${exp.yearsExperience} yrs", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (exp.demoProfile) DemoBadge()
                                        StatusPill(
                                            exp.verificationStatus,
                                            if (exp.verificationEnum() == VerificationStatus.APPROVED) ext.verifiedBg else ext.pendingBg,
                                            if (exp.verificationEnum() == VerificationStatus.APPROVED) ext.verified else ext.pending
                                        )
                                    }
                                }
                                Text(exp.headline, style = MaterialTheme.typography.bodySmall)
                                if (!exp.verificationNotes.isNullOrBlank()) {
                                    Text("Credentials: ${exp.verificationNotes}", style = MaterialTheme.typography.labelSmall, color = ext.mutedText)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (exp.verificationEnum() != VerificationStatus.APPROVED) {
                                        Button(
                                            onClick = { viewModel.adminReviewExpert(exp, true) },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Approve")
                                        }
                                    }
                                    if (exp.verificationEnum() == VerificationStatus.PENDING) {
                                        OutlinedButton(
                                            onClick = { rejectTarget = exp },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Reject")
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.adminToggleExpertStatus(exp) },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = if (exp.accountStatusEnum() == AccountStatus.ACTIVE) MaterialTheme.colorScheme.error else ext.verified
                                        )
                                    ) {
                                        Text(if (exp.accountStatusEnum() == AccountStatus.ACTIVE) "Suspend" else "Reactivate")
                                    }
                                }
                            }
                        }
                    }

                    item { SectionHeader("Registered Customers (${users.size})") }
                    items(users, key = { "u_${it.userId}" }) { u ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, ext.cardBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(u.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text("${u.role} • ${u.accountStatus}", style = MaterialTheme.typography.labelSmall, color = ext.mutedText)
                                }
                                TextButton(onClick = { viewModel.adminToggleCustomerStatus(u) }) {
                                    Text(if (u.statusEnum() == AccountStatus.ACTIVE) "Suspend" else "Reactivate")
                                }
                            }
                        }
                    }
                }

                AdminTab.PAYOUTS -> {
                    item { SectionHeader("Expert Payout Requests", "${payouts.size} request(s)") }
                    if (payouts.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Filled.AdminPanelSettings,
                                title = "No Payout Requests",
                                message = "Expert withdrawal requests appear here for eligibility validation, transfer initiation, and UTR entry."
                            )
                        }
                    } else {
                        items(payouts, key = { it.id }) { req ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, ext.cardBorder)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${req.expertName} • ${CurrencyUtils.formatInrFromPaise(req.amountPaise)}", style = MaterialTheme.typography.titleMedium)
                                        StatusPill(req.status, ext.goldContainer, ext.onGoldContainer)
                                    }
                                    Text("Destination: ${req.payoutDestination}", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                                    Text(
                                        "Eligible Bookings: ${req.eligibleBookingIds.size} • Retries: ${req.retryCount}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ext.mutedText
                                    )
                                    if (!req.payoutReference.isNullOrBlank()) {
                                        Text("UTR/Ref: ${req.payoutReference}", style = MaterialTheme.typography.labelSmall, color = ext.verified)
                                    }
                                    if (!req.failureReason.isNullOrBlank()) {
                                        Text("Failure: ${req.failureReason}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    if (req.statusEnum() in setOf(PayoutStatus.PENDING, PayoutStatus.APPROVED, PayoutStatus.PROCESSING, PayoutStatus.FAILED)) {
                                        Button(onClick = {
                                            payoutUtr = ""
                                            payoutNote = ""
                                            payoutProcessTarget = req
                                        }) {
                                            Text(
                                                if (req.statusEnum() == PayoutStatus.FAILED) "Retry Failed Payout" else "Review & Process Settlement"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                AdminTab.BOOKINGS -> {
                    item { SectionHeader("Booking Lifecycle Inspector (${bookings.size})", "Tap any booking to inspect Google emails, payment, Calendar/Meet sync, attendance, disputes & ledger") }
                    items(bookings.take(40), key = { it.id }) { bk ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, ext.cardBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${bk.serviceTitle} (${bk.bookingStatus})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    Text(CurrencyUtils.formatInrFromPaise(bk.amountPaise), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                }
                                Text("${bk.customerName} -> ${bk.expertName} • ${TimeSlotUtils.formatDateDisplay(bk.dateIso)}", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                                Text(
                                    "Attend: ${bk.attendanceStatus} • Payout: ${bk.payoutEligibilityStatus} • Dispute: ${bk.disputeStatus}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ext.mutedText
                                )
                                Text(
                                    "Commission: ${CurrencyUtils.formatInrFromPaise(bk.commissionPaise)} (${CurrencyUtils.formatCommissionPercent(bk.commissionRateBps)}) • ${bk.referralType}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ext.mutedText
                                )
                                OutlinedButton(
                                    onClick = { inspectBookingTarget = bk },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Inspect Full Lifecycle & Actions") }
                            }
                        }
                    }
                    item { SectionHeader("Payment Records (${payments.size})") }
                    items(payments.take(30), key = { "ap_${it.id}" }) { pay ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, ext.cardBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("${CurrencyUtils.formatInrFromPaise(pay.amountPaise)} • ${pay.paymentStatus} • ${pay.paymentMethod}", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Order: ${pay.razorpayOrderId} | Pay: ${pay.razorpayPaymentId ?: "—"} | ServerVerified: ${pay.serverVerified}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ext.mutedText
                                )
                            }
                        }
                    }
                }

                AdminTab.SETTINGS -> {
                    item {
                        CommissionConfigCard(
                            currentPlatformPct = (settings.platformCommissionBps / 100L).toInt(),
                            currentReferralPct = (settings.referralCommissionBps / 100L).toInt(),
                            currentHoldMins = settings.paymentHoldMinutes.toInt(),
                            currentCancelHours = settings.cancellationWindowHours.toInt(),
                            isBusy = isBusy,
                            onSave = { pPct, rPct, hold, cancelH ->
                                viewModel.adminUpdateCommission(pPct, rPct, hold, cancelH)
                            }
                        )
                    }

                    item {
                        SectionHeader("Manage HR Specialisations (${specs.size})")
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, ext.cardBorder)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = newSpecName, onValueChange = { newSpecName = it }, label = { Text("New Category Name") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = newSpecDesc, onValueChange = { newSpecDesc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                                Button(
                                    onClick = {
                                        viewModel.adminSaveSpecialisation(null, newSpecName, newSpecDesc, (specs.size + 1).toLong(), true)
                                        newSpecName = ""
                                        newSpecDesc = ""
                                    },
                                    enabled = !isBusy && newSpecName.isNotBlank()
                                ) { Text("Add Specialisation") }
                            }
                        }
                    }

                    items(specs, key = { it.id }) { spec ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, ext.cardBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${spec.sortOrder + 1}. ${spec.name}", style = MaterialTheme.typography.titleSmall)
                                    Text(spec.description, style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                                }
                                TextButton(onClick = {
                                    viewModel.adminSaveSpecialisation(spec.id, spec.name, spec.description, spec.sortOrder, !spec.active)
                                }) {
                                    Text(if (spec.active) "Deactivate" else "Activate")
                                }
                            }
                        }
                    }
                }

                AdminTab.MODERATION -> {
                    item { SectionHeader("Review Moderation (${reviews.size})") }
                    items(reviews, key = { it.id }) { rev ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, ext.cardBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${rev.customerName} • ${rev.rating}/5 ★", style = MaterialTheme.typography.titleSmall)
                                    StatusPill(
                                        if (rev.reported) "REPORTED" else rev.moderationStatus,
                                        if (rev.reported) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                                        if (rev.reported) MaterialTheme.colorScheme.error else ext.mutedText
                                    )
                                }
                                if (rev.comment.isNotBlank()) Text(rev.comment, style = MaterialTheme.typography.bodySmall)
                                if (!rev.reportReason.isNullOrBlank()) {
                                    Text("Report reason: ${rev.reportReason}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { viewModel.adminModerateReview(rev.id, rev.moderationStatus == "VISIBLE") }) {
                                        Text(if (rev.moderationStatus == "VISIBLE") "Hide Review" else "Restore Review")
                                    }
                                }
                            }
                        }
                    }

                    item { SectionHeader("Admin Audit Trail (${auditLogs.size})") }
                    items(auditLogs.take(40), key = { it.id }) { log ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, ext.cardBorder)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("${log.action} • ${log.targetType}:${log.targetId}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text("${log.details} (${log.actorEmail})", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                            }
                        }
                    }
                }
            }
        }
    }

    rejectTarget?.let { exp ->
        AlertDialog(
            onDismissRequest = { rejectTarget = null },
            title = { Text("Reject ${exp.fullName}") },
            text = {
                OutlinedTextField(
                    value = rejectReason,
                    onValueChange = { rejectReason = it },
                    label = { Text("Clear reason for applicant") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.adminReviewExpert(exp, false, rejectReason)
                        rejectReason = ""
                        rejectTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Reject Application") }
            },
            dismissButton = {
                TextButton(onClick = { rejectTarget = null }) { Text("Cancel") }
            }
        )
    }

    payoutProcessTarget?.let { req ->
        AlertDialog(
            onDismissRequest = { payoutProcessTarget = null },
            title = { Text("Settle Payout (${CurrencyUtils.formatInrFromPaise(req.amountPaise)})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Expert: ${req.expertName}\nDestination: ${req.payoutDestination}\nStatus: ${req.status} • Eligible Bookings: ${req.eligibleBookingIds.size} • Retries: ${req.retryCount}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(value = payoutUtr, onValueChange = { payoutUtr = it }, label = { Text("Bank UTR / Route Transfer ID (Required for Paid)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = payoutNote, onValueChange = { payoutNote = it }, label = { Text("Admin Note / Failure Reason") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        viewModel.adminProcessPayout(req, PayoutStatus.PROCESSING, payoutUtr, payoutNote)
                        payoutProcessTarget = null
                    }) { Text("Mark Processing") }
                    Button(onClick = {
                        viewModel.adminProcessPayout(req, PayoutStatus.PAID, payoutUtr, payoutNote)
                        payoutProcessTarget = null
                    }) { Text("Mark Paid") }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        viewModel.adminProcessPayout(req, PayoutStatus.FAILED, "", payoutNote)
                        payoutProcessTarget = null
                    }) { Text("Mark Failed") }
                    TextButton(
                        onClick = {
                            viewModel.adminProcessPayout(req, PayoutStatus.REJECTED, "", payoutNote)
                            payoutProcessTarget = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Reject") }
                }
            }
        )
    }

    inspectBookingTarget?.let { selected ->
        val latest = bookings.firstOrNull { it.id == selected.id } ?: selected
        val payment = payments.firstOrNull { it.bookingId == latest.id }
        val attendance = attendanceRecords.firstOrNull { it.bookingId == latest.id }
        val dispute = disputes.firstOrNull { it.bookingId == latest.id }
        val bookingLedger = ledgerEntries.filter { it.bookingId == latest.id }
        val eligibility = FinancialLedgerEngine.evaluateBookingPayoutEligibility(latest, settings)

        AdminBookingInspectorDialog(
            booking = latest,
            payment = payment,
            attendance = attendance,
            dispute = dispute,
            ledger = bookingLedger,
            eligibilityReasons = eligibility.reasons,
            eligibilityStatus = eligibility.status.name,
            isBusy = isBusy,
            onSyncCalendar = { viewModel.syncCalendarAndMeet(latest) },
            onSyncAttendance = { viewModel.syncMeetAttendance(latest) },
            onManualAttendance = { approve, cMin, eMin, oMin, note ->
                viewModel.adminManualAttendanceReview(latest, approve, cMin, eMin, oMin, note)
            },
            onResolveDispute = { resolution, refundPaise, note ->
                viewModel.adminResolveDisputeAndRefund(latest, resolution, refundPaise, note)
            },
            onDismiss = { inspectBookingTarget = null }
        )
    }
}

@Composable
private fun AdminBookingInspectorDialog(
    booking: Booking,
    payment: PaymentRecord?,
    attendance: AttendanceRecord?,
    dispute: DisputeRecord?,
    ledger: List<LedgerEntry>,
    eligibilityReasons: List<String>,
    eligibilityStatus: String,
    isBusy: Boolean,
    onSyncCalendar: () -> Unit,
    onSyncAttendance: () -> Unit,
    onManualAttendance: (Boolean, Long, Long, Long, String) -> Unit,
    onResolveDispute: (DisputeStatus, Long, String) -> Unit,
    onDismiss: () -> Unit
) {
    var cMinutesInput by remember(booking.id) { mutableStateOf(booking.customerJoinedMinutes.coerceAtLeast(booking.durationMinutes).toString()) }
    var eMinutesInput by remember(booking.id) { mutableStateOf(booking.expertJoinedMinutes.coerceAtLeast(booking.durationMinutes).toString()) }
    var oMinutesInput by remember(booking.id) { mutableStateOf(booking.overlapMinutes.coerceAtLeast(booking.durationMinutes).toString()) }
    var adminNote by remember(booking.id) { mutableStateOf("") }
    var partialRefundInr by remember(booking.id) { mutableStateOf(((booking.amountPaise / 100L) / 2L).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Booking Lifecycle Inspector") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("${booking.serviceTitle} (${booking.bookingStatus})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("ID: ${booking.id}\nDate: ${booking.dateIso} • ${TimeSlotUtils.formatMinuteRange(booking.startMinute.toInt(), booking.durationMinutes.toInt())} IST", style = MaterialTheme.typography.labelSmall)
                Text(
                    "Customer: ${booking.customerName} [${booking.verifiedCustomerGoogleEmail.ifBlank { "No Google email" }}]\n" +
                        "Expert: ${booking.expertName} [${booking.verifiedExpertGoogleEmail.ifBlank { "No Google email" }}]",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Payment: ${CurrencyUtils.formatInrFromPaise(booking.amountPaise)} • Status=${booking.paymentStatus} • Settlement=${booking.providerSettlementStatus}\n" +
                        "Commission=${CurrencyUtils.formatInrFromPaise(booking.commissionPaise)} (${CurrencyUtils.formatCommissionPercent(booking.commissionRateBps)}) • GatewayFee=${CurrencyUtils.formatInrFromPaise(booking.gatewayFeePaise)} • Net=${CurrencyUtils.formatInrFromPaise(booking.expertEarningPaise)} • Refunded=${CurrencyUtils.formatInrFromPaise(booking.refundedAmountPaise)}\n" +
                        "Order=${payment?.razorpayOrderId ?: "—"} | Txn=${payment?.razorpayPaymentId ?: "—"} | ServerVerified=${payment?.serverVerified == true}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    "Calendar/Meet Sync: ${booking.calendarSyncStatus} | Event=${booking.googleCalendarEventId ?: "—"} | Space=${booking.googleMeetSpaceId ?: "—"} | Access=${booking.meetingAccessMode}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    "Attendance: ${booking.attendanceStatus} (${booking.attendanceVerificationSource}) • Cust=${booking.customerJoinedMinutes}m, Exp=${booking.expertJoinedMinutes}m, Overlap=${booking.overlapMinutes}m",
                    style = MaterialTheme.typography.labelSmall
                )
                val unmatched = attendance?.unmatchedParticipantDetails
                if (!unmatched.isNullOrBlank()) {
                    Text("Unmatched Meet Participants: $unmatched", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "Completion: Cust=${booking.customerCompletedConfirmed}, Exp=${booking.expertCompletedConfirmed} | Payout=$eligibilityStatus" +
                        if (eligibilityReasons.isNotEmpty()) "\nBlockers: ${eligibilityReasons.joinToString("; ")}" else "",
                    style = MaterialTheme.typography.labelSmall
                )
                if (dispute != null || booking.disputeStatusEnum() != DisputeStatus.NONE) {
                    Text(
                        "Dispute: ${dispute?.status ?: booking.disputeStatus} • Reason: ${dispute?.reason ?: booking.disputeReason ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onSyncCalendar, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                        Text("Sync Meet")
                    }
                    OutlinedButton(onClick = onSyncAttendance, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                        Text("Sync Logs")
                    }
                }

                Text("Manual Attendance Override", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = cMinutesInput, onValueChange = { cMinutesInput = it.filter(Char::isDigit) }, label = { Text("Cust m") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = eMinutesInput, onValueChange = { eMinutesInput = it.filter(Char::isDigit) }, label = { Text("Exp m") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = oMinutesInput, onValueChange = { oMinutesInput = it.filter(Char::isDigit) }, label = { Text("Ovrlp m") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = adminNote, onValueChange = { adminNote = it }, label = { Text("Admin Audit Note") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            onManualAttendance(
                                true,
                                cMinutesInput.toLongOrNull() ?: 0L,
                                eMinutesInput.toLongOrNull() ?: 0L,
                                oMinutesInput.toLongOrNull() ?: 0L,
                                adminNote.ifBlank { "Verified by admin" }
                            )
                        },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Approve Attend") }
                    OutlinedButton(
                        onClick = {
                            onManualAttendance(false, 0L, 0L, 0L, adminNote.ifBlank { "No-show verified" })
                        },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Fail Attend") }
                }

                Text("Dispute & Refund Resolution", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = partialRefundInr,
                    onValueChange = { partialRefundInr = it.filter(Char::isDigit) },
                    label = { Text("Partial Refund (INR)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            onResolveDispute(DisputeStatus.RESOLVED_REFUND_FULL, booking.amountPaise, adminNote.ifBlank { "Full refund approved" })
                        },
                        enabled = !isBusy && booking.paymentStatusEnum() == PaymentStatus.SUCCESS,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) { Text("Full Refund") }
                    OutlinedButton(
                        onClick = {
                            val paise = (partialRefundInr.toLongOrNull() ?: 0L) * 100L
                            onResolveDispute(DisputeStatus.RESOLVED_REFUND_PARTIAL, paise, adminNote.ifBlank { "Partial refund approved" })
                        },
                        enabled = !isBusy && booking.paymentStatusEnum() == PaymentStatus.SUCCESS,
                        modifier = Modifier.weight(1f)
                    ) { Text("Partial") }
                    OutlinedButton(
                        onClick = {
                            onResolveDispute(DisputeStatus.RESOLVED_RELEASE_EXPERT, 0L, adminNote.ifBlank { "Resolved in favor of expert" })
                        },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Release") }
                }

                if (ledger.isNotEmpty()) {
                    Text("Immutable Ledger Rows (${ledger.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    ledger.forEach { row ->
                        Text(
                            "• ${row.entryType} [${row.direction}/${row.entryStatus}] ${CurrencyUtils.formatInrFromPaise(row.amountPaise)}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun CommissionConfigCard(
    currentPlatformPct: Int,
    currentReferralPct: Int,
    currentHoldMins: Int,
    currentCancelHours: Int,
    isBusy: Boolean,
    onSave: (Int, Int, Int, Int) -> Unit
) {
    var platformPct by remember { mutableStateOf(currentPlatformPct.toString()) }
    var referralPct by remember { mutableStateOf(currentReferralPct.toString()) }
    var holdMins by remember { mutableStateOf(currentHoldMins.toString()) }
    var cancelHours by remember { mutableStateOf(currentCancelHours.toString()) }

    LaunchedEffect(currentPlatformPct, currentReferralPct, currentHoldMins, currentCancelHours) {
        platformPct = currentPlatformPct.toString()
        referralPct = currentReferralPct.toString()
        holdMins = currentHoldMins.toString()
        cancelHours = currentCancelHours.toString()
    }

    val ext = HRConnectThemeExtras.colors
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, ext.cardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Commission & Booking Rules", style = MaterialTheme.typography.titleMedium)
            Text(
                "Updating commission percentages only applies to future bookings. Historical booking snapshots remain unchanged.",
                style = MaterialTheme.typography.bodySmall,
                color = ext.mutedText
            )
            OutlinedTextField(value = platformPct, onValueChange = { platformPct = it.filter { c -> c.isDigit() } }, label = { Text("Platform-Generated Commission (%)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = referralPct, onValueChange = { referralPct = it.filter { c -> c.isDigit() } }, label = { Text("Expert-Referred Commission (%)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = holdMins, onValueChange = { holdMins = it.filter { c -> c.isDigit() } }, label = { Text("Payment Hold Window (minutes)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = cancelHours, onValueChange = { cancelHours = it.filter { c -> c.isDigit() } }, label = { Text("Cancellation Notice Window (hours)") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    onSave(
                        platformPct.toIntOrNull() ?: 15,
                        referralPct.toIntOrNull() ?: 10,
                        holdMins.toIntOrNull() ?: 10,
                        cancelHours.toIntOrNull() ?: 24
                    )
                },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Commission Rules") }
        }
    }
}
