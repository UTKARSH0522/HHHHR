package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.BrandConfig
import com.example.core.OperationType
import com.example.core.handleFirestoreError
import com.example.data.model.AvailabilityException
import com.example.data.model.AvailabilityRule
import com.example.data.model.Booking
import com.example.data.model.ConsultationService
import com.example.data.model.ExpertProfile
import com.example.data.model.LedgerEntry
import com.example.data.model.PayoutRequest
import com.example.data.model.PayoutStatus
import com.example.data.model.UserRole
import com.example.data.model.VerificationStatus
import com.example.data.remote.BackendCallResult
import com.example.data.remote.BackendPayoutOnboardingResponse
import com.example.data.remote.MarketplaceBackendClient
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class ExpertRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val backendClient: MarketplaceBackendClient = MarketplaceBackendClient(auth)
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google.")

    fun observeMyExpertProfile(uid: String): Flow<ExpertProfile?> =
        db.collection("experts")
            .whereEqualTo("expertUid", uid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ExpertProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).firstOrNull()
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.LIST, "experts", uid)
                throw error
            }

    fun observeMyServices(uid: String): Flow<List<ConsultationService>> =
        db.collection("services")
            .whereEqualTo("expertUid", uid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ConsultationService::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.LIST, "services", uid)
                throw error
            }

    fun observeMyIncomingBookings(uid: String): Flow<List<Booking>> =
        db.collection("bookings")
            .whereEqualTo("expertUid", uid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    Booking::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.LIST, "bookings", uid)
                throw error
            }

    fun observeMyLedgerEntries(uid: String): Flow<List<LedgerEntry>> =
        db.collection("ledger_entries")
            .whereEqualTo("expertUid", uid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    LedgerEntry::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.LIST, "ledger_entries", uid)
                throw error
            }

    fun observeMyPayoutRequests(uid: String): Flow<List<PayoutRequest>> =
        db.collection("payout_requests")
            .whereEqualTo("expertUid", uid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    PayoutRequest::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.LIST, "payout_requests", uid)
                throw error
            }

    suspend fun submitExpertApplication(
        existingExpertId: String?,
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
    ): Result<Unit> {
        val docId = existingExpertId ?: "exp_${auth.currentUser?.uid ?: "unknown"}"
        val path = "experts/$docId"
        return try {
            val currentUser = auth.currentUser ?: throw IllegalStateException("User must be signed in with Google.")
            val uid = currentUser.uid
            val verifiedGoogleEmail = (currentUser.email ?: "").trim().lowercase().take(200)
            require(verifiedGoogleEmail.isNotBlank() && currentUser.isEmailVerified) {
                "Your signed-in Google account must have a verified email address for Google Meet consultations."
            }
            require(fullName.trim().length >= 2) { "Full name is required." }
            require(headline.trim().length >= 3) { "Professional headline is required." }
            require(designation.trim().length >= 2) { "Designation is required." }
            require(bio.trim().length >= 10) { "Professional biography must be at least 10 characters." }
            require(yearsExperience in 0L..60L) { "Years of experience must be between 0 and 60." }

            val cleanSpecs = (listOf(primarySpecialisation) + specialisations)
                .map { it.trim() }.filter { it.isNotBlank() }.distinct().take(10)
            val cleanSkills = skills.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(15)
            val docRef = db.collection("experts").document(docId)
            val existingSnap = docRef.get().await()

            if (!existingSnap.exists()) {
                val referralCode = (fullName.trim().take(4).uppercase().replace(Regex("[^A-Z]"), "HR") +
                    uid.takeLast(4).uppercase()).padEnd(6, 'X')
                val profile = ExpertProfile(
                    expertUid = uid,
                    fullName = fullName.trim().take(120),
                    verifiedGoogleEmail = verifiedGoogleEmail,
                    googleEmailVerified = true,
                    headline = headline.trim().take(180),
                    designation = designation.trim().take(120),
                    organization = organization.trim().take(120).ifBlank { null },
                    yearsExperience = yearsExperience,
                    bio = bio.trim().take(3000),
                    primarySpecialisation = primarySpecialisation,
                    specialisations = cleanSpecs,
                    skills = cleanSkills,
                    industryExperience = industryExperience.trim().take(300).ifBlank { null },
                    linkedInUrl = linkedInUrl.trim().take(400).ifBlank { null },
                    introVideoUrl = introVideoUrl.trim().take(400).ifBlank { null },
                    verificationNotes = verificationNotes.trim().take(1000).ifBlank { null },
                    verificationStatus = VerificationStatus.PENDING.name,
                    accountStatus = "ACTIVE",
                    demoProfile = false,
                    startingPriceInr = 0L,
                    referralCode = referralCode,
                    timezone = BrandConfig.DEFAULT_TIMEZONE
                )
                docRef.set(profile.toCreateMap()).await()
                db.collection("users").document(uid).update(
                    mapOf("role" to UserRole.EXPERT.name, "updatedAt" to FieldValue.serverTimestamp())
                ).await()
            } else {
                val currentStatus = existingSnap.getString("verificationStatus") ?: VerificationStatus.PENDING.name
                val updates = mutableMapOf<String, Any>(
                    "fullName" to fullName.trim().take(120),
                    "verifiedGoogleEmail" to verifiedGoogleEmail,
                    "googleEmailVerified" to true,
                    "headline" to headline.trim().take(180),
                    "designation" to designation.trim().take(120),
                    "yearsExperience" to yearsExperience,
                    "bio" to bio.trim().take(3000),
                    "primarySpecialisation" to primarySpecialisation,
                    "specialisations" to cleanSpecs,
                    "skills" to cleanSkills,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (organization.isNotBlank()) updates["organization"] = organization.trim().take(120)
                if (industryExperience.isNotBlank()) updates["industryExperience"] = industryExperience.trim().take(300)
                if (linkedInUrl.isNotBlank()) updates["linkedInUrl"] = linkedInUrl.trim().take(400)
                if (introVideoUrl.isNotBlank()) updates["introVideoUrl"] = introVideoUrl.trim().take(400)
                if (verificationNotes.isNotBlank()) updates["verificationNotes"] = verificationNotes.trim().take(1000)
                if (currentStatus == VerificationStatus.REJECTED.name) {
                    updates["verificationStatus"] = VerificationStatus.PENDING.name
                }
                docRef.update(updates).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun saveConsultationService(
        expert: ExpertProfile,
        existingServiceId: String?,
        title: String,
        description: String,
        specialisation: String,
        durationMinutes: Long,
        priceInr: Long,
        consultationFormat: String,
        preparationInstructions: String,
        active: Boolean
    ): Result<Unit> {
        val docRef = if (existingServiceId.isNullOrBlank()) {
            db.collection("services").document()
        } else {
            db.collection("services").document(existingServiceId)
        }
        return try {
            val uid = requireUserId()
            check(expert.expertUid == uid) { "Cannot manage services for another expert." }
            check(expert.verificationStatus == VerificationStatus.APPROVED.name) {
                "Only approved experts can publish consultation services."
            }
            require(title.trim().length >= 3) { "Service title must be at least 3 characters." }
            require(description.trim().length >= 10) { "Description must be at least 10 characters." }
            require(durationMinutes in BrandConfig.supportedDurationsMinutes) { "Unsupported duration." }
            require(priceInr in 100L..250000L) { "Price must be between ₹100 and ₹2,50,000." }

            if (existingServiceId.isNullOrBlank()) {
                val service = ConsultationService(
                    expertId = expert.id,
                    expertUid = uid,
                    expertName = expert.fullName,
                    title = title.trim().take(160),
                    description = description.trim().take(2000),
                    specialisation = specialisation,
                    durationMinutes = durationMinutes,
                    priceInr = priceInr,
                    pricePaise = priceInr * 100L,
                    consultationFormat = consultationFormat,
                    preparationInstructions = preparationInstructions.trim().take(1000).ifBlank { null },
                    active = active,
                    demoService = false
                )
                docRef.set(service.toCreateMap()).await()
            } else {
                val updates = mutableMapOf<String, Any>(
                    "expertName" to expert.fullName,
                    "title" to title.trim().take(160),
                    "description" to description.trim().take(2000),
                    "specialisation" to specialisation,
                    "durationMinutes" to durationMinutes,
                    "priceInr" to priceInr,
                    "pricePaise" to (priceInr * 100L),
                    "consultationFormat" to consultationFormat,
                    "active" to active,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (preparationInstructions.isNotBlank()) {
                    updates["preparationInstructions"] = preparationInstructions.trim().take(1000)
                }
                docRef.update(updates).await()
            }

            // Refresh startingPriceInr on ExpertProfile from active services
            val activePrices = db.collection("services")
                .whereEqualTo("expertUid", uid)
                .whereEqualTo("active", true)
                .get()
                .await()
                .documents
                .mapNotNull { it.getLong("priceInr") }
            val minPrice = activePrices.minOrNull() ?: 0L
            db.collection("experts").document(expert.id).update(
                mapOf("startingPriceInr" to minPrice, "updatedAt" to FieldValue.serverTimestamp())
            ).await()

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun saveAvailabilityRule(
        expertId: String,
        dayOfWeek: Long,
        enabled: Boolean,
        startMinute: Long,
        endMinute: Long,
        breakStartMinute: Long,
        breakEndMinute: Long
    ): Result<Unit> {
        val docId = "${expertId}_day_${dayOfWeek}"
        val docRef = db.collection("availability_rules").document(docId)
        return try {
            val uid = requireUserId()
            require(dayOfWeek in 1L..7L)
            require(startMinute in 0L..1439L && endMinute > startMinute && endMinute <= 1440L) {
                "End time must be after start time."
            }
            val rule = AvailabilityRule(
                expertId = expertId,
                expertUid = uid,
                dayOfWeek = dayOfWeek,
                enabled = enabled,
                startMinute = startMinute,
                endMinute = endMinute,
                breakStartMinute = breakStartMinute,
                breakEndMinute = breakEndMinute,
                timezone = BrandConfig.DEFAULT_TIMEZONE
            )
            docRef.set(rule.toWriteMap()).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun addAvailabilityException(
        expertId: String,
        dateIso: String,
        reason: String
    ): Result<Unit> {
        val docId = "${expertId}_ex_${dateIso}"
        val docRef = db.collection("availability_exceptions").document(docId)
        return try {
            val uid = requireUserId()
            require(dateIso.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) { "Use YYYY-MM-DD date format." }
            val ex = AvailabilityException(
                expertId = expertId,
                expertUid = uid,
                dateIso = dateIso,
                unavailable = true,
                reason = reason.trim().ifBlank { "Unavailable" }.take(200)
            )
            docRef.set(ex.toWriteMap()).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteAvailabilityException(exceptionId: String): Result<Unit> {
        val path = "availability_exceptions/$exceptionId"
        return try {
            requireUserId()
            db.collection("availability_exceptions").document(exceptionId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun requestPayout(
        expert: ExpertProfile,
        amountInr: Long,
        payoutDestination: String,
        availableBalancePaise: Long,
        eligibleBookingIds: List<String>
    ): Result<Unit> {
        val docRef = db.collection("payout_requests").document()
        return try {
            val uid = requireUserId()
            check(expert.expertUid == uid)
            val amountPaise = amountInr * 100L
            require(amountPaise >= 10000L) { "Minimum payout request is ₹100." }
            require(eligibleBookingIds.isNotEmpty() && amountPaise <= availableBalancePaise) {
                "No settled/verified payout balance available. Sessions must pass attendance, completion, and 24-hour dispute hold."
            }
            require(payoutDestination.trim().length >= 4) {
                "Enter a valid UPI ID or Bank Account reference."
            }

            val existingClaims = db.collection("payout_requests")
                .whereEqualTo("expertUid", uid)
                .get()
                .await()
                .documents
                .filter {
                    val s = it.getString("status")
                    s in setOf(
                        PayoutStatus.PENDING.name,
                        PayoutStatus.APPROVED.name,
                        PayoutStatus.PROCESSING.name,
                        PayoutStatus.PAID.name
                    )
                }
                .flatMap { doc ->
                    (doc.get("eligibleBookingIds") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                }
                .toSet()

            val unclaimedIds = eligibleBookingIds.distinct().filterNot { it in existingClaims }.take(100)
            require(unclaimedIds.isNotEmpty()) {
                "All eligible bookings are already included in an active or completed payout request."
            }

            docRef.set(
                mapOf(
                    "expertId" to expert.id,
                    "expertUid" to uid,
                    "expertName" to expert.fullName,
                    "amountPaise" to amountPaise,
                    "payoutDestination" to payoutDestination.trim().take(200),
                    "eligibleBookingIds" to unclaimedIds,
                    "idempotencyKey" to "payout_${uid}_${UUID.randomUUID().toString().take(12)}",
                    "status" to PayoutStatus.PENDING.name,
                    "retryCount" to 0L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Registers expert UPI VPA or bank account payout destination exclusively via the backend API.
     * Raw account numbers/VPA secrets are persisted only in backend-private `payout_secrets` and RazorpayX.
     */
    suspend fun savePayoutOnboarding(
        expertId: String,
        payoutAccountType: String,
        vpaAddress: String = "",
        bankAccountHolderName: String = "",
        bankIfsc: String = "",
        bankAccountNumber: String = ""
    ): Result<BackendPayoutOnboardingResponse> {
        return try {
            requireUserId()
            if (!BrandConfig.isBackendConfigured) {
                return Result.failure(
                    IllegalStateException(
                        "Bank/UPI payout onboarding requires HRCONNECT_BACKEND_BASE_URL so credentials are tokenized via RazorpayX without storing secrets in client-readable Firestore."
                    )
                )
            }
            when (
                val res = backendClient.saveExpertPayoutOnboarding(
                    expertId = expertId,
                    payoutAccountType = payoutAccountType,
                    vpaAddress = vpaAddress,
                    bankAccountHolderName = bankAccountHolderName,
                    bankIfsc = bankIfsc,
                    bankAccountNumber = bankAccountNumber
                )
            ) {
                is BackendCallResult.Success -> Result.success(res.data)
                is BackendCallResult.Error -> Result.failure(IllegalStateException(res.message))
                is BackendCallResult.NotConfigured -> Result.failure(IllegalStateException("Backend unavailable"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun rethrowIfPureCancellation(
        error: Throwable,
        op: OperationType,
        path: String,
        uid: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, op, path)
        }
    }
}
