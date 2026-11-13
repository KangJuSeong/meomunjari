package com.example.meomunjari.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import com.example.meomunjari.ui.components.SectionCard
import com.example.meomunjari.ui.components.SegmentedPicker
import com.example.meomunjari.ui.components.ThinDivider
import com.example.meomunjari.ui.components.Toggle
import com.example.meomunjari.ui.theme.MColors
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.meomunjari.core.model.CarProfile
import com.example.meomunjari.di.AppContainer
import com.example.meomunjari.domain.SortOrder
import com.example.meomunjari.domain.SpotHit
import com.example.meomunjari.domain.SpotQuery
import com.example.meomunjari.domain.SpotSearch
import com.example.meomunjari.ui.LocalAppContainer
import com.example.meomunjari.ui.components.ChipFlow
import com.example.meomunjari.ui.components.SpotRow
import com.example.meomunjari.ui.components.TagChip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class SearchUi(
    val query: SpotQuery = SpotQuery(),
    val input: String = "",
    val suggestions: List<String> = emptyList(),
    val popular: List<String> = emptyList(),
    val categories: Map<String, List<String>> = emptyMap(),
    val results: List<SpotHit> = emptyList(),
    val excludedBySize: Int = 0,
    val car: CarProfile? = null,
)

class SearchViewModel(private val c: AppContainer, initialTags: List<String>) : ViewModel() {
    private val query = MutableStateFlow(SpotQuery(tags = initialTags.map(c.tags::normalize).toSet(), fitMyCar = true, maxDistanceKm = HomeViewModel.NEARBY_KM))
    private val input = MutableStateFlow("")

    val ui: StateFlow<SearchUi> = combine(c.repos.spots.spots, c.trip.location, c.repos.user.user, query, input) { spots, loc, user, q, text ->
        val results = SpotSearch.search(spots, q, loc, user.car.vehicleClass)
        val withoutFit = if (q.fitMyCar) SpotSearch.search(spots, q.copy(fitMyCar = false), loc, user.car.vehicleClass).size else results.size
        SearchUi(
            query = q,
            input = text,
            suggestions = c.tags.suggest(text, spots).filterNot { it in q.tags },
            popular = c.tags.popular(spots),
            categories = c.tags.categories(),
            results = results,
            excludedBySize = withoutFit - results.size,
            car = user.car,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUi())

    fun setInput(text: String) {
        input.value = text
    }

    fun toggleTag(raw: String) {
        val tag = c.tags.normalize(raw)
        if (tag.length < 2) return
        query.update { q -> q.copy(tags = if (tag in q.tags) q.tags - tag else q.tags + tag) }
        input.value = ""
    }

    fun setSort(sort: SortOrder) = query.update { it.copy(sort = sort) }

    fun setFit(fit: Boolean) = query.update { it.copy(fitMyCar = fit) }
}

@Composable
fun SearchScreen(initialTags: List<String>, onSpot: (String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: SearchViewModel = viewModel(key = "search-${initialTags.joinToString()}") { SearchViewModel(container, initialTags) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("어떤 밤을 보내고 싶나요?", style = MaterialTheme.typography.headlineLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = ui.input,
                    onValueChange = vm::setInput,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("🔍  별, 바다, 강아지…", color = MColors.Grey500) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MColors.Grey100,
                        unfocusedContainerColor = MColors.Card,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.toggleTag(ui.input) }),
                )
            }
            if (ui.suggestions.isNotEmpty()) {
                ChipFlow { ui.suggestions.forEach { TagChip(it, selected = false, onClick = { vm.toggleTag(it) }) } }
            }

            if (ui.query.tags.isNotEmpty()) {
                SectionCard(title = "선택한 테마") {
                    ChipFlow {
                        ui.query.tags.forEach { tag -> TagChip("$tag ✕", selected = true, strong = true, onClick = { vm.toggleTag(tag) }) }
                    }
                    Text("모두 만족하는 곳만 보여드려요", style = MaterialTheme.typography.bodyMedium, color = MColors.Grey500)
                }
            }

            SectionCard(title = "많이 찾는 테마") {
                ChipFlow { ui.popular.forEach { TagChip(it, it in ui.query.tags, onClick = { vm.toggleTag(it) }) } }
            }

            ui.categories.forEach { (category, tags) ->
                SectionCard(title = category) {
                    ChipFlow { tags.forEach { TagChip(it, it in ui.query.tags, onClick = { vm.toggleTag(it) }) } }
                }
            }
            Spacer(Modifier.height(96.dp))
        }

        Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("내 차가 들어갈 수 있는 곳만", style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append("${ui.car?.vehicleClass?.label ?: ""} 기준")
                                if (ui.query.fitMyCar && ui.excludedBySize > 0) append(" · 진입이 어려운 ${ui.excludedBySize}곳을 뺐어요")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MColors.Grey500,
                        )
                    }
                    Toggle(checked = ui.query.fitMyCar, onCheckedChange = vm::setFit)
                }
                SegmentedPicker(SortOrder.entries, ui.query.sort, { it.label }, vm::setSort)
            }
            SectionCard(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${ui.results.size}곳", style = MaterialTheme.typography.titleLarge)
                    Text("을 찾았어요", style = MaterialTheme.typography.bodyLarge, color = MColors.Grey500)
                }
                LazyColumn {
                    itemsIndexed(ui.results, key = { _, hit -> hit.spot.id }) { i, hit ->
                        if (i > 0) ThinDivider()
                        SpotRow(hit, onClick = { onSpot(hit.spot.id) })
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}
