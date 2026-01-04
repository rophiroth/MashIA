package org.psyhackers.mashia

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Switch
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import coil.load
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth
import org.psyhackers.mashia.ui.AuthActivity
import org.psyhackers.mashia.ui.ChatFragment
import org.psyhackers.mashia.ui.DebugFragment
import org.psyhackers.mashia.ui.SettingsFragment
import org.psyhackers.mashia.ui.LibraryFragment
import org.psyhackers.mashia.util.DebugFileLogger

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {
    private lateinit var drawer: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugFileLogger.init(applicationContext)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.title = ""
        try {
            val custom = layoutInflater.inflate(R.layout.toolbar_chat, toolbar, false)
            toolbar.addView(custom)
            val appBtn = custom.findViewById<TextView>(R.id.toolbar_app)
            appBtn.setOnClickListener { showAppInfo() }
        } catch (_: Throwable) {}

        drawer = findViewById(R.id.drawer_layout)
        val toggle = ActionBarDrawerToggle(this, drawer, toolbar, R.string.app_name, R.string.app_name)
        drawer.addDrawerListener(toggle)
        toggle.syncState()

        val nav = findViewById<NavigationView>(R.id.nav_view)
        nav.setNavigationItemSelectedListener(this)

        // Header avatar + name/email
        val img = findViewById<ImageView>(R.id.img_avatar)
        val name = findViewById<TextView>(R.id.txt_name)
        val email = findViewById<TextView>(R.id.txt_email)
        val libraryHeader = findViewById<View>(R.id.drawer_library_header)
        val libraryContainer = findViewById<android.widget.FrameLayout>(R.id.drawer_library_container)
        val libraryToggle = findViewById<android.widget.ImageButton>(R.id.btn_library_toggle)
        val includeSubfolders = findViewById<Switch>(R.id.switch_library_include_subfolders)
        var libraryVisible = true
        GoogleSignIn.getLastSignedInAccount(this)?.let { acc ->
            name.text = acc.displayName ?: ""
            email.text = acc.email ?: ""
            acc.photoUrl?.let { uri -> img.load(uri) }
        }
        try {
            if (supportFragmentManager.findFragmentById(R.id.drawer_library_container) == null) {
                supportFragmentManager.commit {
                    replace(R.id.drawer_library_container, LibraryFragment.newInstance(embedded = true))
                }
            }
        } catch (_: Throwable) {}
        fun updateLibraryToggle() {
            val icon = if (libraryVisible) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float
            libraryToggle.setImageResource(icon)
        }
        updateLibraryToggle()
        val toggleLibrary = View.OnClickListener {
            libraryVisible = !libraryVisible
            libraryContainer.visibility = if (libraryVisible) android.view.View.VISIBLE else android.view.View.GONE
            updateLibraryToggle()
        }
        libraryToggle.setOnClickListener(toggleLibrary)
        libraryHeader.setOnClickListener(toggleLibrary)

        try {
            val prefs = getSharedPreferences("settings", 0)
            includeSubfolders.isChecked = prefs.getBoolean(SettingsFragment.KEY_LIBRARY_INCLUDE_SUBFOLDERS, true)
            includeSubfolders.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(SettingsFragment.KEY_LIBRARY_INCLUDE_SUBFOLDERS, checked).apply()
                val lib = supportFragmentManager.findFragmentById(R.id.drawer_library_container)
                if (lib is LibraryFragment) lib.refreshFromStore()
            }
        } catch (_: Throwable) {}

        if (savedInstanceState == null) {
            supportFragmentManager.commit { replace(R.id.fragment_container, ChatFragment()) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val o = when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "unknown"
        }
        DebugFileLogger.log("MainActivity", "configChanged orientation=$o")
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_chat -> supportFragmentManager.commit { replace(R.id.fragment_container, ChatFragment()) }
            R.id.nav_debug -> supportFragmentManager.commit { replace(R.id.fragment_container, DebugFragment()) }
            R.id.nav_settings -> supportFragmentManager.commit { replace(R.id.fragment_container, SettingsFragment()) }
            R.id.nav_logout -> doLogout()
        }
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)
        drawer.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)
            drawer.closeDrawer(GravityCompat.START)
            return
        }

        val current: Fragment? = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current !is ChatFragment) {
            supportFragmentManager.commit { replace(R.id.fragment_container, ChatFragment()) }
            return
        }

        super.onBackPressed()
    }

    fun closeDrawer() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)
            drawer.closeDrawer(GravityCompat.START)
        }
    }

    private fun doLogout() {
        // Firebase logout
        FirebaseAuth.getInstance().signOut()
        // Google logout
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().build()
        val client = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(this, gso)
        client.signOut().addOnCompleteListener {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
        }
    }

    private fun showAppInfo() {
        val prefs = getSharedPreferences("settings", 0)
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "" }
        val groqModel = prefs.getString(org.psyhackers.mashia.ui.SettingsFragment.KEY_GROQ_CHAT_MODEL, getString(R.string.groq_model)).orEmpty()
        val cloudModel = prefs.getString(org.psyhackers.mashia.ui.SettingsFragment.KEY_CLOUD_STT_MODEL, "auto").orEmpty()
        val whisperLang = prefs.getString(org.psyhackers.mashia.ui.SettingsFragment.KEY_LANGUAGE, "auto").orEmpty()
        val whisperStatus = prefs.getString("whisper_status_text", "Whisper: unknown").orEmpty()
        val msg = buildString {
            append("Version: ").append(versionName.ifBlank { "?" }).append("\n")
            append(whisperStatus).append("\n")
            append("Groq chat model: ").append(groqModel.ifBlank { "?" }).append("\n")
            append("Cloud STT model: ").append(cloudModel.ifBlank { "?" }).append("\n")
            append("Whisper language: ").append(whisperLang.ifBlank { "auto" })
        }
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("MashIA")
                .setMessage(msg)
                .setPositiveButton("Settings") { _, _ ->
                    supportFragmentManager.commit { replace(R.id.fragment_container, SettingsFragment()) }
                }
                .setNeutralButton("Models") { _, _ ->
                    supportFragmentManager.commit { replace(R.id.fragment_container, SettingsFragment()) }
                }
                .setNegativeButton("Close", null)
                .show()
        } catch (_: Throwable) {}
    }
}
