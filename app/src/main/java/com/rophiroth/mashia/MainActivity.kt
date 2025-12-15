package com.rophiroth.mashia

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.fragment.app.commit
import com.rophiroth.mashia.ui.ChatFragment
import com.rophiroth.mashia.ui.LibraryFragment
import com.rophiroth.mashia.ui.SettingsFragment

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bottom = findViewById<BottomNavigationView>(R.id.bottom_nav)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, ChatFragment())
            }
        }
        bottom.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_chat -> supportFragmentManager.commit {
                    replace(R.id.fragment_container, ChatFragment())
                }
                R.id.tab_library -> supportFragmentManager.commit {
                    replace(R.id.fragment_container, LibraryFragment())
                }
                R.id.tab_settings -> supportFragmentManager.commit {
                    replace(R.id.fragment_container, SettingsFragment())
                }
            }
            true
        }
    }
}
