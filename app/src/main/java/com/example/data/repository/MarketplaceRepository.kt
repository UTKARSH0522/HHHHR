package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.BrandConfig
import com.example.core.OperationType
import com.example.core.TimeSlotUtils
import com.example.core.handleFirestoreError
import com.example.data.model.AvailabilityException
import com.example.data.model.AvailabilityRule
import com.example.data.model.ConsultationService
import com.example.data.model.ExpertProfile
import com.example.data.model.ExpertRatingStats
import com.example.data.model.ModerationStatus
import com.example.data.model.PlatformSettings
import com.example.data.model.Review
import com.example.data.model.SlotLock
import com.example.data.model.Specialisation
import com.example.data.model.TimeSlotOption
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

class MarketplaceRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")

    fun observeSpecialisations(): Flow<List<Specialisation>> {
        val uid = requireUserId()
        return db.collection("specialisations")
            .snapshots()
            .map { snapshot ->
                val list = snapshot.toObjects(
                    Specialisation::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).filter { it.active }.sortedBy { it.sortOrder }
                if (list.isNotEmpty()) {
                    list
                } else {
                    BrandConfig.defaultSpecialisations.mapIndexed { idx, (name, desc) ->
                        Specialisation(
                            id = "default_spec_${idx + 1}",
                            name = name,
                            description = desc,
                            sortOrder = idx.toLong(),
                            active = true
                        )
                    }
                }
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.LIST, "specialisations", uid)
                throw error
            }
    }

    fun observeApprovedExperts(): Flow<List<ExpertProfile>> {
        val uid = requireUserId()
        return db.collection("experts")
            .whereEqualTo("verificationStatus", "APPROVED")
            .whereEqualTo("accountStatus", "ACTIVE")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ExpertProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.LIST, "experts", uid)
                throw error
            }
    }

    fun observeActiveServices(): Flow<List<ConsultationService>> {
        val uid = requireUserId()
        return db.collection("services")
            .whereEqualTo("active", true)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ConsultationService::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedBy { it.pricePaise }
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.LIST, "services", uid)
                throw error
            }
    }

    fun observeVisibleReviews(): Flow<List<Review>> {
        val uid = requireUserId()
        return db.collection("reviews")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    Review::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).filter { it.moderationStatus == ModerationStatus.VISIBLE.name }
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.LIST, "reviews", uid)
                throw error
            }
    }

    fun observePlatformSettings(): Flow<PlatformSettings> {
        val uid = requireUserId()
        return db.collection("platform_settings").document("config")
            .snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        PlatformSettings::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ) ?: PlatformSettings()
                } else {
                    PlatformSettings()
                }
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.GET, "platform_settings/config", uid)
                throw error
            }
    }

    fun observeAvailabilityRulesForExpert(expertId: String): Flow<List<AvailabilityRule>> {
        val uid = requireUserId()
        return db.collection("availability_rules")
            .whereEqualTo("expertId", expertId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    AvailabilityRule::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedBy { it.dayOfWeek }
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.LIST, "availability_rules", uid)
                throw error
            }
    }

    fun observeAvailabilityExceptionsForExpert(expertId: String): Flow<List<AvailabilityException>> {
        val uid = requireUserId()
        return db.collection("availability_exceptions")
            .whereEqualTo("expertId", expertId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    AvailabilityException::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.LIST, "availability_exceptions", uid)
                throw error
            }
    }

    fun observeSlotLocksForExpert(expertId: String): Flow<List<SlotLock>> {
        val uid = requireUserId()
        return db.collection("slot_locks")
            .whereEqualTo("expertId", expertId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    SlotLock::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.LIST, "slot_locks", uid)
                throw error
            }
    }

    suspend fun toggleSaveExpert(expertId: String, currentSaved: List<String>): Result<Unit> {
        var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}"
        return try {
            val uid = requireUserId()
            require(expertId.isNotBlank()) { "Expert ID cannot be blank" }
            val docRef = db.collection("users").document(uid)
            path = docRef.path
            val updated = if (currentSaved.contains(expertId)) {
                currentSaved.filterNot { it == expertId }
            } else {
                (currentSaved + expertId).take(50)
            }
            docRef.update(
                mapOf(
                    "savedExpertIds" to updated,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
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

    companion object {
        fun computeRatingStats(reviews: List<Review>): ExpertRatingStats {
            if (reviews.isEmpty()) {
                return ExpertRatingStats(0.0, 0, emptyMap())
            }
            val avg = reviews.map { it.rating }.average()
            val dist = (1..5).associateWith { star -> reviews.count { it.rating.toInt() == star } }
            return ExpertRatingStats(averageRating = avg, reviewCount = reviews.size, distribution = dist)
        }

        fun computeAvailableSlots(
            dateIso: String,
            durationMinutes: Int,
            rules: List<AvailabilityRule>,
            exceptions: List<AvailabilityException>,
            slotLocks: List<SlotLock>,
            timezone: String = BrandConfig.DEFAULT_TIMEZONE,
            nowMs: Long = System.currentTimeMillis()
        ): List<TimeSlotOption> {
            val isExceptionBlocked = exceptions.any { it.dateIso == dateIso && it.unavailable }
            if (isExceptionBlocked) return emptyList()

            val dayOfWeek = TimeSlotUtils.isoDayOfWeek(dateIso).toLong()
            val rule = rules.firstOrNull { it.dayOfWeek == dayOfWeek && it.enabled } ?: return emptyList()

            val activeLocks = slotLocks.filter { it.dateIso == dateIso && it.isBlocking(nowMs) }
            val step = 30
            val slots = mutableListOf<TimeSlotOption>()
            var cursor = rule.startMinute.toInt()
            val endLimit = rule.endMinute.toInt()

            while (cursor + durationMinutes <= endLimit) {
                val slotEnd = cursor + durationMinutes
                val overlapsBreak = rule.breakStartMinute >= 0 && rule.breakEndMinute > rule.breakStartMinute &&
                    cursor < rule.breakEndMinute.toInt() && slotEnd > rule.breakStartMinute.toInt()
                val overlapsLock = activeLocks.any { lock ->
                    cursor < lock.endMinute.toInt() && slotEnd > lock.startMinute.toInt()
                }
                val isFuture = TimeSlotUtils.isFutureSlot(dateIso, cursor, timezone, nowMs)

                if (!overlapsBreak && !overlapsLock && isFuture) {
                    slots.add(
                        TimeSlotOption(
                            dateIso = dateIso,
                            startMinute = cursor,
                            endMinute = slotEnd,
                            label = TimeSlotUtils.formatMinuteRange(cursor, durationMinutes)
                        )
                    )
                }
                cursor += step
            }
            return slots
        }
    }
}
