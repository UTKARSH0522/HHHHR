package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.BrandConfig
import com.example.core.CurrencyUtils
import com.example.core.TimeSlotUtils
import com.example.core.UiState
import com.example.data.model.Booking
import com.example.data.model.BookingStatus
import com.example.data.model.PaymentStatus
import com.example.ui.HRConnectViewModel
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.MetricStatCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.theme.HRConnectThemeExtras

private enum class CustomerTab(val label: String) {
    OVERVIEW("Overview"),
    BOOKINGS("My Bookings"),
    PAYMENTS("Payments"),
    SAVED("Saved Experts"),
    PROFILE("Profile & Prefs")
}

@Composable
fun CustomerDashboardScreen(
    viewModel: HRConnectViewModel,
    onOpenExpert: (String) -> Unit,
    onOpenPolicies: () -> Unit,
    onSignOut: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(CustomerTab.OVERVIEW) }
    val bookingsState by viewModel.customerBookingsState.collectAsStateWithLifecycle()
    val paymentsState by viewModel.customerPaymentsState.collectAsStateWithLifecycle()
    val expertsState by viewModel.approvedExpertsState.collectAsStateWithLifecycle()
    val userAccount by viewModel.userAccount.collectAsStateWithLifecycle()
    val privateInfo by viewModel.userPrivateInfo.collectAsStateWithLifecycle()
    val activeCheckout by viewModel.activeBookingForCheckout.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val bookings = (bookingsState as? UiState.Success)?.data ?: emptyList()
    val payments = (paymentsState as? UiState.Success)?.data ?: emptyList()
    val experts = (expertsState as? UiState.Success)?.data ?: emptyList()
    val savedExperts = experts.filter { (userAccount?.savedExpertIds ?: emptyList()).contains(it.id) }

    var cancelTarget by remember { mutableStateOf<Booking?>(null) }
    var cancelReason by remember { mutableStateOf("") }
    var rescheduleTarget by remember { mutableStateOf<Booking?>(null) }
    var reviewTarget by remember { mutableStateOf<Booking?>(null) }
    var reviewRating by remember { mutableIntStateOf(5) }
    var reviewComment by remember { mutableStateOf("") }
    var checkInTarget by remember { mutableStateOf<Booking?>(null) }
    var checkInPinInput by remember { mutableStateOf("") }
    var disputeTarget by remember { mutableStateOf<Booking?>(null) }
    var disputeReasonInput by remember { mutableStateOf("") }
    var disputeEvidenceInput by remember { mutableStateOf("") }

    val context = LocalContext.current
    val ext = HRConnectThemeExtras.colors

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CustomerTab.entries.forEach { tab ->
                FilterChip(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    label = { Text(tab.label) },
                    modifier = Modifier.testTag("customer_tab_${tab.name.lowercase()}")
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (selectedTab) {
                CustomerTab.OVERVIEW -> {
                    val upcoming = bookings.filter {
                        it.bookingStatusEnum() in setOf(
                            BookingStatus.CONFIRMED,
                            BookingStatus.SCHEDULED,
                            BookingStatus.IN_PROGRESS,
                            BookingStatus.ATTENDANCE_PENDING,
                            BookingStatus.COMPLETION_PENDING
                        )
                    }
                    val pending = bookings.filter { it.bookingStatusEnum() == BookingStatus.PENDING_PAYMENT && !it.isHoldExpired() }
                    val completed = bookings.filter { it.bookingStatusEnum() == BookingStatus.COMPLETED }

                    item {
                        SectionHeader(
                            title = "Welcome, ${userAccount?.displayName ?: viewModel.currentUserDisplayName}",
                            subtitle = "Your consultations, payments, and saved HR advisors"
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            MetricStatCard("Upcoming", upcoming.size.toString(), modifier = Modifier.weight(1f))
                            MetricStatCard("Pending Hold", pending.size.toString(), modifier = Modifier.weight(1f))
                            MetricStatCard("Completed", completed.size.toString(), modifier = Modifier.weight(1f))
                        }
                    }

                    if (pending.isNotEmpty()) {
                        item { SectionHeader("Action Required: Pending Payment Holds") }
                        items(pending, key = { "hold_${it.id}" }) { booking ->
                            BookingItemCard(
                                booking = booking,
                                onPayNow = { viewModel.startCheckout(booking) },
                                onReleaseExpired = { viewModel.releaseExpiredHold(booking) },
                                onReschedule = { rescheduleTarget = booking },
                                onCancel = { cancelTarget = booking },
                                onCheckIn = {
                                    checkInPinInput = booking.checkInPin.orEmpty()
                                    checkInTarget = booking
                                },
                                onConfirmCompleted = { viewModel.confirmBookingCompletion(booking, isExpert = false) },
                                onDispute = { disputeTarget = booking },
                                onReview = { reviewTarget = booking },
                                onOpenMeeting = { url ->
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                }
                            )
                        }
                    }

                    item { SectionHeader("Upcoming & Recent Consultations") }
                    if (bookings.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Filled.CalendarToday,
                                title = "No Consultations Yet",
                                message = "Explore verified HR experts in the Marketplace tab to reserve your first 1:1 session."
                            )
                        }
                    } else {
                        items(bookings.take(5), key = { "ov_${it.id}" }) { booking ->
                            BookingItemCard(
                                booking = booking,
                                onPayNow = { viewModel.startCheckout(booking) },
                                onReleaseExpired = { viewModel.releaseExpiredHold(booking) },
                                onReschedule = { rescheduleTarget = booking },
                                onCancel = { cancelTarget = booking },
                                onCheckIn = {
                                    checkInPinInput = booking.checkInPin.orEmpty()
                                    checkInTarget = booking
                                },
                                onConfirmCompleted = { viewModel.confirmBookingCompletion(booking, isExpert = false) },
                                onDispute = { disputeTarget = booking },
                                onReview = { reviewTarget = booking },
                                onOpenMeeting = { url ->
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                }
                            )
                        }
                    }
                }

                CustomerTab.BOOKINGS -> {
                    item { SectionHeader("All My Bookings", "${bookings.size} total consultation(s)") }
                    if (bookings.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Filled.CalendarToday,
                                title = "No Bookings Found",
                                message = "Your booked HR consultations, meeting links, and review options will appear here."
                            )
                        }
                    } else {
                        items(bookings, key = { it.id }) { booking ->
                            BookingItemCard(
                                booking = booking,
                                onPayNow = { viewModel.startCheckout(booking) },
                                onReleaseExpired = { viewModel.releaseExpiredHold(booking) },
                                onReschedule = { rescheduleTarget = booking },
                                onCancel = { cancelTarget = booking },
                                onCheckIn = {
                                    checkInPinInput = booking.checkInPin.orEmpty()
                                    checkInTarget = booking
                                },
                                onConfirmCompleted = { viewModel.confirmBookingCompletion(booking, isExpert = false) },
                                onDispute = { disputeTarget = booking },
                                onReview = { reviewTarget = booking },
                                onOpenMeeting = { url ->
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                }
                            )
                        }
                    }
                }

                CustomerTab.PAYMENTS -> {
                    item { SectionHeader("Payment History", "All amounts recorded in INR") }
                    if (payments.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Filled.Payment,
                                title = "No Transactions Yet",
                                message = "Successful and failed Razorpay test transactions appear here with order references."
                            )
                        }
                    } else {
                        items(payments, key = { it.id }) { pay ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, ext.cardBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            CurrencyUtils.formatInrFromPaise(pay.amountPaise),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        val isOk = pay.statusEnum() == PaymentStatus.SUCCESS
                                        StatusPill(
                                            text = pay.paymentStatus,
                                            bgColor = if (isOk) ext.verifiedBg else MaterialTheme.colorScheme.errorContainer,
                                            textColor = if (isOk) ext.verified else MaterialTheme.colorScheme.error
                                        )
                                    }
                                    Text("Order: ${pay.razorpayOrderId}", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                                    if (!pay.razorpayPaymentId.isNullOrBlank()) {
                                        Text("Payment Ref: ${pay.razorpayPaymentId} (${pay.paymentMethod})", style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                                    }
                                    if (pay.refundedAmountPaise > 0L) {
                                        Text("Refunded: ${CurrencyUtils.formatInrFromPaise(pay.refundedAmountPaise)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    Text(
                                        "Booking Ref: ${pay.bookingId}${if (pay.testMode) " • Sandbox" else if (pay.serverVerified) " • Server Verified" else ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ext.mutedText
                                    )
                                }
                            }
                        }
                    }
                }

                CustomerTab.SAVED -> {
                    item { SectionHeader("Saved HR Experts", "${savedExperts.size} bookmarked") }
                    if (savedExperts.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Filled.Bookmark,
                                title = "No Saved Experts",
                                message = "Tap the bookmark icon on any HR expert card to save them for quick access."
                            )
                        }
                    } else {
                        items(savedExperts, key = { it.id }) { exp ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenExpert(exp.id) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, ext.cardBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(exp.fullName, style = MaterialTheme.typography.titleMedium)
                                        Text(exp.headline, style = MaterialTheme.typography.bodySmall, color = ext.mutedText)
                                    }
                                    TextButton(onClick = { onOpenExpert(exp.id) }) {
                                        Text("View")
                                    }
                                }
                            }
                        }
                    }
                }

                CustomerTab.PROFILE -> {
                    item {
                        ProfileSettingsCard(
                            initialName = userAccount?.displayName ?: viewModel.currentUserDisplayName,
                            initialEmail = privateInfo?.email ?: viewModel.currentUserEmail,
                            initialPhone = privateInfo?.phone ?: "",
                            initialCity = privateInfo?.city ?: "",
                            initialNotifyBookings = privateInfo?.notifyBookings ?: true,
                            initialNotifyPayments = privateInfo?.notifyPayments ?: true,
                            initialNotifyReviews = privateInfo?.notifyReviews ?: true,
                            isBusy = isBusy,
                            onSave = { name, email, phone, city, nBook, nPay, nRev ->
                                viewModel.updateProfileAndPrefs(name, email, phone, city, nBook, nPay, nRev)
                            },
                            onOpenPolicies = onOpenPolicies,
                            onSignOut = onSignOut
                        )
                    }
                }
            }
        }
    }

    if (BrandConfig.allowDebugSandboxFallback) {
        activeCheckout?.let { booking ->
            RazorpayTestCheckoutDialog(
                booking = booking,
                isBusy = isBusy,
                onDismiss = { viewModel.setActiveCheckoutBooking(null) },
                onPaySuccess = { method -> viewModel.completeTestPayment(booking, method) {} },
                onSimulateFailure = { method -> viewModel.failTestPayment(booking, method) }
            )
        }
    }

    rescheduleTarget?.let { booking ->
        RescheduleBookingDialog(
            booking = booking,
            isBusy = isBusy,
            onDismiss = { rescheduleTarget = null },
            onConfirm = { newDateIso, newStartMinute, reason ->
                viewModel.rescheduleBooking(booking, newDateIso, newStartMinute, reason)
                rescheduleTarget = null
            }
        )
    }

    checkInTarget?.let { booking ->
        AlertDialog(
            onDismissRequest = { checkInTarget = null },
            title = { Text("Supplementary Session Check-In") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Supplementary fallback check-in (separate from Google Meet REST API attendance). Enter your 6-digit booking Check-In PIN:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = checkInPinInput,
                        onValueChange = { checkInPinInput = it.filter { ch -> ch.isDigit() }.take(6) },
                        label = { Text("Check-In PIN") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.submitBookingCheckIn(booking, checkInPinInput)
                    checkInTarget = null
                }) { Text("Confirm Check-In") }
            },
            dismissButton = {
                TextButton(onClick = { checkInTarget = null }) { Text("Cancel") }
            }
        )
    }

    disputeTarget?.let { booking ->
        AlertDialog(
            onDismissRequest = { disputeTarget = null },
            title = { Text("Report Consultation Issue / Dispute") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Opening a dispute freezes the expert payout immediately until an administrator reviews Google Meet logs and issues a resolution.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = disputeReasonInput,
                        onValueChange = { disputeReasonInput = it },
                        label = { Text("Issue Summary * (min 10 chars)") },
                        placeholder = { Text("e.g., Expert did not join Google Meet") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = disputeEvidenceInput,
                        onValueChange = { disputeEvidenceInput = it },
                        label = { Text("Additional details / timestamps") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.raiseDispute(booking, disputeReasonInput, disputeEvidenceInput)
                        disputeReasonInput = ""
                        disputeEvidenceInput = ""
                        disputeTarget = null
                    },
                    enabled = disputeReasonInput.trim().length >= 10,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Submit Dispute & Hold Payout") }
            },
            dismissButton = {
                TextButton(onClick = { disputeTarget = null }) { Text("Cancel") }
            }
        )
    }

    cancelTarget?.let { booking ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text("Cancel Consultation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Provide a reason for cancelling ${booking.serviceTitle}:")
                    OutlinedTextField(
                        value = cancelReason,
                        onValueChange = { cancelReason = it },
                        label = { Text("Cancellation reason") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
                TextButton(onClick = { cancelTarget = null }) { Text("Keep Booking") }
            }
        )
    }

    reviewTarget?.let { booking ->
        AlertDialog(
            onDismissRequest = { reviewTarget = null },
            title = { Text("Review ${booking.expertName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Rate your completed consultation (${booking.serviceTitle}):")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..5).forEach { star ->
                            IconButton(onClick = { reviewRating = star }) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = "$star stars",
                                    tint = if (star <= reviewRating) ext.gold else ext.cardBorder,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = reviewComment,
                        onValueChange = { reviewComment = it },
                        label = { Text("Written feedback (optional)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.submitReview(booking, reviewRating, reviewComment)
                    reviewComment = ""
                    reviewTarget = null
                }) { Text("Submit Verified Review") }
            },
            dismissButton = {
                TextButton(onClick = { reviewTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun BookingItemCard(
    booking: Booking,
    onPayNow: () -> Unit,
    onReleaseExpired: () -> Unit,
    onReschedule: () -> Unit,
    onCancel: () -> Unit,
    onCheckIn: () -> Unit,
    onConfirmCompleted: () -> Unit,
    onDispute: () -> Unit,
    onReview: () -> Unit,
    onOpenMeeting: (String) -> Unit
) {
    val ext = HRConnectThemeExtras.colors
    val status = booking.bookingStatusEnum()
    val expired = booking.isHoldExpired()

    val (bg, fg, label) = when {
        expired -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, "HOLD EXPIRED")
        status == BookingStatus.CONFIRMED || status == BookingStatus.SCHEDULED -> Triple(ext.verifiedBg, ext.verified, status.name)
        status == BookingStatus.IN_PROGRESS || status == BookingStatus.COMPLETION_PENDING || status == BookingStatus.ATTENDANCE_PENDING ->
            Triple(ext.goldContainer, ext.onGoldContainer, status.name)
        status == BookingStatus.COMPLETED -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, "COMPLETED")
        status == BookingStatus.DISPUTED -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, "DISPUTED")
        status == BookingStatus.CANCELLED -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, "CANCELLED")
        status == BookingStatus.REFUNDED -> Triple(MaterialTheme.colorScheme.surfaceVariant, ext.mutedText, "REFUNDED")
        else -> Triple(ext.pendingBg, ext.pending, "PENDING PAYMENT")
    }

    val activeSessionStates = setOf(
        BookingStatus.CONFIRMED, BookingStatus.SCHEDULED,
        BookingStatus.IN_PROGRESS, BookingStatus.ATTENDANCE_PENDING,
        BookingStatus.COMPLETION_PENDING
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, ext.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(booking.serviceTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusPill(label, bg, fg)
            }
            Text("Expert: ${booking.expertName}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(
                "${TimeSlotUtils.formatDateDisplay(booking.dateIso)} • ${TimeSlotUtils.formatMinuteRange(booking.startMinute.toInt(), booking.durationMinutes.toInt())}",
                style = MaterialTheme.typography.bodySmall,
                color = ext.mutedText
            )
            Text("Topic: ${booking.topic}", style = MaterialTheme.typography.bodySmall)
            if (booking.verifiedCustomerGoogleEmail.isNotBlank()) {
                Text(
                    "Join Meet with: ${booking.verifiedCustomerGoogleEmail} • Attendance: ${booking.attendanceStatus}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (!booking.checkInPin.isNullOrBlank() && status in activeSessionStates) {
                Text(
                    "Session Check-In PIN: ${booking.checkInPin} • Calendar: ${booking.calendarSyncStatus}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.mutedText
                )
            }
            if (!booking.preparationInstructions.isNullOrBlank()) {
                Text("Prep: ${booking.preparationInstructions}", style = MaterialTheme.typography.labelSmall, color = ext.mutedText)
            }
            Text(
                "Fee: ${CurrencyUtils.formatInrFromPaise(booking.amountPaise)} • Payment: ${booking.paymentStatus}" +
                    if (booking.refundedAmountPaise > 0L) " • Refunded: ${CurrencyUtils.formatInrFromPaise(booking.refundedAmountPaise)}" else "",
                style = MaterialTheme.typography.labelMedium
            )

            if (!booking.meetingUrl.isNullOrBlank() && (status in activeSessionStates || status == BookingStatus.COMPLETED)) {
                Button(
                    onClick = { onOpenMeeting(booking.meetingUrl) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.VideoCall, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Join Google Meet Consultation")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status == BookingStatus.PENDING_PAYMENT && !expired) {
                    Button(onClick = onPayNow, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Payment, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Complete Payment")
                    }
                }
                if (expired) {
                    OutlinedButton(onClick = onReleaseExpired, modifier = Modifier.weight(1f)) {
                        Text("Release Expired Hold")
                    }
                }
                if (status in setOf(BookingStatus.CONFIRMED, BookingStatus.SCHEDULED) && BrandConfig.isBackendConfigured) {
                    OutlinedButton(onClick = onReschedule, modifier = Modifier.weight(1f)) {
                        Text("Reschedule")
                    }
                }
                if (status in setOf(BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED, BookingStatus.SCHEDULED) && !expired) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cancel")
                    }
                }
                if (status in activeSessionStates && booking.customerCheckInAtMs == 0L) {
                    OutlinedButton(onClick = onCheckIn, modifier = Modifier.weight(1f)) {
                        Text("Check-In")
                    }
                }
            }

            if (status in activeSessionStates && !booking.customerCompletedConfirmed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConfirmCompleted, modifier = Modifier.weight(1f)) {
                        Text("Confirm Completed")
                    }
                    OutlinedButton(
                        onClick = onDispute,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Report Issue")
                    }
                }
            } else if (status == BookingStatus.COMPLETED && booking.isDisputeWindowOpen() && booking.disputeStatus == "NONE") {
                OutlinedButton(
                    onClick = onDispute,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Report Issue / Dispute (24h Window)")
                }
            }

            if (status == BookingStatus.COMPLETED && !booking.reviewed) {
                Button(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.RateReview, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Leave Verified Review")
                }
            }
        }
    }
}

@Composable
private fun ProfileSettingsCard(
    initialName: String,
    initialEmail: String,
    initialPhone: String,
    initialCity: String,
    initialNotifyBookings: Boolean,
    initialNotifyPayments: Boolean,
    initialNotifyReviews: Boolean,
    isBusy: Boolean,
    onSave: (String, String, String, String, Boolean, Boolean, Boolean) -> Unit,
    onOpenPolicies: () -> Unit,
    onSignOut: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var email by remember { mutableStateOf(initialEmail) }
    var phone by remember { mutableStateOf(initialPhone) }
    var city by remember { mutableStateOf(initialCity) }
    var nBookings by remember { mutableStateOf(initialNotifyBookings) }
    var nPayments by remember { mutableStateOf(initialNotifyPayments) }
    var nReviews by remember { mutableStateOf(initialNotifyReviews) }

    LaunchedEffect(initialName, initialEmail, initialPhone, initialCity) {
        name = initialName
        email = initialEmail
        phone = initialPhone
        city = initialCity
        nBookings = initialNotifyBookings
        nPayments = initialNotifyPayments
        nReviews = initialNotifyReviews
    }

    val ext = HRConnectThemeExtras.colors

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, ext.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Account & Notification Preferences", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email (Private)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone Number (Private)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("City (India)") }, modifier = Modifier.fillMaxWidth())

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Booking updates", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = nBookings, onCheckedChange = { nBookings = it })
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Payment & payout alerts", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = nPayments, onCheckedChange = { nPayments = it })
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Review notifications", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = nReviews, onCheckedChange = { nReviews = it })
            }

            Button(
                onClick = { onSave(name, email, phone, city, nBookings, nPayments, nReviews) },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Profile & Preferences") }

            OutlinedButton(onClick = onOpenPolicies, modifier = Modifier.fillMaxWidth()) {
                Text("View Legal, Privacy & Refund Policies")
            }

            OutlinedButton(
                onClick = onSignOut,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sign_out_button")
            ) {
                Text("Sign Out")
            }
        }
    }
}

@Composable
private fun RescheduleBookingDialog(
    booking: Booking,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, Int, String) -> Unit
) {
    val upcomingDates = remember { TimeSlotUtils.upcomingDatesIso(14) }
    var selectedDate by remember { mutableStateOf(upcomingDates.getOrElse(1) { upcomingDates.first() }) }
    var selectedStartMinute by remember { mutableIntStateOf(600) }
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reschedule Consultation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Rescheduling updates your slot lock and syncs Google Calendar (allowed >= 4 hours before session start).",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    upcomingDates.take(7).forEach { dateIso ->
                        FilterChip(
                            selected = selectedDate == dateIso,
                            onClick = { selectedDate = dateIso },
                            label = { Text(TimeSlotUtils.formatDateDisplay(dateIso)) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(600, 660, 720, 900, 960, 1020, 1080).forEach { minute ->
                        FilterChip(
                            selected = selectedStartMinute == minute,
                            onClick = { selectedStartMinute = minute },
                            label = { Text(TimeSlotUtils.formatMinute(minute)) }
                        )
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedDate, selectedStartMinute, reason) },
                enabled = !isBusy
            ) { Text("Confirm Reschedule") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep Slot") }
        }
    )
}
