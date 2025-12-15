package org.psyhackers.mashia

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.MenuItem
import android.widget.ImageView
import android.widget.TextView
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
import org.psyhackers.mashia.ui.LibraryFragment
import org.psyhackers.mashia.ui.SettingsFragment
import org.psyhackers.mashia.util.DebugFileLogger

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {
    private lateinit var drawer: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugFileLogger.init(applicationContext)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        // Show app version visibly
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: ""
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                (pInfo.longVersionCode and 0xFFFFFFFF).toInt()
            } else pInfo.versionCode
            toolbar.subtitle = "v $versionName ($versionCode)"
        } catch (_: Throwable) {}

        drawer = findViewById(R.id.drawer_layout)
        val toggle = ActionBarDrawerToggle(this, drawer, toolbar, R.string.app_name, R.string.app_name)
        drawer.addDrawerListener(toggle)
        toggle.syncState()

        val nav = findViewById<NavigationView>(R.id.nav_view)
        nav.setNavigationItemSelectedListener(this)

        // Header avatar + name/email
        val header = nav.getHeaderView(0)
        val img = header.findViewById<ImageView>(R.id.img_avatar)
        val name = header.findViewById<TextView>(R.id.txt_name)
        val email = header.findViewById<TextView>(R.id.txt_email)
        GoogleSignIn.getLastSignedInAccount(this)?.let { acc ->
            name.text = acc.displayName ?: ""
            email.text = acc.email ?: ""
            acc.photoUrl?.let { uri -> img.load(uri) }
        }

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
            R.id.nav_library -> supportFragmentManager.commit { replace(R.id.fragment_container, LibraryFragment()) }
            R.id.nav_settings -> supportFragmentManager.commit { replace(R.id.fragment_container, SettingsFragment()) }
            R.id.nav_logout -> doLogout()
        }
        drawer.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
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
}
