package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.BrandConfig
import com.example.core.ExpertWalletBreakdown
import com.example.core.FinancialLedgerEngine
import com.example.core.UiState
import com.example.core.unwrapFirestoreMessage
import com.example.data.model.AppNotification
import com.example.data.model.AttendanceRecord
import com.example.data.model.AuditLog
import com.example.data.model.AvailabilityException
import com.example.data.model.AvailabilityRule
import com.example.data.model.Booking
import com.example.data.model.ConsultationService
import com.example.data.model.DisputeRecord
import com.example.data.model.DisputeStatus
import com.example.data.model.ExpertProfile
import com.example.data.model.ExpertRatingStats
import com.example.data.model.LedgerEntry
import com.example.data.model.PaymentRecord
import com.example.data.model.PayoutRequest
import com.example.data.model.PayoutStatus
import com.example.data.model.PlatformSettings
import com.example.data.model.Review
import com.example.data.model.SlotLock
import com.example.data.model.Specialisation
import com.example.data.model.TimeSlotOption
import com.example.data.model.UserAccount
import com.example.data.model.UserPrivateInfo
import com.example.data.repository.AdminRepository
import com.example.data.repository.BookingPaymentRepository
import com.example.data.repository.ExpertRepository
import com.example.data.repository.MarketplaceRepository
import com.example.data.repository.UserAccountRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MarketplaceFilters(
    val query: String = "",
    val selectedSpecialisation: String? = null,
    val minYearsExperience: Int = 0,
    val maxPriceInr: Long? = null,
    val minRating: Double = 0.0,
    val durationMinutes: Long? = null,
    val savedOnly: Boolean = false
)

data class PendingRazorpayLaunch(
    val bookingId: String,
    val orderId: String,
    val amountPaise: Long,
    val currency: String,
    val razorpayKeyId: String,
    val serviceTitle: String,
    val description: String,
    val prefillEmail: String,
    val prefillName: String,
    val onSuccessNavigate: (() -> Unit)? = null
)

class HRConnectViewModel(
    val currentUserId: String,
    val currentUserEmail: String,
    val currentUserDisplayName: String,
    private val marketplaceRepo: MarketplaceRepository,
    private val bookingRepo: BookingPaymentRepository,
    private val expertRepo: ExpertRepository,
    private val userRepo: UserAccountRepository,
    private val adminRepo: AdminRepository
) : ViewModel() {

    val isAdminUser: Boolean =
        currentUserEmail.equals(BrandConfig.ADMIN_BOOTSTRAP_EMAIL, ignoreCase = true)

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _filters = MutableStateFlow(MarketplaceFilters())
    val filters: StateFlow<MarketplaceFilters> = _filters.asStateFlow()

    private val _selectedExpertId = MutableStateFlow<String?>(null)
    val selectedExpertId: StateFlow<String?> = _selectedExpertId.asStateFlow()

    private val _activeBookingForCheckout = MutableStateFlow<Booking?>(null)
    val activeBookingForCheckout: StateFlow<Booking?> = _activeBookingForCheckout.asStateFlow()

    private val _pendingRazorpayLaunch = MutableStateFlow<PendingRazorpayLaunch?>(null)
    val pendingRazorpayLaunch: StateFlow<PendingRazorpayLaunch?> = _pendingRazorpayLaunch.asStateFlow()

    init {
        viewModelScope.launch {
            userRepo.ensureUserAccount(
                uid = currentUserId,
                displayName = currentUserDisplayName,
                email = currentUserEmail
            )
        }
    }

    private fun <T> Flow<T>.asUiState(tag: String, fallback: String): StateFlow<UiState<T>> =
        this.map<T, UiState<T>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, "$tag error", root)
                emit(UiState.Error(root.unwrapFirestoreMessage(fallback)))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val specialisationsState: StateFlow<UiState<List<Specialisation>>> =
        marketplaceRepo.observeSpecialisations()
            .asUiState("Specialisations", "Failed to load HR specialisations")

    val approvedExpertsState: StateFlow<UiState<List<ExpertProfile>>> =
        marketplaceRepo.observeApprovedExperts()
            .asUiState("Experts", "Failed to load HR experts")

    val activeServicesState: StateFlow<UiState<List<ConsultationService>>> =
        marketplaceRepo.observeActiveServices()
            .asUiState("Services", "Failed to load consultation services")

    val visibleReviewsState: StateFlow<UiState<List<Review>>> =
        marketplaceRepo.observeVisibleReviews()
            .asUiState("Reviews", "Failed to load reviews")

    val platformSettings: StateFlow<PlatformSettings> =
        marketplaceRepo.observePlatformSettings()
            .catch { emit(PlatformSettings()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlatformSettings())

    val userAccount: StateFlow<UserAccount?> =
        userRepo.observeUserAccount(currentUserId)
            .catch { emit(null) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val userPrivateInfo: StateFlow<UserPrivateInfo?> =
        userRepo.observeUserPrivateInfo(currentUserId)
            .catch { emit(null) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val customerBookingsState: StateFlow<UiState<List<Booking>>> =
        userRepo.observeCustomerBookings(currentUserId)
            .asUiState("CustomerBookings", "Failed to load your bookings")

    val customerPaymentsState: StateFlow<UiState<List<PaymentRecord>>> =
        userRepo.observeCustomerPayments(currentUserId)
            .asUiState("CustomerPayments", "Failed to load your payments")

    val notificationsState: StateFlow<UiState<List<AppNotification>>> =
        userRepo.observeNotifications(currentUserId)
            .asUiState("Notifications", "Failed to load notifications")

    // Expert portal flows
    val myExpertProfile: StateFlow<ExpertProfile?> =
        expertRepo.observeMyExpertProfile(currentUserId)
            .catch { emit(null) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val myExpertServices: StateFlow<List<ConsultationService>> =
        expertRepo.observeMyServices(currentUserId)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val myExpertBookings: StateFlow<List<Booking>> =
        expertRepo.observeMyIncomingBookings(currentUserId)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val myExpertLedger: StateFlow<List<LedgerEntry>> =
        expertRepo.observeMyLedgerEntries(currentUserId)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val myExpertPayouts: StateFlow<List<PayoutRequest>> =
        expertRepo.observeMyPayoutRequests(currentUserId)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val expertWalletBreakdown: StateFlow<ExpertWalletBreakdown> = combine(
        myExpertBookings,
        myExpertLedger,
        myExpertPayouts,
        platformSettings
    ) { bookings, ledger, payouts, settings ->
        FinancialLedgerEngine.computeExpertWalletBreakdown(
            bookings = bookings,
            ledger = ledger,
            payouts = payouts,
            settings = settings
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ExpertWalletBreakdown())

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedExpertRules: StateFlow<List<AvailabilityRule>> =
        _selectedExpertId.flatMapLatest { expertId ->
            if (expertId.isNullOrBlank()) flowOf(emptyList())
            else marketplaceRepo.observeAvailabilityRulesForExpert(expertId).catch { emit(emptyList()) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedExpertExceptions: StateFlow<List<AvailabilityException>> =
        _selectedExpertId.flatMapLatest { expertId ->
            if (expertId.isNullOrBlank()) flowOf(emptyList())
            else marketplaceRepo.observeAvailabilityExceptionsForExpert(expertId).catch { emit(emptyList()) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedExpertLocks: StateFlow<List<SlotLock>> =
        _selectedExpertId.flatMapLatest { expertId ->
            if (expertId.isNullOrBlank()) flowOf(emptyList())
            else marketplaceRepo.observeSlotLocksForExpert(expertId).catch { emit(emptyList()) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val myExpertRules: StateFlow<List<AvailabilityRule>> =
        myExpertProfile.flatMapLatest { exp ->
            if (exp == null) flowOf(emptyList())
            else marketplaceRepo.observeAvailabilityRulesForExpert(exp.id).catch { emit(emptyList()) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val myExpertExceptions: StateFlow<List<AvailabilityException>> =
        myExpertProfile.flatMapLatest { exp ->
            if (exp == null) flowOf(emptyList())
            else marketplaceRepo.observeAvailabilityExceptionsForExpert(exp.id).catch { emit(emptyList()) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    // Admin streams (active only when isAdminUser == true)
    val adminUsers: StateFlow<List<UserAccount>> =
        if (isAdminUser) adminRepo.observeAllUsers().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
        else MutableStateFlow(emptyList())

    val adminExperts: StateFlow<List<ExpertProfile>> =
        if (isAdminUser) adminRepo.observeAllExperts().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
        else MutableStateFlow(emptyList())

    val adminBookings: StateFlow<List<Booking>> =
        if (isAdminUser) adminRepo.observeAllBookings().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
        else MutableStateFlow(emptyList())

    val adminPayments: StateFlow<List<PaymentRecord>> =
        if (isAdminUser) adminRepo.observeAllPayments().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
        else MutableStateFlow(emptyList())

    val adminLedgerEntries: StateFlow<List<LedgerEntry>> =
        if (isAdminUser) adminRepo.observeAllLedgerEntries().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
        else MutableStateFlow(emptyList())

    val adminAttendanceRecords: StateFlow<List<AttendanceRecord>> =
        if (isAdminUser) adminRepo.observeAllAttendanceRecords().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
        else MutableStateFlow(emptyList())

    val adminDisputes: StateFlow<List<DisputeRecord>> =
        if (isAdminUser) adminRepo.observeAllDisputes().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
        else MutableStateFlow(emptyList())

    val adminPayouts: StateFlow<List<PayoutRequest>> =
        if (isAdminUser) adminRepo.observeAllPayoutRequests().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
        else MutableStateFlow(emptyList())

    val adminReviews: StateFlow<List<Review>> =
        if (isAdminUser) adminRepo.observeAllReviews().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
        else MutableStateFlow(emptyList())

    val adminSpecialisations: StateFlow<List<Specialisation>> =
        if (isAdminUser) adminRepo.observeAllSpecialisations().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
        else MutableStateFlow(emptyList())

    val adminAuditLogs: StateFlow<List<AuditLog>> =
        if (isAdminUser) adminRepo.observeAuditLogs().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
        else MutableStateFlow(emptyList())

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    fun selectExpert(expertId: String?) {
        _selectedExpertId.value = expertId
    }

    fun updateFilters(transform: (MarketplaceFilters) -> MarketplaceFilters) {
        _filters.value = transform(_filters.value)
    }

    fun resetFilters() {
        _filters.value = MarketplaceFilters()
    }

    fun setActiveCheckoutBooking(booking: Booking?) {
        _activeBookingForCheckout.value = booking
    }

    fun clearPendingRazorpayLaunch() {
        _pendingRazorpayLaunch.value = null
    }

    fun startCheckout(booking: Booking, onPaid: () -> Unit = {}) {
        if (BrandConfig.isBackendConfigured) {
            viewModelScope.launch {
                _isBusy.value = true
                try {
                    bookingRepo.createRazorpayOrderForCheckout(booking)
                        .onSuccess { order ->
                            if (order.requiresSdkCheckout && !BrandConfig.isPlaceholderRazorpayKey(order.razorpayKeyId)) {
                                _activeBookingForCheckout.value = null
                                _pendingRazorpayLaunch.value = PendingRazorpayLaunch(
                                    bookingId = booking.id,
                                    orderId = order.orderId,
                                    amountPaise = order.amountPaise,
                                    currency = order.currency,
                                    razorpayKeyId = order.razorpayKeyId,
                                    serviceTitle = booking.serviceTitle,
                                    description = "HRConnect: ${booking.serviceTitle} (${booking.expertName})",
                                    prefillEmail = currentUserEmail,
                                    prefillName = currentUserDisplayName,
                                    onSuccessNavigate = onPaid
                                )
                            } else if (BrandConfig.allowDebugSandboxFallback && order.testMode) {
                                _activeBookingForCheckout.value = booking
                            } else {
                                _actionMessage.value = "Razorpay Checkout requires valid Razorpay API keys configured on the backend."
                            }
                        }
                        .onFailure { err ->
                            _actionMessage.value = err.unwrapFirestoreMessage("Unable to initiate Razorpay checkout")
                        }
                } finally {
                    _isBusy.value = false
                }
            }
            return
        }
        if (BrandConfig.allowDebugSandboxFallback && BrandConfig.isRazorpayTestMode) {
            _activeBookingForCheckout.value = booking
        } else {
            _actionMessage.value = "Production Razorpay payments require HRCONNECT_BACKEND_BASE_URL to be configured."
        }
    }

    fun onRazorpaySdkSuccess(
        razorpayPaymentId: String?,
        paymentData: com.razorpay.PaymentData?
    ) {
        val launch = _pendingRazorpayLaunch.value ?: return
        val orderId = paymentData?.orderId?.takeIf { it.isNotBlank() } ?: launch.orderId
        val paymentId = paymentData?.paymentId?.takeIf { it.isNotBlank() } ?: razorpayPaymentId.orEmpty()
        val signature = paymentData?.signature.orEmpty()
        if (paymentId.isBlank() || signature.isBlank()) {
            _pendingRazorpayLaunch.value = null
            _actionMessage.value = "Razorpay SDK did not return a complete payment signature."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            try {
                bookingRepo.verifyRazorpaySdkPayment(
                    bookingId = launch.bookingId,
                    orderId = orderId,
                    paymentId = paymentId,
                    signature = signature
                ).onSuccess {
                    _pendingRazorpayLaunch.value = null
                    _actionMessage.value = "Payment verified by backend server! Consultation confirmed."
                    launch.onSuccessNavigate?.invoke()
                }.onFailure { err ->
                    _pendingRazorpayLaunch.value = null
                    _actionMessage.value = err.unwrapFirestoreMessage("Payment signature verification failed")
                }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun onRazorpaySdkError(code: Int, description: String?) {
        _pendingRazorpayLaunch.value = null
        val clean = description?.takeIf { it.isNotBlank() }?.take(180) ?: "Payment cancelled"
        _actionMessage.value = "Razorpay checkout did not complete ($code): $clean"
    }

    fun ratingStatsForExpert(expertId: String, reviews: List<Review>): ExpertRatingStats =
        MarketplaceRepository.computeRatingStats(reviews.filter { it.expertId == expertId })

    fun toggleSaveExpert(expertId: String) {
        val currentSaved = userAccount.value?.savedExpertIds ?: emptyList()
        viewModelScope.launch {
            marketplaceRepo.toggleSaveExpert(expertId, currentSaved).onFailure {
                _actionMessage.value = it.unwrapFirestoreMessage("Could not update saved experts")
            }
        }
    }

    fun createBookingHold(
        expertId: String,
        serviceId: String,
        dateIso: String,
        startMinute: Int,
        topic: String,
        referralCode: String,
        onBooked: (Booking) -> Unit
    ) {
        val customerName = userAccount.value?.displayName ?: currentUserDisplayName
        runBusy("Slot reserved for 10 minutes. Complete payment to confirm.") {
            bookingRepo.createBookingWithSlotHold(
                customerName = customerName,
                expertId = expertId,
                serviceId = serviceId,
                dateIso = dateIso,
                startMinute = startMinute,
                topic = topic,
                referralCodeInput = referralCode
            ).onSuccess { booking ->
                onBooked(booking)
            }
        }
    }

    fun releaseExpiredHold(booking: Booking) {
        runBusy("Expired slot hold released.") {
            bookingRepo.expireStalePaymentHold(booking)
        }
    }

    fun completeTestPayment(booking: Booking, paymentMethod: String, onPaid: () -> Unit) {
        val label = if (BrandConfig.isBackendConfigured) {
            "Payment verified by backend server! Consultation confirmed."
        } else {
            "Payment recorded in Sandbox Test Mode! Consultation confirmed."
        }
        runBusy(label) {
            bookingRepo.processPayment(booking, paymentMethod)
                .onSuccess {
                    _activeBookingForCheckout.value = null
                    onPaid()
                }
        }
    }

    fun failTestPayment(booking: Booking, paymentMethod: String) {
        runBusy("Payment failure recorded. Booking remains unconfirmed.") {
            bookingRepo.recordPaymentFailure(booking, paymentMethod)
        }
    }

    fun syncCalendarAndMeet(booking: Booking) {
        runBusy("Google Calendar & Meet synced.") {
            bookingRepo.syncGoogleCalendarAndMeet(booking, isAdmin = isAdminUser)
        }
    }

    fun syncMeetAttendance(booking: Booking) {
        runBusy("Attendance evaluation updated.") {
            bookingRepo.syncGoogleMeetAttendance(booking, isAdmin = isAdminUser)
        }
    }

    fun submitBookingCheckIn(booking: Booking, enteredPin: String) {
        runBusy("Session check-in recorded (supplementary fallback).") {
            bookingRepo.submitCheckIn(booking, enteredPin)
        }
    }

    fun rescheduleBooking(booking: Booking, newDateIso: String, newStartMinute: Int, reason: String) {
        runBusy("Consultation rescheduled & calendar synced.") {
            bookingRepo.rescheduleBooking(booking, newDateIso, newStartMinute, reason)
        }
    }

    fun cancelBooking(booking: Booking, reason: String) {
        runBusy("Consultation cancelled per policy.") {
            bookingRepo.cancelBooking(booking, reason)
        }
    }

    fun updateMeetingUrl(booking: Booking, url: String) {
        runBusy("Meeting link shared with customer.") {
            bookingRepo.updateMeetingUrl(booking, url)
        }
    }

    fun confirmBookingCompletion(booking: Booking, isExpert: Boolean) {
        runBusy("Completion confirmation recorded. Payout unlocks only after attendance & 24h dispute window pass.") {
            bookingRepo.confirmBookingCompletion(booking, isExpert)
        }
    }

    fun raiseDispute(booking: Booking, reason: String, evidence: String) {
        runBusy("Dispute submitted. Expert payout has been placed on hold for admin review.") {
            bookingRepo.raiseDispute(booking, reason, evidence)
        }
    }

    fun submitReview(booking: Booking, rating: Int, comment: String) {
        runBusy("Thank you! Your verified review has been published.") {
            bookingRepo.submitReview(booking, rating, comment)
        }
    }

    fun reportReview(reviewId: String, reason: String) {
        runBusy("Review flagged for administrator moderation.") {
            bookingRepo.reportReview(reviewId, reason)
        }
    }

    fun updateProfileAndPrefs(
        displayName: String,
        email: String,
        phone: String,
        city: String,
        notifyBookings: Boolean,
        notifyPayments: Boolean,
        notifyReviews: Boolean
    ) {
        runBusy("Profile & notification preferences saved.") {
            userRepo.updateProfileAndPreferences(
                displayName, email, phone, city,
                notifyBookings, notifyPayments, notifyReviews
            )
        }
    }

    fun markNotificationRead(id: String) {
        viewModelScope.launch {
            userRepo.markNotificationRead(id)
        }
    }

    fun submitExpertApplication(
        fullName: String,
        headline: String,
        designation: String,
        organization: String,
        yearsExperience: Long,
        bio: String,
        primarySpecialisation: String,
        specialisations: List<String>,
        skills: List<String>,
        industryExperience: String,
        linkedInUrl: String,
        introVideoUrl: String,
        verificationNotes: String
    ) {
        runBusy("HR Expert profile saved & submitted for review.") {
            expertRepo.submitExpertApplication(
                existingExpertId = myExpertProfile.value?.id,
                fullName = fullName,
                headline = headline,
                designation = designation,
                organization = organization,
                yearsExperience = yearsExperience,
                bio = bio,
                primarySpecialisation = primarySpecialisation,
                specialisations = specialisations,
                skills = skills,
                industryExperience = industryExperience,
                linkedInUrl = linkedInUrl,
                introVideoUrl = introVideoUrl,
                verificationNotes = verificationNotes
            )
        }
    }

    fun saveService(
        existingServiceId: String?,
        title: String,
        description: String,
        specialisation: String,
        durationMinutes: Long,
        priceInr: Long,
        consultationFormat: String,
        preparationInstructions: String,
        active: Boolean
    ) {
        val expert = myExpertProfile.value ?: run {
            _actionMessage.value = "Create an approved expert profile first."
            return
        }
        runBusy("Consultation service saved.") {
            expertRepo.saveConsultationService(
                expert, existingServiceId, title, description,
                specialisation, durationMinutes, priceInr,
                consultationFormat, preparationInstructions, active
            )
        }
    }

    fun saveAvailabilityRule(
        dayOfWeek: Long,
        enabled: Boolean,
        startMinute: Long,
        endMinute: Long,
        breakStartMinute: Long,
        breakEndMinute: Long
    ) {
        val expert = myExpertProfile.value ?: return
        runBusy("Weekly schedule updated.") {
            expertRepo.saveAvailabilityRule(
                expert.id, dayOfWeek, enabled,
                startMinute, endMinute, breakStartMinute, breakEndMinute
            )
        }
    }

    fun addAvailabilityException(dateIso: String, reason: String) {
        val expert = myExpertProfile.value ?: return
        runBusy("Holiday / unavailable date added.") {
            expertRepo.addAvailabilityException(expert.id, dateIso, reason)
        }
    }

    fun deleteAvailabilityException(exceptionId: String) {
        runBusy("Unavailable date removed.") {
            expertRepo.deleteAvailabilityException(exceptionId)
        }
    }

    fun requestPayout(amountInr: Long, destination: String) {
        val expert = myExpertProfile.value ?: return
        val wallet = expertWalletBreakdown.value
        runBusy("Payout request submitted for admin settlement.") {
            expertRepo.requestPayout(
                expert = expert,
                amountInr = amountInr,
                payoutDestination = destination,
                availableBalancePaise = wallet.availableForPayoutPaise,
                eligibleBookingIds = wallet.eligibleBookingIds
            )
        }
    }

    fun saveExpertPayoutOnboarding(
        payoutAccountType: String,
        vpaAddress: String = "",
        bankAccountHolderName: String = "",
        bankIfsc: String = "",
        bankAccountNumber: String = ""
    ) {
        val expert = myExpertProfile.value ?: run {
            _actionMessage.value = "Create your HR expert profile before configuring payouts."
            return
        }
        runBusy("Payout account verified and saved.") {
            expertRepo.savePayoutOnboarding(
                expertId = expert.id,
                payoutAccountType = payoutAccountType,
                vpaAddress = vpaAddress,
                bankAccountHolderName = bankAccountHolderName,
                bankIfsc = bankIfsc,
                bankAccountNumber = bankAccountNumber
            )
        }
    }

    fun syncFcmToken(token: String, deviceLabel: String = "android") {
        viewModelScope.launch {
            userRepo.syncFcmToken(token, deviceLabel)
        }
    }

    // Admin Actions
    fun adminReviewExpert(expert: ExpertProfile, approve: Boolean, reason: String = "") {
        runBusy(if (approve) "${expert.fullName} approved!" else "Application updated.") {
            adminRepo.reviewExpertApplication(expert, approve, reason)
        }
    }

    fun adminToggleExpertStatus(expert: ExpertProfile) {
        runBusy("Expert account status updated.") {
            adminRepo.toggleExpertAccountStatus(expert)
        }
    }

    fun adminToggleCustomerStatus(user: UserAccount) {
        runBusy("Customer account status updated.") {
            adminRepo.toggleCustomerAccountStatus(user)
        }
    }

    fun adminUpdateCommission(
        platformPct: Int,
        referralPct: Int,
        holdMins: Int,
        cancelHours: Int
    ) {
        runBusy("Commission & platform settings updated (historical snapshots unchanged).") {
            adminRepo.updateCommissionAndSettings(platformPct, referralPct, holdMins, cancelHours)
        }
    }

    fun adminManualAttendanceReview(
        booking: Booking,
        approveAttendance: Boolean,
        customerMinutes: Long,
        expertMinutes: Long,
        overlapMinutes: Long,
        adminNote: String
    ) {
        runBusy(if (approveAttendance) "Attendance approved by admin." else "Attendance marked failed by admin.") {
            adminRepo.adminManualAttendanceReview(
                booking, approveAttendance, customerMinutes, expertMinutes, overlapMinutes, adminNote
            )
        }
    }

    fun adminResolveDisputeAndRefund(
        booking: Booking,
        resolutionType: DisputeStatus,
        refundAmountPaise: Long,
        adminNote: String
    ) {
        runBusy("Dispute & refund resolution recorded in immutable ledger.") {
            adminRepo.adminResolveDisputeAndRefund(booking, resolutionType, refundAmountPaise, adminNote)
        }
    }

    fun adminProcessPayout(
        request: PayoutRequest,
        status: PayoutStatus,
        reference: String,
        note: String
    ) {
        runBusy("Payout status updated to ${status.name}.") {
            adminRepo.processPayoutRequest(request, status, reference, note)
        }
    }

    fun adminSaveSpecialisation(
        existingId: String?,
        name: String,
        description: String,
        sortOrder: Long,
        active: Boolean
    ) {
        runBusy("Specialisation category saved.") {
            adminRepo.saveSpecialisation(existingId, name, description, sortOrder, active)
        }
    }

    fun adminModerateReview(reviewId: String, hide: Boolean) {
        runBusy(if (hide) "Review hidden from public view." else "Review restored.") {
            adminRepo.moderateReview(reviewId, hide)
        }
    }

    fun adminSeedDemoMarketplace() {
        runBusy("Demo HR experts, services, 15 categories & settings seeded!") {
            adminRepo.seedDemoMarketplace()
        }
    }

    private fun runBusy(successMsg: String, block: suspend () -> Result<*>) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val res = block()
                res.onSuccess {
                    _actionMessage.value = successMsg
                }.onFailure { err ->
                    _actionMessage.value = err.unwrapFirestoreMessage("Operation failed")
                }
            } finally {
                _isBusy.value = false
            }
        }
    }

    private companion object {
        const val TAG = "HRConnectVM"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
