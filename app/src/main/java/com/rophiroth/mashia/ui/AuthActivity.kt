package com.rophiroth.mashia.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.rophiroth.mashia.MainActivity
import com.rophiroth.mashia.R

class AuthActivity : AppCompatActivity() {
    private lateinit var googleClient: GoogleSignInClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val already = GoogleSignIn.getLastSignedInAccount(this)
        if (already != null) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_auth)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        googleClient = GoogleSignIn.getClient(this, gso)

        findViewById<Button>(R.id.btn_google).setOnClickListener {
            startActivityForResult(googleClient.signInIntent, RC_SIGN_IN)
        }
        findViewById<Button>(R.id.btn_apple).setOnClickListener {
            Toast.makeText(this, "Apple sign-in coming soon", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_fb).setOnClickListener {
            Toast.makeText(this, "Facebook sign-in coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
                if (account != null) {
                    goToMain()
                }
            } catch (e: ApiException) {
                Toast.makeText(this, "Google sign-in failed: ${e.statusCode}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object { private const val RC_SIGN_IN = 9001 }
}

