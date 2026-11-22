package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.BrandConfig
import com.example.core.CurrencyUtils
import com.example.core.FinancialLedgerEngine
import com.example.core.TimeSlotUtils
import com.example.core.UiState
import com.example.data.model.AccountStatus
import com.example.data.model.Booking
import com.example.data.model.BookingStatus
import com.example.data.model.ConsultationService
import com.example.data.model.ExpertProfile
import com.example.data.model.PayoutEligibilityStatus
import com.example.data.model.VerificationStatus
import com.example.ui.HRConnectViewModel
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.MetricStatCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.theme.HRConnectThemeExtras
import java.util.Locale

private enum class ExpertTab(val label: String) {
    OVERVIEW("Overview"),
    SERVICES("My Services"),
    AVAILABILITY("Availability"),
    BOOKINGS("Bookings"),
    EARNINGS("Earnings & Payouts"),
    PROFILE("Application / Profile")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpertPortalScreen(
    viewModel: HRConnectViewModel
) {
    val expert by viewModel.myExpertProfile.collectAsStateWithLifecycle()
    val services by viewModel.myExpertServices.collectAsStateWithLifecycle()
    val bookings by viewModel.myExpertBookings.collectAsStateWithLifecycle()
    val ledger by viewModel.myExpertLedger.collectAsStateWithLifecycle()
    val payouts by viewModel.myExpertPayouts.collectAsStateWithLifecycle()
    val wallet by viewModel.expertWalletBreakdown.collectAsStateWithLifecycle()
    val settings by viewModel.platformSettings.collectAsStateWithLifecycle()
    val rules by viewModel.myExpertRules.collectAsStateWithLifecycle()
    val exceptions by viewModel.myExpertExceptions.collectAsStateWithLifecycle()
    val specsState by viewModel.specialisationsState.collectAsStateWithLifecycle()
    val reviewsState by viewModel.visibleReviewsState.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val specs = (specsState as? UiState.Success)?.data?.map { it.name }
        ?: BrandConfig.defaultSpecialisations.map { it.first }
    val reviews = ((reviewsState as? UiState.Success)?.data ?: emptyList())
        .filter { it.expertUid == viewModel.currentUserId }

    var selectedTab by remember(expert?.verificationStatus) {
        mutableStateOf(
            if (expert?.verificationEnum() == VerificationStatus.APPROVED) ExpertTab.OVERVIEW
            else ExpertTab.PROFILE
        )
    }

    var editingService by remember { mutableStateOf<ConsultationService?>(null) }
    var showNewServiceDialog by remember { mutableStateOf(false) }
    var meetingLinkBooking by remember { mutableStateOf<Booking?>(null) }
    var meetingLinkInput by remember { mutableStateOf("") }
    var cancelTarget by remember { mutableStateOf<Booking?>(null) }
    var cancelReason by remember { mutableStateOf("") }
    var checkInTarget by remember { mutableStateOf<Booking?>(null) }
    var checkInPinInput by remember { mutableStateOf("") }
    var exceptionDateInput by remember { mutableStateOf(TimeSlotUtils.upcomingDatesIso(7).first()) }
    var exceptionReasonInput by remember { mutableStateOf("") }
    var payoutAmountInput by remember { mutableStateOf("") }
    var payoutDestInput by remember(expert?.payoutDestinationMasked) {
        mutableStateOf(expert?.payoutDestinationMasked ?: "")
    }
    var showPayoutOnboardingDialog by remember { mutableStateOf(false) }

    val ext = HRConnectThemeExtras.colors

    Column(modifier = Modifier.fillMaxSize()) {
        // Verification / Suspension Banner
        expert?.let { exp ->
            val vStatus = exp.verificationEnum()
            val suspended = exp.accountStatusEnum() == AccountStatus.SUSPENDED
            if (vStatus != VerificationStatus.APPROVED || suspended) {
                Surface(
                    color = if (vStatus == VerificationStatus.REJECTED || suspended) MaterialTheme.colorScheme.errorContainer else ext.pendingBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            text = when {
                                suspended -> "Account Suspended — Suspended experts cannot receive new bookings."
                                vStatus == VerificationStatus.PENDING -> "Verification Status: PENDING ADMIN REVIEW"
                                else -> "Application Status: REJECTED — Update your profile below and resubmit."
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (vStatus == VerificationStatus.REJECTED && !exp.rejectionReason.isNullOrBlank()) {
                            Text(
                                text = "Admin feedback: ${exp.rejectionReason}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExpertTab.entries.forEach { tab ->
                FilterChip(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    label = { Text(tab.label) },
                    modifier = Modifier.testTag("expert_tab_${tab.name.lowercase()}")
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (selectedTab) {
                ExpertTab.OVERVIEW -> {
                    val upcomingCount = bookings.count {
                        it.bookingStatusEnum() in setOf(
                            BookingStatus.CONFIRMED, BookingStatus.SCHEDULED,
                            BookingStatus.IN_PROGRESS, BookingStatus.COMPLETION_PENDING
                        )
                    }
                    val completedCount = bookings.count { it.bookingStatusEnum() == BookingStatus.COMPLETED }
                    val avgRating = if (reviews.isEmpty()) "—" else String.format(Locale.ENGLISH, "%.1f ★", reviews.map { it.rating }.average())

                    item {
                        SectionHeader(
                            title = expert?.fullName ?: "HR Expert Portal",
                            subtitle = if (expert != null) "Referral Code: ${expert!!.referralCode} (10% fee) • Google: ${expert!!.verifiedGoogleEmail}" else "Apply to monetise your HR expertise"
                        )
                    }

                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricStatCard("Pending Hold", CurrencyUtils.formatInrFromPaise(wallet.pendingEarningsPaise), supporting = "Attendance + 24h hold", modifier = Modifier.weight(1f))
                            MetricStatCard("Available Payout", CurrencyUtils.formatInrFromPaise(wallet.availableForPayoutPaise), supporting = "${wallet.eligibleBookingIds.size} settled", modifier = Modifier.weight(1f))
                        }
                    }
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricStatCard("Gross Revenue", CurrencyUtils.formatInrFromPaise(wallet.grossRevenuePaise), supporting = "Fee: ${CurrencyUtils.formatInrFromPaise(wallet.platformCommissionPaise)}", modifier = Modifier.weight(1f))
                            MetricStatCard("Consultations", "$upcomingCount up • $completedCount done", supporting = "Rating: $avgRating", modifier = Modifier.weight(1f))
                        }
                    }

                    item {
                        SectionHeader("Verified Customer Reviews (${reviews.size})")
                    }
                    if (reviews.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Filled.Star,
                                title = "No Reviews Yet",
                                message = "Customer ratings and feedback from completed sessions will appear here."
                            )
                        }
                    } else {
                        items(reviews, key = { it.id }) { rev ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, ext.cardBorder)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text("${rev.customerName} • ${rev.rating}/5 ★", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    if (rev.comment.isNotBlank()) {
                                        Text(rev.comment, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }

                ExpertTab.SERVICES -> {
                    val canPublish = expert?.verificationEnum() == VerificationStatus.APPROVED
                    item {
                        SectionHeader(
                            title = "Consultation Services",
                            subtitle = if (canPublish) "Set INR pricing & duration (15–90m)" else "Available after admin approval",
                            actionLabel = if (canPublish) "+ Add Service" else null,
                            onAction = { showNewServiceDialog = true }
                        )
                    }
                    if (!canPublish) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Filled.WorkOutline,
                                title = "Approval Required",
                                message = "Submit your HR Expert application under Application / Profile. Once approved by an admin, you can publish paid services."
                            )
                        }
                    } else if (services.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Filled.Add,
                                title = "Create Your First Consultation Service",
                                message = "Tap + Add Service above to publish a 15, 30, 45, 60, or 90-minute consultation in INR."
                            )
                        }
                    } else {
                        items(services, key = { it.id }) { svc ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, ext.cardBorder)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(svc.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                        StatusPill(
                                            text = if (svc.active) "ACTIVE" else "INACTIVE",
                                            bgColor = if (svc.active) ext.verifiedBg else MaterialTheme.colorScheme.surfaceVariant,
                                            textColor = if (svc.active) ext.verified else ext.mutedText
                                        )
                                    }
                                    Text(
                                        "${CurrencyUtils.formatInr(svc.priceInr)} • ${svc.durationMinutes} min • ${svc.consultationFormat}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(svc.description, style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = { editingService = svc }) {
                                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Edit")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                ExpertTab.AVAILABILITY -> {
                    val exp = expert
                    item { SectionHeader("Weekly Availability (Asia/Kolkata)", "Toggle weekdays and set consulting hours") }
                    if (exp == null) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Filled.Schedule,
                                title = "Create Expert Profile First",
                                message = "Complete your application under Application / Profile to configure availability."
                            )
                        }
                    } else {
                        val dayNames = listOf(
                            1L to "Monday", 2L to "Tuesday", 3L to "Wednesday",
                            4L to "Thursday", 5L to "Friday", 6L to "Saturday", 7L to "Sunday"
                        )
                        items(dayNames, key = { it.first }) { (dayNum, dayLabel) ->
                            val existingRule = rules.firstOrNull { it.dayOfWeek == dayNum }
                            var enabled by remember(existingRule) { mutableStateOf(existingRule?.enabled ?: (dayNum <= 5L)) }
                            var startMin by remember(existingRule) { mutableStateOf(existingRule?.startMinute ?: 600L) }
                            var endMin by remember(existingRule) { mutableStateOf(existingRule?.endMinute ?: 1080L) }

                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, ext.cardBorder)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(dayLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                                    }
                                    if (enabled) {
                                        Text(
                                            "Window: ${TimeSlotUtils.formatMinute(startMin.toInt())} – ${TimeSlotUtils.formatMinute(endMin.toInt())} IST",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ext.mutedText
                                        )
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf(
                                                Triple("9am–6pm", 540L, 1080L),
                                                Triple("10am–7pm", 600L, 1140L),
                                                Triple("6pm–10pm", 1080L, 1320L)
                                            ).forEach { (lbl, s, e) ->
                                                FilterChip(
                                                    selected = startMin == s && endMin == e,
                                                    onClick = { startMin = s; endMin = e },
                                                    label = { Text(lbl) }
                                                )
                                            }
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.saveAvailabilityRule(
                                                dayOfWeek = dayNum,
                                                enabled = enabled,
                                                startMinute = startMin,
                                                endMinute = endMin,
                                                breakStartMinute = 810L,
                                                breakEndMinute = 870L
                                            )
                                        },
                                        enabled = !isBusy
                                    ) { Text("Save $dayLabel") }
                                }
                            }
                        }

                        item {
                            SectionHeader("Holiday / Unavailable Date Exceptions")
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, ext.cardBorder)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = exceptionDateInput,
                                        onValueChange = { exceptionDateInput = it },
                                        label = { Text("Date (YYYY-MM-DD)") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = exceptionReasonInput,
                                        onValueChange = { exceptionReasonInput = it },
                                        label = { Text("Reason (e.g., Public Holiday / Leave)") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = {
                                            viewModel.addAvailabilityException(exceptionDateInput, exceptionReasonInput)
                                            exceptionReasonInput = ""
                                        },
                                        enabled = !isBusy
                                    ) { Text("Block Date") }
                                }
                            }
                        }

                        items(exceptions, key = { it.id }) { ex ->
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
                                    Column {
                                        Text(TimeSlotUtils.formatDateDisplay(ex.dateIso), style = MaterialTheme.typography.titleSmall)
                                        Text(ex.reason, style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                                    }
                                    IconButton(onClick = { viewModel.deleteAvailabilityException(ex.id) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Remove exception", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }

                ExpertTab.BOOKINGS -> {
                    item { SectionHeader("Incoming Consultations", "${bookings.size} booking(s)") }
                    if (bookings.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Filled.CalendarMonth,
                                title = "No Incoming Bookings Yet",
                                message = "Confirmed customer bookings appear here with verified Google Meet links, check-in PIN, and dual-party completion controls."
                            )
                        }
                    } else {
                        items(bookings, key = { it.id }) { booking ->
                            val status = booking.bookingStatusEnum()
                            val eligibility = FinancialLedgerEngine.evaluateBookingPayoutEligibility(booking, settings)
                            val activeForSession = status in setOf(
                                BookingStatus.CONFIRMED,
                                BookingStatus.SCHEDULED,
                                BookingStatus.IN_PROGRESS,
                                BookingStatus.ATTENDANCE_PENDING,
                                BookingStatus.COMPLETION_PENDING
                            )
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, ext.cardBorder)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(booking.serviceTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                        StatusPill(booking.bookingStatus, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
                                    }
                                    Text(
                                        "Customer: ${booking.customerName} (${booking.verifiedCustomerGoogleEmail.ifBlank { "Google email pending" }})",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "${TimeSlotUtils.formatDateDisplay(booking.dateIso)} • ${TimeSlotUtils.formatMinuteRange(booking.startMinute.toInt(), booking.durationMinutes.toInt())} IST",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ext.mutedText
                                    )
                                    Text("Topic: ${booking.topic}", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "Net Payable: ${CurrencyUtils.formatInrFromPaise(booking.expertEarningPaise)} (Commission ${CurrencyUtils.formatCommissionPercent(booking.commissionRateBps)}: ${CurrencyUtils.formatInrFromPaise(booking.commissionPaise)} • ${booking.referralType})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = ext.verified
                                    )
                                    Text(
                                        "Meet Sync: ${booking.calendarSyncStatus} • Attendance: ${booking.attendanceStatus} (${booking.overlapMinutes}m overlap) • Source: ${booking.attendanceVerificationSource}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ext.mutedText
                                    )
                                    Text(
                                        "Payout Status: ${eligibility.status.name}" +
                                            if (eligibility.reasons.isNotEmpty()) " — ${eligibility.reasons.first()}" else " — Ready for withdrawal",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (eligibility.eligible) ext.verified else ext.pending
                                    )
                                    if (!booking.checkInPin.isNullOrBlank()) {
                                        Text(
                                            "Fallback Check-In PIN: ${booking.checkInPin} (Expert checked in: ${booking.expertCheckInAtMs > 0L})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (!booking.meetingUrl.isNullOrBlank()) {
                                        Text("Meet URL: ${booking.meetingUrl}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                    if (activeForSession) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { viewModel.syncCalendarAndMeet(booking) },
                                                enabled = !isBusy,
                                                modifier = Modifier.weight(1f)
                                            ) { Text("Sync Meet") }
                                            OutlinedButton(
                                                onClick = { viewModel.syncMeetAttendance(booking) },
                                                enabled = !isBusy,
                                                modifier = Modifier.weight(1f)
                                            ) { Text("Verify Attendance") }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    meetingLinkInput = booking.meetingUrl ?: "https://meet.google.com/"
                                                    meetingLinkBooking = booking
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("Meeting URL") }
                                            OutlinedButton(
                                                onClick = {
                                                    checkInPinInput = booking.checkInPin.orEmpty()
                                                    checkInTarget = booking
                                                },
                                                enabled = !isBusy,
                                                modifier = Modifier.weight(1f)
                                            ) { Text("Check-In") }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = { viewModel.confirmBookingCompletion(booking, isExpert = true) },
                                                enabled = !isBusy && !booking.expertCompletedConfirmed,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(if (booking.expertCompletedConfirmed) "Expert Confirmed" else "Confirm Complete")
                                            }
                                            TextButton(
                                                onClick = { cancelTarget = booking },
                                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                            ) { Text("Cancel") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                ExpertTab.EARNINGS -> {
                    item { SectionHeader("6-Bucket Earnings Wallet & Payouts", "Payouts require verified attendance, completion & 24h dispute window") }
                    expert?.let { exp ->
                        item {
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
                                        Text("Payout Bank / UPI Account", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        StatusPill(
                                            text = exp.payoutKycStatus ?: "NOT_CONFIGURED",
                                            bgColor = if (exp.payoutKycStatus == "LINKED_READY") ext.verifiedBg else ext.goldContainer,
                                            textColor = if (exp.payoutKycStatus == "LINKED_READY") ext.verified else ext.onGoldContainer
                                        )
                                    }
                                    Text(
                                        text = if (!exp.payoutDestinationMasked.isNullOrBlank()) {
                                            "Verified Destination (${exp.payoutAccountType ?: "UPI"}): ${exp.payoutDestinationMasked}"
                                        } else {
                                            "Link your UPI VPA or Indian Bank Account (IFSC + Account No.) via RazorpayX before requesting withdrawals."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ext.mutedText
                                    )
                                    OutlinedButton(
                                        onClick = { showPayoutOnboardingDialog = true },
                                        enabled = !isBusy,
                                        modifier = Modifier.testTag("configure_payout_account_button")
                                    ) {
                                        Text(if (exp.payoutDestinationMasked.isNullOrBlank()) "Configure Payout Account" else "Update Payout Account")
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, ext.cardBorder)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Gross Consultation Volume: ${CurrencyUtils.formatInrFromPaise(wallet.grossRevenuePaise)}", style = MaterialTheme.typography.bodyMedium)
                                Text("Platform Commission Deducted: ${CurrencyUtils.formatInrFromPaise(wallet.platformCommissionPaise)}", style = MaterialTheme.typography.bodyMedium)
                                Text("Gateway Fees (2% tracked): ${CurrencyUtils.formatInrFromPaise(wallet.gatewayFeePaise)} • TDS: ${CurrencyUtils.formatInrFromPaise(wallet.taxDeductionPaise)}", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                                Text("Net Settled Expert Earnings: ${CurrencyUtils.formatInrFromPaise(wallet.netEarningsPaise)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ext.verified)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("1. Pending (Attendance / 24h Dispute Window): ${CurrencyUtils.formatInrFromPaise(wallet.pendingEarningsPaise)}", style = MaterialTheme.typography.bodySmall, color = ext.pending)
                                Text("2. Available for Payout (${wallet.eligibleBookingIds.size} eligible booking(s)): ${CurrencyUtils.formatInrFromPaise(wallet.availableForPayoutPaise)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("3. Payout Processing / Requested: ${CurrencyUtils.formatInrFromPaise(wallet.payoutProcessingPaise)}", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                                Text("4. Paid Out Total: ${CurrencyUtils.formatInrFromPaise(wallet.paidOutPaise)}", style = MaterialTheme.typography.bodySmall, color = ext.verified)
                                Text("5. Disputed / On Hold: ${CurrencyUtils.formatInrFromPaise(wallet.disputedOrHeldPaise)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                Text("6. Refunded / Cancelled Adjustments: ${CurrencyUtils.formatInrFromPaise(wallet.refundedPaise)}", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)

                                if (expert != null && wallet.availableForPayoutPaise >= 10000L) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = payoutAmountInput,
                                        onValueChange = { payoutAmountInput = it.filter { ch -> ch.isDigit() } },
                                        label = { Text("Withdrawal Amount (INR, max ${wallet.availableForPayoutPaise / 100L})") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = payoutDestInput,
                                        onValueChange = { payoutDestInput = it },
                                        label = { Text("UPI ID or Bank Reference") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = {
                                            val amt = payoutAmountInput.toLongOrNull() ?: 0L
                                            viewModel.requestPayout(amt, payoutDestInput)
                                            payoutAmountInput = ""
                                        },
                                        enabled = !isBusy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Request Payout")
                                    }
                                } else if (wallet.pendingEarningsPaise > 0L) {
                                    Text(
                                        "Funds move from Pending to Available automatically once Google Meet attendance passes, completion is confirmed, and the 24-hour customer dispute window clears without disputes.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ext.mutedText
                                    )
                                }
                            }
                        }
                    }

                    item { SectionHeader("Payout History (${payouts.size})") }
                    items(payouts, key = { it.id }) { p ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, ext.cardBorder)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(CurrencyUtils.formatInrFromPaise(p.amountPaise), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    StatusPill(p.status, ext.goldContainer, ext.onGoldContainer)
                                }
                                Text("To: ${p.payoutDestination} • Bookings: ${p.eligibleBookingIds.size}", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                                if (!p.payoutReference.isNullOrBlank()) {
                                    Text("UTR/Ref: ${p.payoutReference}", style = MaterialTheme.typography.labelSmall, color = ext.verified)
                                }
                                if (!p.failureReason.isNullOrBlank()) {
                                    Text("Failure: ${p.failureReason} (Retries: ${p.retryCount})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    item { SectionHeader("Immutable Financial Ledger (${ledger.size})") }
                    items(ledger, key = { it.id }) { entry ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, ext.cardBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${entry.entryType} • ${entry.direction} (${entry.entryStatus})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    Text(entry.description, style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                                }
                                Text(CurrencyUtils.formatInrFromPaise(entry.amountPaise), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                ExpertTab.PROFILE -> {
                    item {
                        ExpertApplicationForm(
                            existing = expert,
                            defaultName = viewModel.currentUserDisplayName,
                            specialisationOptions = specs,
                            isBusy = isBusy,
                            onSubmit = { name, head, desig, org, yrs, bio, prim, selectedSpecs, skillsList, ind, li, vid, notes ->
                                viewModel.submitExpertApplication(
                                    fullName = name,
                                    headline = head,
                                    designation = desig,
                                    organization = org,
                                    yearsExperience = yrs,
                                    bio = bio,
                                    primarySpecialisation = prim,
                                    specialisations = selectedSpecs,
                                    skills = skillsList,
                                    industryExperience = ind,
                                    linkedInUrl = li,
                                    introVideoUrl = vid,
                                    verificationNotes = notes
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    if (showNewServiceDialog || editingService != null) {
        ServiceEditorDialog(
            existing = editingService,
            specialisations = specs,
            isBusy = isBusy,
            onDismiss = {
                showNewServiceDialog = false
                editingService = null
            },
            onSave = { title, desc, spec, dur, priceInr, format, prep, active ->
                viewModel.saveService(
                    existingServiceId = editingService?.id,
                    title = title,
                    description = desc,
                    specialisation = spec,
                    durationMinutes = dur,
                    priceInr = priceInr,
                    consultationFormat = format,
                    preparationInstructions = prep,
                    active = active
                )
                showNewServiceDialog = false
                editingService = null
            }
        )
    }

    meetingLinkBooking?.let { booking ->
        AlertDialog(
            onDismissRequest = { meetingLinkBooking = null },
            title = { Text("Share Consultation Meeting URL") },
            text = {
                OutlinedTextField(
                    value = meetingLinkInput,
                    onValueChange = { meetingLinkInput = it },
                    label = { Text("Meeting Link (https://...)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateMeetingUrl(booking, meetingLinkInput)
                    meetingLinkBooking = null
                }) { Text("Save Link") }
            },
            dismissButton = {
                TextButton(onClick = { meetingLinkBooking = null }) { Text("Cancel") }
            }
        )
    }

    cancelTarget?.let { booking ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text("Cancel Booking") },
            text = {
                OutlinedTextField(
                    value = cancelReason,
                    onValueChange = { cancelReason = it },
                    label = { Text("Reason for cancellation") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.cancelBooking(booking, cancelReason)
                        cancelReason = ""
                        cancelTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Confirm Cancel") }
            },
            dismissButton = {
                TextButton(onClick = { cancelTarget = null }) { Text("Keep") }
            }
        )
    }

    checkInTarget?.let { booking ->
        AlertDialog(
            onDismissRequest = { checkInTarget = null },
            title = { Text("Session Check-In (Fallback)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Primary attendance uses Google Meet REST API conference logs. Use this PIN check-in as supplementary verification if needed.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = checkInPinInput,
                        onValueChange = { checkInPinInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("6-Digit Session PIN") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.submitBookingCheckIn(booking, checkInPinInput)
                    checkInTarget = null
                }) { Text("Submit Check-In") }
            },
            dismissButton = {
                TextButton(onClick = { checkInTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showPayoutOnboardingDialog) {
        PayoutOnboardingDialog(
            isBusy = isBusy,
            onDismiss = { showPayoutOnboardingDialog = false },
            onSave = { accountType, vpa, holder, ifsc, acctNum ->
                viewModel.saveExpertPayoutOnboarding(
                    payoutAccountType = accountType,
                    vpaAddress = vpa,
                    bankAccountHolderName = holder,
                    bankIfsc = ifsc,
                    bankAccountNumber = acctNum
                )
                showPayoutOnboardingDialog = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayoutOnboardingDialog(
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit
) {
    var accountType by remember { mutableStateOf("UPI") }
    var vpa by remember { mutableStateOf("") }
    var holderName by remember { mutableStateOf("") }
    var ifsc by remember { mutableStateOf("") }
    var accountNumber by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RazorpayX Payout Onboarding") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Credentials are transmitted securely to the HRConnect backend and tokenized via RazorpayX. Full numbers are never stored in public Firestore.",
                    style = MaterialTheme.typography.bodySmall
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = accountType == "UPI",
                        onClick = { accountType = "UPI" },
                        label = { Text("UPI VPA") }
                    )
                    FilterChip(
                        selected = accountType == "BANK_ACCOUNT",
                        onClick = { accountType = "BANK_ACCOUNT" },
                        label = { Text("Bank Account (IMPS/NEFT)") }
                    )
                }
                if (accountType == "UPI") {
                    OutlinedTextField(
                        value = vpa,
                        onValueChange = { vpa = it.trim() },
                        label = { Text("UPI VPA (e.g. name@okaxis)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = holderName,
                        onValueChange = { holderName = it },
                        label = { Text("Account Holder Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ifsc,
                        onValueChange = { ifsc = it.uppercase().take(11) },
                        label = { Text("IFSC Code (11 chars)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = accountNumber,
                        onValueChange = { accountNumber = it.filter { c -> c.isDigit() }.take(18) },
                        label = { Text("Bank Account Number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(accountType, vpa, holderName, ifsc, accountNumber) },
                enabled = !isBusy
            ) { Text("Verify & Link Account") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpertApplicationForm(
    existing: ExpertProfile?,
    defaultName: String,
    specialisationOptions: List<String>,
    isBusy: Boolean,
    onSubmit: (String, String, String, String, Long, String, String, List<String>, List<String>, String, String, String, String) -> Unit
) {
    var fullName by remember { mutableStateOf(existing?.fullName ?: defaultName) }
    var headline by remember { mutableStateOf(existing?.headline ?: "") }
    var designation by remember { mutableStateOf(existing?.designation ?: "") }
    var organization by remember { mutableStateOf(existing?.organization ?: "") }
    var yearsExp by remember { mutableStateOf(existing?.yearsExperience?.toString() ?: "5") }
    var bio by remember { mutableStateOf(existing?.bio ?: "") }
    var primarySpec by remember { mutableStateOf(existing?.primarySpecialisation ?: specialisationOptions.firstOrNull() ?: "HR Business Partnering") }
    var additionalSpecs by remember { mutableStateOf(existing?.specialisations ?: emptyList()) }
    var skillsCsv by remember { mutableStateOf(existing?.skills?.joinToString(", ") ?: "") }
    var industryExp by remember { mutableStateOf(existing?.industryExperience ?: "") }
    var linkedIn by remember { mutableStateOf(existing?.linkedInUrl ?: "") }
    var introVideo by remember { mutableStateOf(existing?.introVideoUrl ?: "") }
    var verificationNotes by remember { mutableStateOf(existing?.verificationNotes ?: "") }

    LaunchedEffect(existing?.id) {
        if (existing != null) {
            fullName = existing.fullName
            headline = existing.headline
            designation = existing.designation
            organization = existing.organization ?: ""
            yearsExp = existing.yearsExperience.toString()
            bio = existing.bio
            primarySpec = existing.primarySpecialisation
            additionalSpecs = existing.specialisations
            skillsCsv = existing.skills.joinToString(", ")
            industryExp = existing.industryExperience ?: ""
            linkedIn = existing.linkedInUrl ?: ""
            introVideo = existing.introVideoUrl ?: ""
            verificationNotes = existing.verificationNotes ?: ""
        }
    }

    val ext = HRConnectThemeExtras.colors
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, ext.cardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = if (existing == null) "Become an HR Expert" else "Manage Expert Profile",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Applications are reviewed by HRConnect administrators before public directory listing.",
                style = MaterialTheme.typography.bodySmall,
                color = ext.mutedText
            )
            OutlinedTextField(value = fullName, onValueChange = { fullName = it }, label = { Text("Full Name *") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = headline, onValueChange = { headline = it }, label = { Text("Professional Headline *") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = designation, onValueChange = { designation = it }, label = { Text("Current / Previous Designation *") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = organization, onValueChange = { organization = it }, label = { Text("Current Organisation (Optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = yearsExp, onValueChange = { yearsExp = it.filter { c -> c.isDigit() } }, label = { Text("Years of HR Experience *") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = bio, onValueChange = { bio = it }, label = { Text("Professional Biography * (min 10 chars)") }, minLines = 3, modifier = Modifier.fillMaxWidth())

            Text("Primary Specialisation", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                specialisationOptions.take(10).forEach { spec ->
                    FilterChip(
                        selected = primarySpec == spec,
                        onClick = { primarySpec = spec },
                        label = { Text(spec) }
                    )
                }
            }

            OutlinedTextField(value = skillsCsv, onValueChange = { skillsCsv = it }, label = { Text("Professional Skills (comma-separated)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = industryExp, onValueChange = { industryExp = it }, label = { Text("Industry Experience") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = linkedIn, onValueChange = { linkedIn = it }, label = { Text("LinkedIn Profile URL (Optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = introVideo, onValueChange = { introVideo = it }, label = { Text("Intro / Sample Expertise Video URL (Optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = verificationNotes, onValueChange = { verificationNotes = it }, label = { Text("Verification Credentials / Certifications") }, minLines = 2, modifier = Modifier.fillMaxWidth())

            Button(
                onClick = {
                    val yrs = yearsExp.toLongOrNull() ?: 0L
                    val skillsList = skillsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    onSubmit(
                        fullName, headline, designation, organization, yrs, bio,
                        primarySpec, additionalSpecs, skillsList, industryExp,
                        linkedIn, introVideo, verificationNotes
                    )
                },
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("submit_expert_application_button")
            ) {
                Text(if (existing == null) "Submit Expert Application" else "Save Profile Changes")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceEditorDialog(
    existing: ConsultationService?,
    specialisations: List<String>,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Long, Long, String, String, Boolean) -> Unit
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var desc by remember { mutableStateOf(existing?.description ?: "") }
    var spec by remember { mutableStateOf(existing?.specialisation ?: specialisations.firstOrNull() ?: "HR Business Partnering") }
    var duration by remember { mutableStateOf(existing?.durationMinutes ?: 30L) }
    var priceInr by remember { mutableStateOf(existing?.priceInr?.toString() ?: "1000") }
    var format by remember { mutableStateOf(existing?.consultationFormat ?: BrandConfig.consultationFormats.first()) }
    var prep by remember { mutableStateOf(existing?.preparationInstructions ?: "") }
    var active by remember { mutableStateOf(existing?.active ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Consultation Service" else "Edit Service") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Service Title *") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description *") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = priceInr, onValueChange = { priceInr = it.filter { c -> c.isDigit() } }, label = { Text("Price (INR, min ₹100) *") }, modifier = Modifier.fillMaxWidth())

                Text("Duration", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrandConfig.supportedDurationsMinutes.forEach { d ->
                        FilterChip(selected = duration == d, onClick = { duration = d }, label = { Text("${d}m") })
                    }
                }

                Text("Format", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrandConfig.consultationFormats.forEach { fmt ->
                        FilterChip(selected = format == fmt, onClick = { format = fmt }, label = { Text(fmt) })
                    }
                }

                OutlinedTextField(value = prep, onValueChange = { prep = it }, label = { Text("Preparation Instructions (Optional)") }, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Active & Bookable")
                    Switch(checked = active, onCheckedChange = { active = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = priceInr.toLongOrNull() ?: 1000L
                    onSave(title, desc, spec, duration, p, format, prep, active)
                },
                enabled = !isBusy
            ) { Text("Save Service") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
