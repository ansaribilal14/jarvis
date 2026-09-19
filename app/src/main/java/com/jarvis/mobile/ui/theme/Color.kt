package com.jarvis.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// =============================================================================
// v2.0 "Expressive" palette - Material 3 dynamic-toned JARVIS identity.
// Deep space neutrals + sentinel teal primary + plasma violet tertiary.
// All v1.x symbol names are preserved (Accent, Danger, Ok, DarkBg, ...) so the
// whole app inherits the new look with zero call-site churn.
// =============================================================================

// ---- Dark (primary theme, first-class) -------------------------------------
val DarkBg = Color(0xFF070B10)        // deep space, darker + bluer than v1
val DarkSurface = Color(0xFF0F141B)
val DarkSurfaceHi = Color(0xFF171F29)
val DarkOutline = Color(0xFF2A3644)
val DarkText = Color(0xFFE9F1F4)
val DarkTextDim = Color(0xFF93A6B1)

// ---- Light -----------------------------------------------------------------
val LightBg = Color(0xFFF6F8F7)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceHi = Color(0xFFECF2F0)
val LightOutline = Color(0xFFD6DEDC)
val LightText = Color(0xFF151E1D)
val LightTextDim = Color(0xFF55676B)

// ---- JARVIS accent system ---------------------------------------------------
val Accent = Color(0xFF2FE6C8)        // sentinel teal - brighter for expressive contrast
val AccentDeep = Color(0xFF0E8C7B)
val AccentDim = Color(0x332FE6C8)
val Warn = Color(0xFFF2B544)          // medium risk
val Danger = Color(0xFFE4574F)        // high risk / failed
val Ok = Color(0xFF63D97E)

// ---- v2.0 expressive extras -------------------------------------------------
val Plasma = Color(0xFF9B8CFF)        // tertiary: plasma violet (AI surfaces)
val PlasmaDeep = Color(0xFF5D4FD3)
val AquaHi = Color(0xFF7DE8E0)        // gradient partner for Accent
val RecordingRed = Color(0xFFFF5A52)  // REC pill / recording states
val SuccessContainer = Color(0xFF0F2E1D)
val OnSuccessContainer = Color(0xFF9BF0B9)
