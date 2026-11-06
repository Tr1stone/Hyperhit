package com.example.hyperhit

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class AutoClickService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }
}