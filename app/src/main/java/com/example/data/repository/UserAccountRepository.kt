package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.BrandConfig
import com.example.core.OperationType
import com.example.core.handleFirestoreError
import com.example.data.model.AccountStatus
import com.example.data.model.AppNotification
import com.example.data.model.Booking
import com.example.data.model.PaymentRecord
import com.example.data.model.UserAccount
import com.example.data.model.UserPrivateInfo
import com.example.data.model.UserRole
import com.example.data.remote.BackendCallResult
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
import java.security.MessageDigest

class UserAccountRepository(
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

    suspend fun ensureUserAccount(uid: String, displayName: String, email: String): Result<Unit> {
        val userRef = db.collection("users").document(uid)
        val privateRef = userRef.collection("private").document("info")
        return try {
            val currentUser = auth.currentUser ?: throw IllegalStateException("User must be signed in with Google.")
            check(currentUser.uid == uid)
            val verifiedEmail = (currentUser.email ?: email).trim().lowercase().take(200)
            val isVerified = currentUser.isEmailVerified
            val snap = userRef.get().await()
            if (!snap.exists()) {
                val account = UserAccount(
                    userId = uid,
                    displayName = displayName.ifBlank { "HRConnect Member" }.take(100),
                    verifiedGoogleEmail = verifiedEmail,
                    emailVerified = isVerified,
                    role = UserRole.CUSTOMER.name,
                    accountStatus = AccountStatus.ACTIVE.name,
                    savedExpertIds = emptyList()
                )
                userRef.set(account.toCreateMap()).await()
            } else {
                val existingVerified = snap.getString("verifiedGoogleEmail").orEmpty()
                if (existingVerified != verifiedEmail || snap.getBoolean("emailVerified") != isVerified) {
                    userRef.update(
                        mapOf(
                            "verifiedGoogleEmail" to verifiedEmail,
                            "emailVerified" to isVerified,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }
            val privSnap = privateRef.get().await()
            if (!privSnap.exists()) {
                val pii = UserPrivateInfo(
                    userId = uid,
                    email = verifiedEmail,
                    phone = "",
                    city = "",
                    notifyBookings = true,
                    notifyPayments = true,
                    notifyReviews = true
                )
                privateRef.set(pii.toWriteMap()).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userRef.path)
            Result.failure(e)
        }
    }

    fun observeUserAccount(uid: String): Flow<UserAccount?> =
        db.collection("users").document(uid)
            .snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        UserAccount::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.GET, "users/$uid", uid)
                throw error
            }

    fun observeUserPrivateInfo(uid: String): Flow<UserPrivateInfo?> =
        db.collection("users").document(uid).collection("private").document("info")
            .snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        UserPrivateInfo::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.GET, "users/$uid/private/info", uid)
                throw error
            }

    fun observeCustomerBookings(uid: String): Flow<List<Booking>> =
        db.collection("bookings")
            .whereEqualTo("customerUid", uid)
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

    fun observeCustomerPayments(uid: String): Flow<List<PaymentRecord>> =
        db.collection("payments")
            .whereEqualTo("customerUid", uid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    PaymentRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.LIST, "payments", uid)
                throw error
            }

    fun observeNotifications(uid: String): Flow<List<AppNotification>> =
        db.collection("notifications")
            .whereEqualTo("recipientUid", uid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    AppNotification::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                rethrowIfPureCancellation(error, OperationType.LIST, "notifications", uid)
                throw error
            }

    suspend fun updateProfileAndPreferences(
        displayName: String,
        email: String,
        phone: String,
        city: String,
        notifyBookings: Boolean,
        notifyPayments: Boolean,
        notifyReviews: Boolean
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        val userRef = db.collection("users").document(uid)
        val privRef = userRef.collection("private").document("info")
        return try {
            val verifiedUid = requireUserId()
            val authEmail = (auth.currentUser?.email ?: "").trim().lowercase()
            val cleanContactEmail = email.trim().lowercase().take(200)
            require(displayName.trim().isNotBlank()) { "Display name cannot be blank." }
            require(cleanContactEmail.contains("@") || cleanContactEmail.isBlank()) {
                "Please enter a valid email address."
            }

            val prevPriv = db.collection("users").document(verifiedUid).collection("private").document("info").get().await()
            val prevEmail = prevPriv.getString("email").orEmpty()

            val batch = db.batch()
            batch.update(
                db.collection("users").document(verifiedUid),
                mapOf(
                    "displayName" to displayName.trim().take(100),
                    "verifiedGoogleEmail" to authEmail.take(200),
                    "emailVerified" to (auth.currentUser?.isEmailVerified == true),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                db.collection("users").document(verifiedUid).collection("private").document("info"),
                mapOf(
                    "email" to cleanContactEmail.ifBlank { authEmail }.take(200),
                    "phone" to phone.trim().take(30),
                    "city" to city.trim().take(100),
                    "notifyBookings" to notifyBookings,
                    "notifyPayments" to notifyPayments,
                    "notifyReviews" to notifyReviews,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (prevEmail != cleanContactEmail && cleanContactEmail.isNotBlank()) {
                batch.set(
                    db.collection("email_audit_logs").document(),
                    mapOf(
                        "userId" to verifiedUid,
                        "previousEmail" to prevEmail.take(200),
                        "newContactEmail" to cleanContactEmail,
                        "verifiedGoogleEmail" to authEmail.take(200),
                        "reverificationRequired" to (cleanContactEmail != authEmail),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, privRef.path)
            Result.failure(e)
        }
    }

    suspend fun markNotificationRead(notificationId: String): Result<Unit> {
        val path = "notifications/$notificationId"
        return try {
            requireUserId()
            db.collection("notifications").document(notificationId).update(mapOf("read" to true)).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun syncFcmToken(token: String, deviceLabel: String = "android"): Result<Unit> {
        val cleanToken = token.trim()
        if (cleanToken.length < 20) {
            return Result.failure(IllegalArgumentException("Invalid FCM registration token"))
        }
        return try {
            val uid = requireUserId()
            if (BrandConfig.isBackendConfigured) {
                return when (val res = backendClient.registerFcmToken(cleanToken, deviceLabel)) {
                    is BackendCallResult.Success -> Result.success(Unit)
                    is BackendCallResult.Error -> Result.failure(IllegalStateException(res.message))
                    is BackendCallResult.NotConfigured -> Result.failure(IllegalStateException("Backend unavailable"))
                }
            }
            val tokenId = MessageDigest.getInstance("SHA-256")
                .digest(cleanToken.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(32)
            db.collection("users").document(uid)
                .collection("fcm_tokens").document(tokenId)
                .set(
                    mapOf(
                        "userId" to uid,
                        "token" to cleanToken,
                        "platform" to "android",
                        "deviceId" to deviceLabel.take(64),
                        "enabled" to true,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "users/fcm_tokens")
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
