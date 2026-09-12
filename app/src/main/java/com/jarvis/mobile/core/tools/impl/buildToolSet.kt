package com.jarvis.mobile.core.tools.impl

import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.tools.Tool
import com.jarvis.mobile.core.tools.ToolRegistry
import com.jarvis.mobile.core.tools.impl.UiResolve

/** Builds the complete tool set (spec: ACTION TOOL SYSTEM). */
fun buildToolSet(container: JarvisApp.Container): List<Tool> = listOf(
    // App & navigation
    OpenAppTool,
    CloseAppTool,
    LaunchIntentTool,
    ShareContentTool,
    ListAppsTool,
    // Screen interaction
    TapTool,
    DoubleTapTool,
    LongPressTool,
    TypeTextTool,
    ClearTextTool,
    ScrollTool,
    SwipeTool,
    CopyTextTool,
    PasteTool,
    ReadScreenTool,
    FindElementTool,
    PressBackTool,
    PressHomeTool,
    WaitTool,
    WaitForChangeTool,
    // Device controls
    ControlBrightnessTool,
    ControlVolumeTool,
    ControlFlashlightTool,
    ControlWifiTool,
    ControlBluetoothTool,
    // Information & data
    ReadNotificationsTool,
    GetLocationTool,
    ReadCalendarTool,
    CreateCalendarEventTool,
    FindFileTool,
    OpenFileTool,
    ShareFileTool,
    // Communication (human-confirms-send)
    CallContactTool,
    SendSmsTool,
    WhatsAppMessageTool,
    TelegramMessageTool,
    SendEmailTool,
    // Media, navigation & system actions
    OpenUrlTool,
    YoutubeSearchTool,
    MapsNavigateTool,
    SetAlarmTool,
    SetTimerTool,
    MediaPlayPauseTool,
    TakeScreenshotTool,
    ToggleDndTool,
)
