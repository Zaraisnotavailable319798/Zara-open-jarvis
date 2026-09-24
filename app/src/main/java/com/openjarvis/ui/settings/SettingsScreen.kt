package com.openjarvis.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openjarvis.llm.UniversalAdapter
import kotlinx.coroutines.delay

private object SettingsColors {
    val Void950 = Color(0xFF08060D)
    val Void900 = Color(0xFF100C18)
    val Void800 = Color(0xFF171120)
    val Void600 = Color(0xFF31273D)

    val TextPrimary = Color(0xFFF5F1FA)
    val TextSecondary = Color(0xFFB9B0C4)
    val TextDisabled = Color(0xFF766C80)

    val BorderSubtle = Color(0xFF2B2235)
    val BorderGlow = Color(0xFF7650A8)

    val Violet = Color(0xFF9B6DFF)
    val VioletDim = Color(0xFF5B3B82)
    val Cyan = Color(0xFF58D7FF)
    val Green = Color(0xFF5FE39A)
    val Amber = Color(0xFFFFC857)
    val Red = Color(0xFFFF667A)
}

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onSaveProvider: (String, String, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedProvider by remember { mutableStateOf("Groq") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("llama-3.1-70b-versatile") }
    var showBaseUrlField by remember { mutableStateOf(false) }
    var showProvidersDropdown by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var savedVisible by remember { mutableStateOf(false) }
    var voiceEnabled by remember { mutableStateOf(false) }
    var speakResults by remember { mutableStateOf(true) }
    var sttMode by remember { mutableStateOf("Push to Talk") }

    LaunchedEffect(selectedProvider) {
        showBaseUrlField = selectedProvider == "Custom"
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SettingsColors.Void950)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            SettingsHeader(onNavigateBack = onNavigateBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                SectionLabel("AI PROVIDER")

                Spacer(modifier = Modifier.height(12.dp))

                ProviderSelectorCard(
                    selectedProvider = selectedProvider,
                    isExpanded = showProvidersDropdown,
                    onToggle = {
                        showProvidersDropdown = !showProvidersDropdown
                    },
                    onSelect = { provider ->
                        selectedProvider = provider
                        showProvidersDropdown = false
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                FloatingLabelTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = "Base URL",
                    isFocused = showBaseUrlField,
                    visible = showBaseUrlField
                )

                FloatingLabelTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = "API Key",
                    isFocused = false,
                    isPassword = !showPassword,
                    onTogglePassword = {
                        showPassword = !showPassword
                    }
                )

                FloatingLabelTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = "Model",
                    isFocused = false
                )

                Text(
                    text = "Works with any OpenAI-compatible API",
                    style = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight(400),
                        fontSize = 11.sp,
                        color = SettingsColors.TextDisabled
                    )
                )

                Spacer(modifier = Modifier.height(24.dp))

                TestConnectionButton(
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = model,
                    onSave = { name, url, key, mdl ->
                        onSaveProvider(name, url, key, mdl)
                        savedVisible = true
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))

                SectionLabel("VOICE")

                Spacer(modifier = Modifier.height(12.dp))

                SettingsToggleRow(
                    title = "Voice Assistant",
                    subtitle = "Push to Talk · VAD supported",
                    enabled = voiceEnabled,
                    onToggle = {
                        voiceEnabled = !voiceEnabled
                    }
                )

                if (voiceEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsToggleRow(
                        title = "Speak Results",
                        subtitle = "Jarvis reads results aloud",
                        enabled = speakResults,
                        onToggle = {
                            speakResults = !speakResults
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = SettingsColors.Void900,
                        border = BorderStroke(
                            width = 1.dp,
                            color = SettingsColors.BorderSubtle
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sttMode =
                                        if (sttMode == "Push to Talk") {
                                            "Auto (VAD)"
                                        } else {
                                            "Push to Talk"
                                        }
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "STT Mode",
                                    style = TextStyle(
                                        fontWeight = FontWeight(500),
                                        fontSize = 14.sp,
                                        color = SettingsColors.TextPrimary
                                    )
                                )

                                Text(
                                    text =
                                        if (sttMode == "Push to Talk") {
                                            "Hold mic to record, release to send"
                                        } else {
                                            "Tap to record, auto-sends on silence"
                                        },
                                    style = TextStyle(
                                        fontWeight = FontWeight(400),
                                        fontSize = 11.sp,
                                        color = SettingsColors.TextDisabled
                                    )
                                )
                            }

                            Text(
                                text = sttMode,
                                style = TextStyle(
                                    fontWeight = FontWeight(500),
                                    fontSize = 12.sp,
                                    color = SettingsColors.Violet
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = SettingsColors.Void800,
                        border = BorderStroke(
                            width = 1.dp,
                            color = SettingsColors.BorderSubtle
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = SettingsColors.Green,
                                modifier = Modifier.size(20.dp)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "Speech Recognition",
                                    style = TextStyle(
                                        fontWeight = FontWeight(500),
                                        fontSize = 14.sp,
                                        color = SettingsColors.TextPrimary
                                    )
                                )

                                Text(
                                    text = "Google Speech · Online",
                                    style = TextStyle(
                                        fontWeight = FontWeight(400),
                                        fontSize = 11.sp,
                                        color = SettingsColors.TextDisabled
                                    )
                                )
                            }

                            Text(
                                text = "Ready",
                                style = TextStyle(
                                    fontWeight = FontWeight(500),
                                    fontSize = 12.sp,
                                    color = SettingsColors.Green
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                SectionLabel("PERMISSIONS")

                Spacer(modifier = Modifier.height(12.dp))

                PermissionRow(
                    title = "Accessibility",
                    subtitle = "Required — Tap to enable"
                )

                Spacer(modifier = Modifier.height(12.dp))

                PermissionRow(
                    title = "Overlay",
                    subtitle = "Required — Tap to enable"
                )

                Spacer(modifier = Modifier.height(32.dp))

                SectionLabel("ABOUT")

                Spacer(modifier = Modifier.height(12.dp))

                AboutSection()

                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        SavedToast(
            visible = savedVisible,
            onDismiss = {
                savedVisible = false
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
        )
    }
}

@Composable
private fun SettingsHeader(
    onNavigateBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onNavigateBack) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = "Back",
                tint = SettingsColors.TextSecondary
            )
        }

        Text(
            text = "Settings",
            style = TextStyle(
                fontWeight = FontWeight(600),
                fontSize = 20.sp,
                color = SettingsColors.TextPrimary
            )
        )
    }

    Divider(
        modifier = Modifier.fillMaxWidth(),
        thickness = 1.dp,
        color = SettingsColors.BorderSubtle
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = TextStyle(
            fontWeight = FontWeight(600),
            fontSize = 10.sp,
            letterSpacing = 3.sp,
            color = SettingsColors.TextDisabled
        )
    )
}

@Composable
private fun ProviderSelectorCard(
    selectedProvider: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit
) {
    val borderColor = if (isExpanded) {
        SettingsColors.BorderGlow
    } else {
        SettingsColors.BorderSubtle
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
        color = SettingsColors.Void900,
        border = BorderStroke(width = 1.dp, color = borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderDot(provider = selectedProvider)

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = selectedProvider,
                style = TextStyle(
                    fontWeight = FontWeight(500),
                    fontSize = 14.sp,
                    color = SettingsColors.TextPrimary
                ),
                modifier = Modifier.weight(1f)
            )

            val rotationAngle by animateFloatAsState(
                targetValue = if (isExpanded) 180f else 0f,
                animationSpec = tween(200),
                label = "rotation"
            )

            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = SettingsColors.TextDisabled,
                modifier = Modifier.graphicsLayer {
                    rotationZ = rotationAngle
                }
            )
        }
    }

    AnimatedVisibility(
        visible = isExpanded,
        enter = expandVertically() + fadeIn(
            animationSpec = tween(200)
        ),
        exit = shrinkVertically() + fadeOut(
            animationSpec = tween(200)
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = SettingsColors.Void800,
            border = BorderStroke(
                width = 1.dp,
                color = SettingsColors.BorderSubtle
            )
        ) {
            Column {
                UniversalAdapter.AVAILABLE_PROVIDERS.forEach { provider ->
                    ProviderOption(
                        label = provider,
                        isSelected = provider == selectedProvider,
                        onClick = {
                            onSelect(provider)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderDot(provider: String) {
    val color = when (provider) {
        "Groq" -> SettingsColors.Violet
        "Google Gemini" -> SettingsColors.Cyan
        "OpenRouter" -> SettingsColors.Green
        "Anthropic Claude" -> SettingsColors.Amber
        "OpenAI" -> SettingsColors.Green
        "Ollama (Local)" -> SettingsColors.Cyan
        "Custom" -> SettingsColors.Violet
        else -> SettingsColors.Violet
    }

    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
    )
}

@Composable
private fun ProviderOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .background(SettingsColors.Violet)
            )

            Spacer(modifier = Modifier.width(12.dp))
        } else {
            Spacer(modifier = Modifier.width(15.dp))
        }

        Text(
            text = label,
            style = TextStyle(
                fontWeight = FontWeight(500),
                fontSize = 14.sp,
                color =
                    if (isSelected) {
                        SettingsColors.Violet
                    } else {
                        SettingsColors.TextPrimary
                    }
            )
        )
    }
}

@Composable
fun FloatingLabelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isFocused: Boolean,
    isPassword: Boolean = false,
    visible: Boolean = true,
    onTogglePassword: (() -> Unit)? = null
) {
    val labelOffset by animateFloatAsState(
        targetValue =
            if (isFocused || value.isNotEmpty()) {
                -20f
            } else {
                0f
            },
        animationSpec = spring(
            stiffness = 300f,
            dampingRatio = 0.75f
        ),
        label = "offset"
    )

    val labelScale by animateFloatAsState(
        targetValue =
            if (isFocused || value.isNotEmpty()) {
                0.75f
            } else {
                1f
            },
        animationSpec = spring(
            stiffness = 300f,
            dampingRatio = 0.75f
        ),
        label = "scale"
    )

    val borderColor = if (isFocused) {
        SettingsColors.Violet
    } else {
        SettingsColors.BorderSubtle
    }

    if (!visible) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(14.dp),
        color = SettingsColors.Void900,
        border = BorderStroke(width = 1.dp, color = borderColor)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontWeight = FontWeight(400),
                    fontSize = 14.sp,
                    color = SettingsColors.TextSecondary
                ),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .graphicsLayer {
                        translationY = labelOffset.dp.toPx()
                        scaleX = labelScale
                        scaleY = labelScale
                    }
            )

            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = 16.dp,
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 12.dp
                    ),
                textStyle = TextStyle(
                    fontWeight = FontWeight(400),
                    fontSize = 14.sp,
                    color = SettingsColors.TextPrimary
                ),
                visualTransformation =
                    if (isPassword) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    }
            )
        }
    }
}

@Composable
private fun TestConnectionButton(
    apiKey: String,
    baseUrl: String,
    model: String,
    onSave: (String, String, String, String) -> Unit
) {
    var testState by remember {
        mutableStateOf<TestState>(TestState.Idle)
    }

    val borderColor = when (testState) {
        is TestState.Idle -> SettingsColors.VioletDim
        is TestState.Loading -> SettingsColors.Violet
        is TestState.Success -> SettingsColors.Green
        is TestState.Error -> SettingsColors.Red
    }

    val textColor = when (testState) {
        is TestState.Idle -> SettingsColors.Violet
        is TestState.Loading -> SettingsColors.Violet
        is TestState.Success -> SettingsColors.Green
        is TestState.Error -> SettingsColors.Red
    }

    OutlinedButton(
        onClick = {
            onSave(
                "Groq",
                baseUrl,
                apiKey,
                model
            )

            testState = TestState.Success(150)
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(width = 1.dp, color = borderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = textColor
        )
    ) {
        when (val state = testState) {
            is TestState.Idle -> {
                Text("Test Connection")
            }

            is TestState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = SettingsColors.Violet
                )
            }

            is TestState.Success -> {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text("Connected — ${state.ms}ms")
            }

            is TestState.Error -> {
                Text("Failed — check key")
            }
        }
    }
}

sealed class TestState {
    data object Idle : TestState()
    data object Loading : TestState()
    data class Success(val ms: Long) : TestState()
    data class Error(val message: String) : TestState()
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .then(
                if (onToggle != null) {
                    Modifier.clickable {
                        onToggle()
                    }
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(16.dp),
        color = SettingsColors.Void900,
        border = BorderStroke(
            width = 1.dp,
            color = SettingsColors.BorderSubtle
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = SettingsColors.TextSecondary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = TextStyle(
                        fontWeight = FontWeight(500),
                        fontSize = 14.sp,
                        color = SettingsColors.TextPrimary
                    )
                )

                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontWeight = FontWeight(400),
                        fontSize = 11.sp,
                        color = SettingsColors.TextDisabled
                    )
                )
            }

            Switch(
                checked = enabled,
                onCheckedChange = {
                    onToggle?.invoke()
                },
                enabled = onToggle != null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = SettingsColors.TextPrimary,
                    checkedTrackColor = SettingsColors.Violet,
                    uncheckedThumbColor = SettingsColors.TextDisabled,
                    uncheckedTrackColor = SettingsColors.Void600
                )
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(16.dp),
        color = SettingsColors.Void900,
        border = BorderStroke(
            width = 1.dp,
            color = SettingsColors.BorderSubtle
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        SettingsColors.Red.copy(
                            alpha = 0.12f
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = SettingsColors.Red,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = TextStyle(
                        fontWeight = FontWeight(500),
                        fontSize = 14.sp,
                        color = SettingsColors.TextPrimary
                    )
                )

                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontWeight = FontWeight(400),
                        fontSize = 11.sp,
                        color = SettingsColors.Red
                    )
                )
            }

            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = SettingsColors.Red
            )
        }
    }
}

@Composable
private fun AboutSection() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SettingsColors.Void900,
        border = BorderStroke(
            width = 1.dp,
            color = SettingsColors.BorderSubtle
        )
    ) {
        Column {
            AboutRow(
                title = "Version",
                value = "M1.0 (build 1)"
            )

            Divider(
                thickness = 1.dp,
                color = SettingsColors.BorderSubtle
            )

            AboutRow(
                title = "License",
                value = "MIT Open Source"
            )

            Divider(
                thickness = 1.dp,
                color = SettingsColors.BorderSubtle
            )

            AboutRow(
                title = "GitHub",
                value = "tokenarc/open-jarvis",
                isLink = true
            )

            Divider(
                thickness = 1.dp,
                color = SettingsColors.BorderSubtle
            )

            Text(
                text = "Built on Android · From Termux with love",
                style = TextStyle(
                    fontWeight = FontWeight(400),
                    fontSize = 12.sp,
                    color = SettingsColors.TextDisabled
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun AboutRow(
    title: String,
    value: String,
    isLink: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = TextStyle(
                fontWeight = FontWeight(400),
                fontSize = 14.sp,
                color = SettingsColors.TextSecondary
            )
        )

        Text(
            text = value,
            style = TextStyle(
                fontWeight = FontWeight(400),
                fontSize = 14.sp,
                color =
                    if (isLink) {
                        SettingsColors.Violet
                    } else {
                        SettingsColors.TextPrimary
                    }
            )
        )
    }
}

@Composable
private fun SavedToast(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(visible) {
        if (visible) {
            delay(1500)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { 40 } + fadeIn(),
        exit = slideOutVertically { 40 } + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = SettingsColors.Void800,
            border = BorderStroke(
                width = 1.dp,
                color = SettingsColors.Green.copy(alpha = 0.25f)
            )
        ) {
            Text(
                text = "✓ saved",
                style = TextStyle(
                    fontWeight = FontWeight(500),
                    fontSize = 12.sp,
                    color = SettingsColors.Green
                ),
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 8.dp
                )
            )
        }
    }
}
