package com.automation.agent

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.automation.agent.services.ControllerService

/**
 * Main Activity for Android Automation Agent
 * 
 * This activity serves as the entry point and provides UI for monitoring
 * the agent status. The actual automation work is done by ControllerService.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start the controller service
        startControllerService()
    }

    private fun startControllerService() {
        val serviceIntent = Intent(this, ControllerService::class.java)
        startForegroundService(serviceIntent)
    }
}

