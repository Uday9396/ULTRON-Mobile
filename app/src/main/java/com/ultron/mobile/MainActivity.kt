package com.ultron.mobile

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "ULTRON\n\nSYSTEMS ONLINE"
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }

        setContentView(title)
    }
}
