package com.ahmedrehan.simplepdfreader

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val message = TextView(this).apply {
            text = "Simple PDF Reader\n\nProject setup is ready."
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        setContentView(message)
    }
}
