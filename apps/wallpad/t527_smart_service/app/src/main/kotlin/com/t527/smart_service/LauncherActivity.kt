package com.t527.smart_service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        startServiceAndFinish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startServiceAndFinish()
        } else {
            Toast.makeText(this, "마이크 권한 필요", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startServiceAndFinish() {
        val intent = Intent(this, VoiceAiService::class.java).apply {
            action = VoiceAiService.ACTION_MIC_GRANTED
        }
        startForegroundService(intent)
        Toast.makeText(this, "음성 AI 서비스 시작", Toast.LENGTH_SHORT).show()
        finish()
    }
}
