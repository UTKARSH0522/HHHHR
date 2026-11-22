package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.BrandConfig
import com.example.core.CurrencyUtils
import com.example.core.UiState
import com.example.data.model.ConsultationService
import com.example.data.model.ExpertProfile
import com.example.data.model.ExpertRatingStats
import com.example.data.model.Review
import com.example.data.model.Specialisation
import com.example.ui.HRConnectViewModel
import com.example.ui.components.DemoBadge
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.ExpertAvatar
import com.example.ui.components.MetricStatCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.VerifiedBadge
import com.example.ui.theme.HRConnectThemeExtras
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeMarketplaceScreen(
    viewModel: HRConnectViewModel,
    onOpenExpert: (String) -> Unit,
    onNavigateBecomeExpert: () -> Unit,
    onOpenPolicies: () -> Unit
) {
    val expertsState by viewModel.approvedExpertsState.collectAsStateWithLifecycle()
    val servicesState by viewModel.activeServicesState.collectAsStateWithLifecycle()
    val specsState by viewModel.specialisationsState.collectAsStateWithLifecycle()
    val reviewsState by viewModel.visibleReviewsState.collectAsStateWithLifecycle()
    val userAccount by viewModel.userAccount.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val settings by viewModel.platformSettings.collectAsStateWithLifecycle()

    var showFilterSheet by remember { mutableStateOf(false) }

    val experts = (expertsState as? UiState.Success)?.data ?: emptyList()
    val services = (servicesState as? UiState.Success)?.data ?: emptyList()
    val specs = (specsState as? UiState.Success)?.data ?: emptyList()
    val reviews = (reviewsState as? UiState.Success)?.data ?: emptyList()
    val savedIds = userAccount?.savedExpertIds ?: emptyList()

    val filteredExperts = remember(experts, services, reviews, filters, savedIds) {
        val q = filters.query.trim().lowercase()
        experts.filter { expert ->
            val expertServices = services.filter { it.expertId == expert.id }
            val stats = viewModel.ratingStatsForExpert(expert.id, reviews)
            val matchesQuery = q.isBlank() ||
                expert.fullName.lowercase().contains(q) ||
                expert.headline.lowercase().contains(q) ||
                expert.bio.lowercase().contains(q) ||
                expert.primarySpecialisation.lowercase().contains(q) ||
                expert.specialisations.any { it.lowercase().contains(q) } ||
                expert.skills.any { it.lowercase().contains(q) } ||
                expertServices.any {
                    it.title.lowercase().contains(q) || it.description.lowercase().contains(q)
                }

            val matchesSpec = filters.selectedSpecialisation == null ||
                expert.primarySpecialisation == filters.selectedSpecialisation ||
                expert.specialisations.contains(filters.selectedSpecialisation)

            val matchesExp = expert.yearsExperience >= filters.minYearsExperience

            val startingInr = expertServices.minOfOrNull { it.priceInr } ?: expert.startingPriceInr
            val matchesPrice = filters.maxPriceInr == null || (startingInr in 1..filters.maxPriceInr!!)

            val matchesDuration = filters.durationMinutes == null ||
                expertServices.any { it.durationMinutes == filters.durationMinutes }

            val matchesRating = filters.minRating <= 0.0 ||
                (stats.reviewCount > 0 && stats.averageRating >= filters.minRating)

            val matchesSaved = !filters.savedOnly || savedIds.contains(expert.id)

            matchesQuery && matchesSpec && matchesExp && matchesPrice && matchesDuration && matchesRating && matchesSaved
        }
    }

    val ext = HRConnectThemeExtras.colors

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Editorial Dark Blue, Gold & White Hero Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = ext.navy),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, ext.gold.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "FOR HR PROFESSIONALS & SEEKERS",
                        style = MaterialTheme.typography.labelSmall,
                        color = ext.gold,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = "Your HR Expertise Deserves to Earn More.",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Discover verified HR professionals for 1:1 consultations in INR, or monetise your own HR knowledge with transparent 10–15% commission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.88f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onNavigateBecomeExpert,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ext.gold,
                                contentColor = ext.navy
                            ),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.testTag("become_expert_cta")
                        ) {
                            Text("Become an HR Expert", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onOpenPolicies,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                            shape = RoundedCornerShape(50)
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Trust & FAQ", color = Color.White)
                        }
                    }
                }
            }
        }

        // Real DB Metrics Row (No fabricated numbers!)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricStatCard(
                    label = "Verified Experts",
                    value = experts.size.toString(),
                    supporting = if (experts.isEmpty()) "Ready for onboarding" else "Live in directory",
                    modifier = Modifier.weight(1f)
                )
                MetricStatCard(
                    label = "Active Services",
                    value = services.size.toString(),
                    supporting = "15–90 min INR",
                    modifier = Modifier.weight(1f)
                )
                MetricStatCard(
                    label = "Verified Reviews",
                    value = reviews.size.toString(),
                    supporting = "${settings.platformCommissionBps / 100}% std fee",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Search & Filter Bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = filters.query,
                    onValueChange = { q -> viewModel.updateFilters { it.copy(query = q) } },
                    placeholder = { Text("Search name, skill, POSH, HRBP, CTC...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (filters.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateFilters { it.copy(query = "") } }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("marketplace_search_input")
                )
                Surface(
                    onClick = { showFilterSheet = true },
                    color = if (filters.minYearsExperience > 0 || filters.maxPriceInr != null || filters.durationMinutes != null || filters.minRating > 0.0 || filters.savedOnly) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, ext.cardBorder),
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("open_filters_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.FilterList,
                            contentDescription = "Filter experts",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Specialisation Horizontal Filter Chips
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filters.selectedSpecialisation == null,
                    onClick = { viewModel.updateFilters { it.copy(selectedSpecialisation = null) } },
                    label = { Text("All Domains") }
                )
                specs.forEach { spec ->
                    FilterChip(
                        selected = filters.selectedSpecialisation == spec.name,
                        onClick = {
                            viewModel.updateFilters {
                                it.copy(
                                    selectedSpecialisation = if (it.selectedSpecialisation == spec.name) null else spec.name
                                )
                            }
                        },
                        label = { Text(spec.name) }
                    )
                }
            }
        }

        // Directory Header
        item {
            SectionHeader(
                title = "HR Experts Directory",
                subtitle = "${filteredExperts.size} matching specialist${if (filteredExperts.size == 1) "" else "s"}",
                actionLabel = if (filters != com.example.ui.MarketplaceFilters()) "Reset" else null,
                onAction = { viewModel.resetFilters() }
            )
        }

        // Loading / Error / Empty / Experts list
        when (val state = expertsState) {
            is UiState.Loading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            is UiState.Error -> {
                item {
                    EmptyStateCard(
                        icon = Icons.Outlined.Info,
                        title = "Unable to load experts",
                        message = state.message
                    )
                }
            }

            is UiState.Success -> {
                if (filteredExperts.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Outlined.PersonSearch,
                            title = if (experts.isEmpty()) "Marketplace Ready for Experts" else "No matching HR experts",
                            message = if (experts.isEmpty()) {
                                "No approved HR experts are published yet. Admins can seed demo experts from the Admin Console, or HR professionals can apply via Expert Portal."
                            } else {
                                "Try clearing your search or adjusting experience, price, or duration filters."
                            }
                        )
                    }
                } else {
                    items(filteredExperts, key = { it.id }) { expert ->
                        val expertServices = services.filter { it.expertId == expert.id }
                        val stats = viewModel.ratingStatsForExpert(expert.id, reviews)
                        val isSaved = savedIds.contains(expert.id)
                        ExpertDirectoryCard(
                            expert = expert,
                            services = expertServices,
                            ratingStats = stats,
                            isSaved = isSaved,
                            onToggleSave = { viewModel.toggleSaveExpert(expert.id) },
                            onViewProfile = { onOpenExpert(expert.id) }
                        )
                    }
                }
            }
        }

        // Popular Consultation Services Showcase (Real DB services)
        if (services.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(6.dp))
                SectionHeader(
                    title = "Active Consultation Services",
                    subtitle = "Book fixed-duration 1:1 sessions directly"
                )
            }
            items(services.take(5), key = { "svc_preview_${it.id}" }) { service ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenExpert(service.expertId) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, ext.cardBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(service.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${service.expertName} • ${service.durationMinutes} min • ${service.consultationFormat}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ext.mutedText
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = CurrencyUtils.formatInr(service.priceInr),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // FAQ & Platform Benefits Footer Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, ext.cardBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Frequently Asked Questions", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "• How are experts verified? Every HR expert application is reviewed by an administrator before public listing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ext.mutedText
                    )
                    Text(
                        "• How does commission work? Platform bookings carry ${settings.platformCommissionBps / 100}% commission; expert referrals carry ${settings.referralCommissionBps / 100}%.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ext.mutedText
                    )
                    Text(
                        "• Are earnings guaranteed? No. HRConnect provides discovery, booking, and payout infrastructure without guaranteeing consultation volume.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ext.mutedText
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Filter HR Experts", style = MaterialTheme.typography.titleLarge)

                Text("Minimum Years of Experience", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 3, 5, 10, 15).forEach { yrs ->
                        FilterChip(
                            selected = filters.minYearsExperience == yrs,
                            onClick = { viewModel.updateFilters { it.copy(minYearsExperience = yrs) } },
                            label = { Text(if (yrs == 0) "Any" else "${yrs}+ yrs") }
                        )
                    }
                }

                Text("Maximum Starting Price (INR)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "Any", 1000L to "≤ ₹1,000", 1500L to "≤ ₹1,500", 2500L to "≤ ₹2,500").forEach { (cap, label) ->
                        FilterChip(
                            selected = filters.maxPriceInr == cap,
                            onClick = { viewModel.updateFilters { it.copy(maxPriceInr = cap) } },
                            label = { Text(label) }
                        )
                    }
                }

                Text("Consultation Duration", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = filters.durationMinutes == null,
                        onClick = { viewModel.updateFilters { it.copy(durationMinutes = null) } },
                        label = { Text("Any") }
                    )
                    BrandConfig.supportedDurationsMinutes.forEach { dur ->
                        FilterChip(
                            selected = filters.durationMinutes == dur,
                            onClick = { viewModel.updateFilters { it.copy(durationMinutes = dur) } },
                            label = { Text("${dur}m") }
                        )
                    }
                }

                Text("Minimum Verified Rating", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.0 to "Any", 4.0 to "4.0+ ★", 4.5 to "4.5+ ★").forEach { (rate, label) ->
                        FilterChip(
                            selected = filters.minRating == rate,
                            onClick = { viewModel.updateFilters { it.copy(minRating = rate) } },
                            label = { Text(label) }
                        )
                    }
                }

                FilterChip(
                    selected = filters.savedOnly,
                    onClick = { viewModel.updateFilters { it.copy(savedOnly = !it.savedOnly) } },
                    label = { Text("Saved / Bookmarked Experts Only") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.resetFilters()
                            showFilterSheet = false
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Reset") }
                    Button(
                        onClick = { showFilterSheet = false },
                        modifier = Modifier.weight(1f)
                    ) { Text("Apply Filters") }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpertDirectoryCard(
    expert: ExpertProfile,
    services: List<ConsultationService>,
    ratingStats: ExpertRatingStats,
    isSaved: Boolean,
    onToggleSave: () -> Unit,
    onViewProfile: () -> Unit
) {
    val ext = HRConnectThemeExtras.colors
    val startingPrice = services.minOfOrNull { it.priceInr } ?: expert.startingPriceInr

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onViewProfile)
            .testTag("expert_card_${expert.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, ext.cardBorder),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                ExpertAvatar(fullName = expert.fullName, size = 54.dp, isDemo = expert.demoProfile)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VerifiedBadge()
                        if (expert.demoProfile) {
                            DemoBadge()
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = expert.fullName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = expert.headline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onToggleSave,
                    modifier = Modifier.testTag("bookmark_expert_${expert.id}")
                ) {
                    Icon(
                        imageVector = if (isSaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = if (isSaved) "Remove bookmark" else "Save expert",
                        tint = if (isSaved) ext.gold else ext.mutedText
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WorkOutline, contentDescription = null, modifier = Modifier.size(15.dp), tint = ext.mutedText)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${expert.yearsExperience} yrs exp", style = MaterialTheme.typography.labelMedium, color = ext.mutedText)
                }
                Text("• ${expert.primarySpecialisation}", style = MaterialTheme.typography.labelMedium, color = ext.mutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // Only display rating when verified reviews actually exist!
            if (ratingStats.reviewCount > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(16.dp), tint = ext.gold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = String.format(Locale.ENGLISH, "%.1f (%d verified review%s)", ratingStats.averageRating, ratingStats.reviewCount, if (ratingStats.reviewCount == 1) "" else "s"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = expert.bio,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (expert.skills.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    expert.skills.take(4).forEach { skill ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = skill,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Starting from", style = MaterialTheme.typography.labelSmall, color = ext.mutedText)
                    Text(
                        text = if (startingPrice > 0) CurrencyUtils.formatInr(startingPrice) else "Consultation setup pending",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                }
                Button(
                    onClick = onViewProfile,
                    modifier = Modifier.testTag("view_profile_${expert.id}")
                ) {
                    Text("View Profile & Book")
                }
            }
        }
    }
}
