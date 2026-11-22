package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.example.data.model.ConsultationService
import com.example.data.model.Review
import com.example.data.model.TimeSlotOption
import com.example.ui.HRConnectViewModel
import com.example.ui.components.DemoBadge
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.ExpertAvatar
import com.example.ui.components.SectionHeader
import com.example.ui.components.VerifiedBadge
import com.example.ui.theme.HRConnectThemeExtras
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExpertProfileBookingScreen(
    expertId: String,
    viewModel: HRConnectViewModel,
    onBack: () -> Unit,
    onBookingConfirmedGoToBookings: () -> Unit
) {
    BackHandler { onBack() }

    LaunchedEffect(expertId) {
        viewModel.selectExpert(expertId)
    }

    val context = LocalContext.current
    val expertsState by viewModel.approvedExpertsState.collectAsStateWithLifecycle()
    val servicesState by viewModel.activeServicesState.collectAsStateWithLifecycle()
    val reviewsState by viewModel.visibleReviewsState.collectAsStateWithLifecycle()
    val rules by viewModel.selectedExpertRules.collectAsStateWithLifecycle()
    val exceptions by viewModel.selectedExpertExceptions.collectAsStateWithLifecycle()
    val locks by viewModel.selectedExpertLocks.collectAsStateWithLifecycle()
    val activeCheckoutBooking by viewModel.activeBookingForCheckout.collectAsStateWithLifecycle()
    val settings by viewModel.platformSettings.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val expert = (expertsState as? UiState.Success)?.data?.firstOrNull { it.id == expertId }
    val expertServices = ((servicesState as? UiState.Success)?.data ?: emptyList())
        .filter { it.expertId == expertId && it.active }
    val expertReviews = ((reviewsState as? UiState.Success)?.data ?: emptyList())
        .filter { it.expertId == expertId }
    val ratingStats = remember(expertReviews) {
        viewModel.ratingStatsForExpert(expertId, expertReviews)
    }

    var selectedServiceForBooking by remember { mutableStateOf<ConsultationService?>(null) }
    var reportReviewTarget by remember { mutableStateOf<Review?>(null) }
    var reportReason by remember { mutableStateOf("") }

    val ext = HRConnectThemeExtras.colors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(expert?.fullName ?: "HR Expert Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back_from_expert_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (expert == null) {
            EmptyStateCard(
                icon = Icons.Filled.Schedule,
                title = "Loading Expert Profile",
                message = "Retrieving verified expert details and consultation services...",
                modifier = Modifier.padding(innerPadding).padding(16.dp)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Profile Header Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, ext.cardBorder)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ExpertAvatar(fullName = expert.fullName, size = 64.dp, isDemo = expert.demoProfile)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    VerifiedBadge()
                                    if (expert.demoProfile) DemoBadge()
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(expert.fullName, style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    "${expert.designation}${if (!expert.organization.isNullOrBlank()) " • ${expert.organization}" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ext.mutedText
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = expert.headline,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${expert.yearsExperience} years HR experience • Timezone: ${expert.timezone}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ext.mutedText
                        )

                        if (!expert.industryExperience.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Industries: ${expert.industryExperience}",
                                style = MaterialTheme.typography.labelMedium,
                                color = ext.mutedText
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(expert.bio, style = MaterialTheme.typography.bodyMedium)

                        if (expert.specialisations.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Specialisations", style = MaterialTheme.typography.labelLarge)
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                expert.specialisations.forEach { spec ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            spec,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (expert.skills.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Key Skills", style = MaterialTheme.typography.labelLarge)
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                expert.skills.forEach { skill ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            skill,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (!expert.introVideoUrl.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(expert.introVideoUrl)))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.PlayCircleOutline, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Watch Expert Introduction Video")
                            }
                        }
                    }
                }
            }

            // Consultation Services Section
            item {
                SectionHeader(
                    title = "Consultation Services",
                    subtitle = "Select a service to view live IST availability"
                )
            }

            if (expertServices.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Filled.VideoCall,
                        title = "No Active Services Published",
                        message = "This expert has not published active consultation services yet."
                    )
                }
            } else {
                items(expertServices, key = { it.id }) { svc ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, ext.cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(svc.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${svc.durationMinutes} min • ${svc.consultationFormat} • ${svc.specialisation}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = ext.mutedText
                                    )
                                }
                                Text(
                                    text = CurrencyUtils.formatInr(svc.priceInr),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(svc.description, style = MaterialTheme.typography.bodySmall)
                            if (!svc.preparationInstructions.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Preparation: ${svc.preparationInstructions}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ext.mutedText
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { selectedServiceForBooking = svc },
                                enabled = expert.expertUid != viewModel.currentUserId,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("book_service_${svc.id}")
                            ) {
                                Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (expert.expertUid == viewModel.currentUserId) {
                                        "Your Own Service (Self-Booking Disabled)"
                                    } else {
                                        "Select Slot & Book (${CurrencyUtils.formatInr(svc.priceInr)})"
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Verified Reviews Section
            item {
                SectionHeader(
                    title = "Verified Customer Reviews",
                    subtitle = if (ratingStats.reviewCount > 0) {
                        String.format(Locale.ENGLISH, "%.1f average across %d completed bookings", ratingStats.averageRating, ratingStats.reviewCount)
                    } else {
                        "Only customers with completed consultations can submit reviews"
                    }
                )
            }

            if (expertReviews.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Filled.Star,
                        title = "No Verified Reviews Yet",
                        message = "HRConnect never fabricates testimonials. Reviews appear here after completed consultations."
                    )
                }
            } else {
                items(expertReviews, key = { it.id }) { rev ->
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(rev.customerName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(Icons.Filled.Star, contentDescription = null, tint = ext.gold, modifier = Modifier.size(15.dp))
                                    Text("${rev.rating}/5", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                                IconButton(
                                    onClick = { reportReviewTarget = rev },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Filled.Flag, contentDescription = "Report review", modifier = Modifier.size(16.dp), tint = ext.mutedText)
                                }
                            }
                            if (rev.comment.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(rev.comment, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    selectedServiceForBooking?.let { svc ->
        BookingSlotSheet(
            service = svc,
            expertName = expert?.fullName ?: svc.expertName,
            verifiedCustomerEmail = viewModel.currentUserEmail,
            verifiedExpertEmail = expert?.verifiedGoogleEmail.orEmpty(),
            rules = rules,
            exceptions = exceptions,
            locks = locks,
            isBusy = isBusy,
            holdMinutes = settings.paymentHoldMinutes.toInt(),
            onDismiss = { selectedServiceForBooking = null },
            onConfirmReserve = { dateIso, slot, topic, refCode ->
                viewModel.createBookingHold(
                    expertId = svc.expertId,
                    serviceId = svc.id,
                    dateIso = dateIso,
                    startMinute = slot.startMinute,
                    topic = topic,
                    referralCode = refCode
                ) { booked ->
                    selectedServiceForBooking = null
                    viewModel.startCheckout(booked) {
                        onBookingConfirmedGoToBookings()
                    }
                }
            }
        )
    }

    // Debug-only Sandbox Test Checkout Sheet (only opened when Razorpay API keys aren't active or in offline sandbox)
    if (BrandConfig.allowDebugSandboxFallback) {
        activeCheckoutBooking?.let { booking ->
            RazorpayTestCheckoutDialog(
                booking = booking,
                isBusy = isBusy,
                onDismiss = { viewModel.setActiveCheckoutBooking(null) },
                onPaySuccess = { method ->
                    viewModel.completeTestPayment(booking, method) {
                        onBookingConfirmedGoToBookings()
                    }
                },
                onSimulateFailure = { method ->
                    viewModel.failTestPayment(booking, method)
                }
            )
        }
    }

    reportReviewTarget?.let { review ->
        AlertDialog(
            onDismissRequest = { reportReviewTarget = null },
            title = { Text("Report Review") },
            text = {
                OutlinedTextField(
                    value = reportReason,
                    onValueChange = { reportReason = it },
                    label = { Text("Reason for moderation") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.reportReview(review.id, reportReason)
                    reportReason = ""
                    reportReviewTarget = null
                }) { Text("Report") }
            },
            dismissButton = {
                TextButton(onClick = { reportReviewTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BookingSlotSheet(
    service: ConsultationService,
    expertName: String,
    verifiedCustomerEmail: String,
    verifiedExpertEmail: String,
    rules: List<com.example.data.model.AvailabilityRule>,
    exceptions: List<com.example.data.model.AvailabilityException>,
    locks: List<com.example.data.model.SlotLock>,
    isBusy: Boolean,
    holdMinutes: Int,
    onDismiss: () -> Unit,
    onConfirmReserve: (String, TimeSlotOption, String, String) -> Unit
) {
    val upcomingDates = remember { TimeSlotUtils.upcomingDatesIso(14) }
    var selectedDate by remember { mutableStateOf(upcomingDates.first()) }
    var selectedSlot by remember { mutableStateOf<TimeSlotOption?>(null) }
    var topic by remember { mutableStateOf("") }
    var referralCode by remember { mutableStateOf("") }

    val availableSlots = remember(selectedDate, service.durationMinutes, rules, exceptions, locks) {
        com.example.data.repository.MarketplaceRepository.computeAvailableSlots(
            dateIso = selectedDate,
            durationMinutes = service.durationMinutes.toInt(),
            rules = rules,
            exceptions = exceptions,
            slotLocks = locks
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Book ${service.title}", style = MaterialTheme.typography.titleLarge)
            Text(
                "$expertName • ${service.durationMinutes} min • ${CurrencyUtils.formatInr(service.priceInr)}",
                style = MaterialTheme.typography.bodySmall,
                color = HRConnectThemeExtras.colors.mutedText
            )

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = buildString {
                        append("Verified Google Meet account: $verifiedCustomerEmail.")
                        if (verifiedExpertEmail.isNotBlank()) {
                            append(" Expert joins via $verifiedExpertEmail.")
                        }
                        append(" Join the consultation using this exact Google account for attendance tracking.")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Text("1. Choose Date (IST / Asia/Kolkata)", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                upcomingDates.forEach { dateIso ->
                    FilterChip(
                        selected = selectedDate == dateIso,
                        onClick = {
                            selectedDate = dateIso
                            selectedSlot = null
                        },
                        label = { Text(TimeSlotUtils.formatDateDisplay(dateIso)) }
                    )
                }
            }

            Text("2. Available Slots", style = MaterialTheme.typography.labelLarge)
            if (availableSlots.isEmpty()) {
                Text(
                    "No open slots on ${TimeSlotUtils.formatDateDisplay(selectedDate)}. Select another date.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableSlots.take(12).forEach { slot ->
                        FilterChip(
                            selected = selectedSlot == slot,
                            onClick = { selectedSlot = slot },
                            label = { Text(TimeSlotUtils.formatMinute(slot.startMinute)) },
                            modifier = Modifier.testTag("slot_chip_${slot.startMinute}")
                        )
                    }
                }
            }

            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it },
                label = { Text("Consultation Topic / Agenda *") },
                placeholder = { Text("e.g., Structuring ESOP & CTC for Series A hires") },
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("booking_topic_input")
            )

            OutlinedTextField(
                value = referralCode,
                onValueChange = { referralCode = it },
                label = { Text("Expert Referral Code (Optional, 10% platform fee)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val slot = selectedSlot ?: return@Button
                        onConfirmReserve(selectedDate, slot, topic, referralCode)
                    },
                    enabled = !isBusy && selectedSlot != null && topic.trim().length >= 3,
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("reserve_slot_button")
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Hold Slot (${holdMinutes}m) & Pay")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RazorpayTestCheckoutDialog(
    booking: Booking,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onPaySuccess: (String) -> Unit,
    onSimulateFailure: (String) -> Unit
) {
    var selectedMethod by remember { mutableStateOf("UPI") }
    val ext = HRConnectThemeExtras.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Razorpay Checkout")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    color = ext.goldContainer,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, ext.gold)
                ) {
                    Text(
                        text = if (BrandConfig.isBackendConfigured) {
                            "BACKEND-VERIFIED RAZORPAY (${BrandConfig.razorpayKeyId}) — Signature & webhook verified server-side."
                        } else {
                            "SANDBOX TEST MODE (${BrandConfig.razorpayKeyId}) — Development sandbox only. Real payments require HRCONNECT_BACKEND_BASE_URL."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = ext.onGoldContainer,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Text(booking.serviceTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${booking.expertName} • ${TimeSlotUtils.formatDateDisplay(booking.dateIso)} (${TimeSlotUtils.formatMinuteRange(booking.startMinute.toInt(), booking.durationMinutes.toInt())})",
                    style = MaterialTheme.typography.bodySmall,
                    color = ext.mutedText
                )
                if (booking.verifiedCustomerGoogleEmail.isNotBlank()) {
                    Text(
                        "Verified Meet Google Account: ${booking.verifiedCustomerGoogleEmail}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider()

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Consultation Fee (INR)", style = MaterialTheme.typography.bodyMedium)
                    Text(CurrencyUtils.formatInrFromPaise(booking.amountPaise), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Split: ${CurrencyUtils.formatCommissionPercent(booking.commissionRateBps)} platform fee (${CurrencyUtils.formatInrFromPaise(booking.commissionPaise)}), Expert Net: ${CurrencyUtils.formatInrFromPaise(booking.expertEarningPaise)}, Est. Gateway Fee: ${CurrencyUtils.formatInrFromPaise(booking.gatewayFeePaise)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.mutedText
                )

                Text("Payment Method", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("UPI", "Card", "NetBanking", "Wallet").forEach { method ->
                        FilterChip(
                            selected = selectedMethod == method,
                            onClick = { selectedMethod = method },
                            label = { Text(method) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPaySuccess(selectedMethod) },
                enabled = !isBusy,
                modifier = Modifier.testTag("complete_test_payment_button")
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Verify & Complete Test Payment")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = { onSimulateFailure(selectedMethod) },
                    enabled = !isBusy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Test Failure")
                }
                TextButton(onClick = onDismiss, enabled = !isBusy) {
                    Text("Close")
                }
            }
        }
    )
}
