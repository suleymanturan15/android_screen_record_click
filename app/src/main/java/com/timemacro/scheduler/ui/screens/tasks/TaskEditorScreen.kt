package com.timemacro.scheduler.ui.screens.tasks

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timemacro.scheduler.AppContainer
import com.timemacro.scheduler.domain.model.Task
import com.timemacro.scheduler.viewmodel.MacroViewModel
import com.timemacro.scheduler.viewmodel.MacroViewModelFactory
import com.timemacro.scheduler.viewmodel.TaskViewModel
import com.timemacro.scheduler.viewmodel.TaskViewModelFactory
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.Locale

private val HHmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

enum class TaskEditorMode { CREATE, EDIT }

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun TaskEditorScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    mode: TaskEditorMode,
    taskId: String?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val taskVm: TaskViewModel =
        viewModel(
            factory =
                TaskViewModelFactory(
                    taskRepository = container.taskRepository,
                    macroRepository = container.macroRepository,
                    logRepository = container.logRepository,
                    schedulerEngine = container.schedulerEngine,
                    taskPlanner = container.taskPlanner,
                ),
        )
    val macroVm: MacroViewModel = viewModel(factory = MacroViewModelFactory(container.macroRepository))
    val macros by macroVm.macros.collectAsStateWithLifecycle()

    var loadedTask by remember { mutableStateOf<Task?>(null) }
    LaunchedEffect(taskId) {
        if (mode == TaskEditorMode.EDIT && taskId != null) {
            loadedTask = taskVm.loadTask(taskId)
        } else {
            loadedTask = null
        }
    }

    var selectedMacroId by remember { mutableStateOf<String?>(null) }
    var macroMenuOpen by remember { mutableStateOf(false) }

    // Default macro selection (CREATE mode): pick first enabled macro.
    LaunchedEffect(macros, mode) {
        if (mode == TaskEditorMode.CREATE && selectedMacroId == null) {
            selectedMacroId = macros.firstOrNull { it.isEnabled }?.id
        }
    }

    var startTimeText by remember { mutableStateOf("09:00") }
    var runsPerDayText by remember { mutableStateOf("1") }

    var planType by remember { mutableStateOf("DAILY") }
    var planTypeMenuOpen by remember { mutableStateOf(false) }

    var intervalMode by remember { mutableStateOf("FIXED_INTERVAL") }
    var intervalModeMenuOpen by remember { mutableStateOf(false) }

    var fixedIntervalMinutesText by remember { mutableStateOf("60") }
    var extraDelayMinutesText by remember { mutableStateOf("0") }
    var initialDelayMinutesText by remember { mutableStateOf("0") }

    var monthlyDayText by remember { mutableStateOf("1") }
    var yearlyMonth by remember { mutableStateOf(1) } // 1..12
    var yearlyMonthMenuOpen by remember { mutableStateOf(false) }
    var yearlyDayText by remember { mutableStateOf("1") }

    // Prefill for EDIT mode once loaded.
    LaunchedEffect(loadedTask) {
        val t = loadedTask ?: return@LaunchedEffect
        selectedMacroId = t.macroId
        startTimeText = t.startTime.format(HHmm)
        runsPerDayText = t.runsPerDay.toString()
        planType = t.planType
        intervalMode = t.intervalMode
        fixedIntervalMinutesText = t.fixedIntervalMinutes?.toString() ?: ""
        extraDelayMinutesText = t.extraDelayMinutes?.toString() ?: ""
        initialDelayMinutesText = t.initialDelayMinutes.toString()
        monthlyDayText = t.monthlyDay?.toString() ?: "1"
        yearlyMonth = (t.yearlyMonth ?: 1).coerceIn(1, 12)
        yearlyDayText = t.yearlyDay?.toString() ?: "1"
    }

    val selectedMacro = macros.firstOrNull { it.id == selectedMacroId }
    val canSave =
        macros.any { it.isEnabled } &&
            (selectedMacro == null || selectedMacro.isEnabled)

    val context = LocalContext.current
    val scrollState = rememberScrollState()

    fun normalizedPlanTypeLabel(type: String): String =
        when (type) {
            "DAILY" -> "Günlük"
            "MONTHLY" -> "Aylık"
            "YEARLY" -> "Yıllık"
            else -> type
        }

    fun normalizedIntervalModeLabel(type: String): String =
        when (type) {
            "FIXED_INTERVAL" -> "Sabit Aralık"
            "MACRO_BASED" -> "Makro Bazlı"
            else -> type
        }

    val parsedStartTime = runCatching { LocalTime.parse(startTimeText, HHmm) }.getOrDefault(LocalTime.MIDNIGHT)
    val parsedRunsPerDay = runsPerDayText.trim().toIntOrNull()
    val runsPerDayError =
        when {
            parsedRunsPerDay == null -> "Zorunlu"
            parsedRunsPerDay !in 1..24 -> "1 ile 24 arasında olmalı"
            else -> null
        }

    val parsedFixedInterval = fixedIntervalMinutesText.trim().toIntOrNull()
    val fixedIntervalError =
        when (intervalMode) {
            "FIXED_INTERVAL" ->
                when {
                    parsedFixedInterval == null -> "Sabit aralık dakikası zorunlu"
                    parsedFixedInterval !in 1..1440 -> "1 ile 1440 arasında olmalı"
                    else -> null
                }

            else -> null
        }

    val parsedExtraDelay = extraDelayMinutesText.trim().toIntOrNull()
    val extraDelayError =
        when (intervalMode) {
            "MACRO_BASED" ->
                when {
                    extraDelayMinutesText.trim().isEmpty() -> null // opsiyonel (boş = 0)
                    parsedExtraDelay == null -> "Sayı olmalı"
                    parsedExtraDelay !in 0..1440 -> "0 ile 1440 arasında olmalı"
                    else -> null
                }

            else -> null
        }

    val parsedMonthlyDay = monthlyDayText.trim().toIntOrNull()
    val monthlyDayError =
        when (planType) {
            "MONTHLY" ->
                when {
                    parsedMonthlyDay == null -> "Zorunlu"
                    parsedMonthlyDay !in 1..31 -> "1 ile 31 arasında olmalı"
                    else -> null
                }

            else -> null
        }

    val parsedYearlyDay = yearlyDayText.trim().toIntOrNull()
    val yearlyDayError =
        when (planType) {
            "YEARLY" ->
                when {
                    parsedYearlyDay == null -> "Zorunlu"
                    parsedYearlyDay !in 1..31 -> "1 ile 31 arasında olmalı"
                    else -> null
                }

            else -> null
        }

    val parsedInitialDelay = initialDelayMinutesText.trim().toIntOrNull()
    val initialDelay = parsedInitialDelay?.coerceAtLeast(0) ?: 0

    val selectedMacroDurationMs = selectedMacro?.recordDurationMs
    val previewTaskValid =
        runsPerDayError == null &&
            fixedIntervalError == null &&
            extraDelayError == null &&
            monthlyDayError == null &&
            yearlyDayError == null &&
            selectedMacroId != null

    val previewTask: Task? =
        if (!previewTaskValid) {
            null
        } else {
            Task(
                id = loadedTask?.id ?: "preview",
                macroId = selectedMacroId!!,
                startTime = parsedStartTime,
                runsPerDay = parsedRunsPerDay!!.coerceIn(1, 24),
                planType = planType,
                intervalMode = intervalMode,
                fixedIntervalMinutes = if (intervalMode == "FIXED_INTERVAL") parsedFixedInterval else null,
                extraDelayMinutes = if (intervalMode == "MACRO_BASED") (parsedExtraDelay ?: 0) else null,
                initialDelayMinutes = initialDelay,
                monthlyDay = if (planType == "MONTHLY") parsedMonthlyDay else null,
                yearlyMonth = if (planType == "YEARLY") yearlyMonth else null,
                yearlyDay = if (planType == "YEARLY") parsedYearlyDay else null,
                planEndsAt = loadedTask?.planEndsAt,
                nextScheduledAt = null,
                lastScheduledAt = null,
                lastRunAt = loadedTask?.lastRunAt,
                active = true,
            )
        }

    val nowMs = System.currentTimeMillis()
    val previewNextRun =
        previewTask?.let {
            container.taskPlanner.computeNextRun(
                nowEpochMs = nowMs,
                task = it,
                lastScheduledOrLastRunEpochMs = loadedTask?.lastRunAt?.toEpochMilli(),
                macroDurationMs = selectedMacroDurationMs,
            )
        }

    val canSubmit =
        canSave &&
            runsPerDayError == null &&
            fixedIntervalError == null &&
            extraDelayError == null &&
            monthlyDayError == null &&
            yearlyDayError == null

    fun submit() {
        val macroId =
            selectedMacroId
                ?: macros.firstOrNull { it.isEnabled }?.id
                ?: return
        val startTime = parsedStartTime
        val runsPerDay = parsedRunsPerDay?.coerceIn(1, 24) ?: return
        val fixedInterval =
            if (intervalMode == "FIXED_INTERVAL") {
                parsedFixedInterval?.coerceIn(1, 1440) ?: return
            } else {
                null
            }
        val extraDelay =
            if (intervalMode == "MACRO_BASED") {
                (parsedExtraDelay ?: 0).coerceIn(0, 1440)
            } else {
                null
            }

        val monthlyDay =
            if (planType == "MONTHLY") {
                (parsedMonthlyDay ?: return).coerceIn(1, 31)
            } else {
                null
            }
        val yearlyM = if (planType == "YEARLY") yearlyMonth.coerceIn(1, 12) else null
        val yearlyD =
            if (planType == "YEARLY") {
                (parsedYearlyDay ?: return).coerceIn(1, 31)
            } else {
                null
            }

        val existing = loadedTask
        val planEndsAt = existing?.planEndsAt // keep existing, do not auto-expire
        val newTask =
            if (existing == null) {
                Task(
                    id = UUID.randomUUID().toString(),
                    macroId = macroId,
                    startTime = startTime,
                    runsPerDay = runsPerDay,
                    planType = planType,
                    intervalMode = intervalMode,
                    fixedIntervalMinutes = fixedInterval,
                    extraDelayMinutes = extraDelay,
                    initialDelayMinutes = initialDelay,
                    monthlyDay = monthlyDay,
                    yearlyMonth = yearlyM,
                    yearlyDay = yearlyD,
                    planEndsAt = planEndsAt,
                    nextScheduledAt = null,
                    lastScheduledAt = null,
                    lastRunAt = null,
                    active = true,
                )
            } else {
                existing.copy(
                    macroId = macroId,
                    startTime = startTime,
                    runsPerDay = runsPerDay,
                    planType = planType,
                    intervalMode = intervalMode,
                    fixedIntervalMinutes = fixedInterval,
                    extraDelayMinutes = extraDelay,
                    initialDelayMinutes = initialDelay,
                    monthlyDay = monthlyDay,
                    yearlyMonth = yearlyM,
                    yearlyDay = yearlyD,
                    planEndsAt = planEndsAt,
                )
            }

        if (existing == null) taskVm.upsert(newTask) else taskVm.updateTask(newTask)
        onDone()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Button(onClick = onCancel) { Text("İptal") }
                Button(onClick = { submit() }, enabled = canSubmit) {
                    Text(if (mode == TaskEditorMode.CREATE) "Kaydet" else "Güncelle")
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (mode == TaskEditorMode.CREATE) "Görev Oluştur" else "Görevi Düzenle",
                    style = MaterialTheme.typography.headlineSmall,
                )

                if (mode == TaskEditorMode.EDIT && taskId != null && loadedTask == null) {
                    Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(
                        onClick = { macroMenuOpen = true },
                        enabled = macros.isNotEmpty(),
                    ) {
                        val selected = macros.firstOrNull { it.id == selectedMacroId }
                        val label =
                            selected?.let { if (it.isEnabled) it.name else "${it.name} (disabled)" }
                                ?: macros.firstOrNull { it.isEnabled }?.name
                                ?: macros.firstOrNull()?.let { if (it.isEnabled) it.name else "${it.name} (disabled)" }
                                ?: "No macros"
                        Text("Makro: $label")
                    }
                    DropdownMenu(
                        expanded = macroMenuOpen,
                        onDismissRequest = { macroMenuOpen = false },
                    ) {
                        macros.forEach { macro ->
                            DropdownMenuItem(
                                text = { Text(if (macro.isEnabled) macro.name else "${macro.name} (disabled)") },
                                onClick = {
                                    selectedMacroId = macro.id
                                    macroMenuOpen = false
                                },
                                enabled = macro.isEnabled,
                            )
                        }
                    }

                    if (macros.isEmpty()) {
                        Button(onClick = { macroVm.createPlaceholderMacro(name = "Sample Macro") }) {
                            Text("Örnek makro oluştur")
                        }
                    }
                }

                val bringStartTime = remember { BringIntoViewRequester() }
                val bringRunsPerDay = remember { BringIntoViewRequester() }
                val bringMonthlyDay = remember { BringIntoViewRequester() }
                val bringYearlyDay = remember { BringIntoViewRequester() }
                val bringFixed = remember { BringIntoViewRequester() }
                val bringExtra = remember { BringIntoViewRequester() }
                val bringInitial = remember { BringIntoViewRequester() }

                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(bringStartTime)
                        .onFocusEvent { if (it.isFocused) scope.launch { bringStartTime.bringIntoView() } },
                    value = startTimeText,
                    onValueChange = { /* read-only */ },
                    label = { Text("Başlangıç Saati (SS:DD)") },
                    singleLine = true,
                    readOnly = true,
                )

                Button(
                    onClick = {
                        val parsed = runCatching { LocalTime.parse(startTimeText, HHmm) }.getOrDefault(LocalTime.of(9, 0))
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                startTimeText = String.format("%02d:%02d", hour, minute)
                            },
                            parsed.hour,
                            parsed.minute,
                            true,
                        ).show()
                    },
                ) {
                    Text("Saat Seç")
                }

                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(bringRunsPerDay)
                        .onFocusEvent { if (it.isFocused) scope.launch { bringRunsPerDay.bringIntoView() } },
                    value = runsPerDayText,
                    onValueChange = { runsPerDayText = it },
                    label = { Text("Günde kaç kez çalışsın (1–24)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )

                if (runsPerDayError != null) {
                    Text(
                        text = "Hata: $runsPerDayError",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Plan Türü
                Text("Plan Türü", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(onClick = { planTypeMenuOpen = true }) {
                        Text(normalizedPlanTypeLabel(planType))
                    }
                    DropdownMenu(expanded = planTypeMenuOpen, onDismissRequest = { planTypeMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Günlük") },
                            onClick = {
                                planType = "DAILY"
                                planTypeMenuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Aylık") },
                            onClick = {
                                planType = "MONTHLY"
                                planTypeMenuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Yıllık") },
                            onClick = {
                                planType = "YEARLY"
                                planTypeMenuOpen = false
                            },
                        )
                    }
                }

                if (planType == "MONTHLY") {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(bringMonthlyDay)
                            .onFocusEvent { if (it.isFocused) scope.launch { bringMonthlyDay.bringIntoView() } },
                        value = monthlyDayText,
                        onValueChange = { monthlyDayText = it },
                        label = { Text("Ayın kaçıncı günü (1..31)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    if (monthlyDayError != null) {
                        Text(
                            text = "Hata: $monthlyDayError",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (planType == "YEARLY") {
                    Text("Ay", style = MaterialTheme.typography.titleMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Button(onClick = { yearlyMonthMenuOpen = true }) {
                            Text(
                                when (yearlyMonth) {
                                    1 -> "Ocak"
                                    2 -> "Şubat"
                                    3 -> "Mart"
                                    4 -> "Nisan"
                                    5 -> "Mayıs"
                                    6 -> "Haziran"
                                    7 -> "Temmuz"
                                    8 -> "Ağustos"
                                    9 -> "Eylül"
                                    10 -> "Ekim"
                                    11 -> "Kasım"
                                    12 -> "Aralık"
                                    else -> yearlyMonth.toString()
                                },
                            )
                        }
                        DropdownMenu(expanded = yearlyMonthMenuOpen, onDismissRequest = { yearlyMonthMenuOpen = false }) {
                            (1..12).forEach { m ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (m) {
                                                1 -> "Ocak"
                                                2 -> "Şubat"
                                                3 -> "Mart"
                                                4 -> "Nisan"
                                                5 -> "Mayıs"
                                                6 -> "Haziran"
                                                7 -> "Temmuz"
                                                8 -> "Ağustos"
                                                9 -> "Eylül"
                                                10 -> "Ekim"
                                                11 -> "Kasım"
                                                12 -> "Aralık"
                                                else -> m.toString()
                                            },
                                        )
                                    },
                                    onClick = {
                                        yearlyMonth = m
                                        yearlyMonthMenuOpen = false
                                    },
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(bringYearlyDay)
                            .onFocusEvent { if (it.isFocused) scope.launch { bringYearlyDay.bringIntoView() } },
                        value = yearlyDayText,
                        onValueChange = { yearlyDayText = it },
                        label = { Text("Gün (1..31)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    if (yearlyDayError != null) {
                        Text(
                            text = "Hata: $yearlyDayError",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // Çalıştırma Modu
                Text("Çalıştırma Modu", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(onClick = { intervalModeMenuOpen = true }) {
                        Text(normalizedIntervalModeLabel(intervalMode))
                    }
                    DropdownMenu(expanded = intervalModeMenuOpen, onDismissRequest = { intervalModeMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Sabit Aralık") },
                            onClick = {
                                intervalMode = "FIXED_INTERVAL"
                                intervalModeMenuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Makro Bazlı") },
                            onClick = {
                                intervalMode = "MACRO_BASED"
                                intervalModeMenuOpen = false
                            },
                        )
                    }
                }

                Text(
                    text =
                        when (intervalMode) {
                            "FIXED_INTERVAL" -> "Belirlediğin dakikada bir çalıştırır. Örn: 60 dk => her 60 dakikada bir."
                            "MACRO_BASED" -> "Makro biter, ardından gecikme kadar bekler ve tekrar çalıştırır."
                            else -> ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                )

                if (intervalMode == "FIXED_INTERVAL") {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(bringFixed)
                            .onFocusEvent { if (it.isFocused) scope.launch { bringFixed.bringIntoView() } },
                        value = fixedIntervalMinutesText,
                        onValueChange = { fixedIntervalMinutesText = it },
                        label = { Text("Sabit aralık (dakika)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    if (fixedIntervalError != null) {
                        Text(
                            text = "Hata: $fixedIntervalError",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(bringExtra)
                            .onFocusEvent { if (it.isFocused) scope.launch { bringExtra.bringIntoView() } },
                        value = extraDelayMinutesText,
                        onValueChange = { extraDelayMinutesText = it },
                        label = { Text("Ek gecikme (dakika) (opsiyonel)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    if (extraDelayError != null) {
                        Text(
                            text = "Hata: $extraDelayError",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(bringInitial)
                        .onFocusEvent { if (it.isFocused) scope.launch { bringInitial.bringIntoView() } },
                    value = initialDelayMinutesText,
                    onValueChange = { initialDelayMinutesText = it },
                    label = { Text("İlk gecikme (dakika) (opsiyonel)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )

                if (selectedMacroId != null && selectedMacro?.isEnabled == false) {
                    Text(
                        text = "Seçilen makro devre dışı. Makrolar sekmesinden etkinleştir veya başka makro seç.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // Next run preview
                run {
                    val tr = remember { Locale("tr", "TR") }
                    val fmt = remember { DateTimeFormatter.ofPattern("dd MMM HH:mm", tr) }
                    val previewText =
                        when {
                            selectedMacroId == null -> "Eksik bilgi: Makro seçilmeli"
                            runsPerDayError != null -> "Eksik bilgi: Günde kaç kez çalışsın"
                            fixedIntervalError != null -> "Eksik bilgi: Sabit aralık dakikası girilmeli"
                            extraDelayError != null -> "Eksik bilgi: Ek gecikme değeri hatalı"
                            monthlyDayError != null -> "Eksik bilgi: Ayın günü girilmeli"
                            yearlyDayError != null -> "Eksik bilgi: Yıllık gün girilmeli"
                            previewNextRun?.nextRunAtEpochMs != null -> {
                                val dt = java.time.Instant.ofEpochMilli(previewNextRun.nextRunAtEpochMs).atZone(ZoneId.systemDefault())
                                "Bir sonraki çalışma: ${dt.format(fmt)}"
                            }

                            else -> "Bir sonraki çalışma hesaplanamadı"
                        }
                    Text(previewText, style = MaterialTheme.typography.bodyMedium)
                }

                // Extra bottom space so last fields can scroll above the fixed bottom bar.
                Spacer(Modifier.height(90.dp))
            }
        }
    }
}

